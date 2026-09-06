#!/usr/bin/env python3
"""Named-command relay for the tailnet.

Commands are defined server-side in /etc/wakepc.conf — the phone can only
invoke them by name, never send shell.

Callers are identified by asking tailscaled who owns the source address
(`tailscale whois`), so a device you already trust on the tailnet needs no
credential at all. A bearer token remains as a fallback for hosts without
the tailscale CLI. Endpoints:

    GET  /commands       -> {"commands": [{"name": "wake-pc", "ping": true,
                                           "elevated": false}]}
    POST /run/<name>     -> run that command; commands marked `confirm` also
                            require the header X-WakePC-Confirm: <code>
    GET  /status/<name>  -> {"awake": bool} for commands that define a ping ip

    POST /wake, GET /status: legacy 0.1 endpoints, mapped onto the first
    WOL / first ping-able command so old clients keep working.

Stdlib only — no pip installs. See wakepc.conf.example for the config format.
"""

import configparser
import ipaddress
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Windows keeps its config beside the service rather than in /etc; the
# installer points here with WAKEPC_CONFIG instead of patching the source.
CONFIG_PATH = os.environ.get("WAKEPC_CONFIG", "/etc/wakepc.conf")
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")

# The ranges Tailscale hands out. An address outside them was never a peer.
TAILNET_NETS = (
    ipaddress.ip_network("100.64.0.0/10"),
    ipaddress.ip_network("fd7a:115c:a1e0::/48"),
)

# Commands that interrupt a machine somebody may be using. Used only to pick
# a default for `confirm` — fail safe, so an unrecognised shell command is
# treated as disruptive and the config can relax it.
DISRUPTIVE_RE = re.compile(
    r"shut ?down|restart|reboot|sleep|suspend|hibernate|logoff|log ?out|poweroff",
    re.IGNORECASE,
)


class Command:
    def __init__(self, name, run, ping, broadcast_ip, confirm):
        self.name = name
        self.run = run  # ("wol", mac) or ("shell", cmdline)
        self.ping = ping
        self.broadcast_ip = broadcast_ip
        self.confirm = confirm  # needs the confirmation code as well as identity


def load_config():
    parser = configparser.ConfigParser()
    # utf-8-sig: Windows editors and PowerShell happily write a BOM, which
    # configparser would otherwise read as part of the first line.
    if not parser.read(CONFIG_PATH, encoding="utf-8-sig"):
        sys.exit(f"error: could not read {CONFIG_PATH}")
    main = parser["wakepc"]
    trust_tailnet = main.getboolean("trust_tailnet", True)
    token = main.get("token", "")
    if token and len(token) < 8:
        sys.exit("error: a token must be at least 8 characters (wakepc.py genpass)")
    if not token and not trust_tailnet:
        sys.exit("error: set a token, or leave trust_tailnet on so peers are identified")
    allow_users = {
        u.strip().lower() for u in main.get("allow_users", "").split(",") if u.strip()
    }
    confirm_code = main.get("confirm_code", "").strip()

    commands = {}
    for section in parser.sections():
        if not section.startswith("command:"):
            continue
        name = section.split(":", 1)[1]
        if not NAME_RE.match(name):
            sys.exit(f"error: bad command name: {name}")
        raw = parser[section].get("run", "").strip()
        if raw.upper().startswith("WOL "):
            mac = raw[4:].strip().replace(":", "").replace("-", "").lower()
            if len(mac) != 12:
                sys.exit(f"error: bad MAC in [{section}]")
            run = ("wol", mac)
        elif raw.startswith("shell "):
            run = ("shell", raw[6:].strip())
        else:
            sys.exit(f"error: [{section}] run must start with 'WOL ' or 'shell '")
        # A WOL packet can only ever turn something on, so it is never
        # disruptive; anything else is, until the config says otherwise.
        default_confirm = run[0] == "shell" and bool(DISRUPTIVE_RE.search(raw))
        commands[name] = Command(
            name=name,
            run=run,
            ping=parser[section].get("ping", "").strip() or None,
            broadcast_ip=parser[section].get("broadcast_ip", "255.255.255.255"),
            confirm=parser[section].getboolean("confirm", default_confirm),
        )
    if not commands:
        sys.exit("error: no [command:*] sections defined")
    if confirm_code and not confirm_code.isdigit():
        sys.exit("error: confirm_code must be digits")
    if any(c.confirm for c in commands.values()) and not confirm_code:
        # Normal for a wake-only setup: the app is then the only gate, and
        # with no code set there is no gate at all. Said once, not as a warning.
        print("note: no confirm_code set — elevated commands are gated by the app only")

    return {
        "token": token,
        "trust_tailnet": trust_tailnet,
        "allow_users": allow_users,
        "confirm_code": confirm_code,
        "bind_host": main.get("bind_host", "auto"),
        "port": main.getint("port", 8787),
        "commands": commands,
    }


def tailscale_bin():
    """The tailscale CLI, or None if this host has not got one."""
    found = shutil.which("tailscale")
    if found:
        return found
    for guess in (
        r"C:\Program Files\Tailscale\tailscale.exe",
        "/usr/bin/tailscale",
        "/usr/local/bin/tailscale",
        "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
    ):
        if os.path.exists(guess):
            return guess
    return None


def _tailscale(*args, timeout=5):
    binary = tailscale_bin()
    if binary is None:
        return None
    try:
        done = subprocess.run([binary, *args], capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return done.stdout if done.returncode == 0 else None


def own_tailnet_addr():
    """This host's own tailnet address, for binding and for the QR."""
    out = _tailscale("ip", "-4")
    if not out:
        return None
    first = out.decode(errors="replace").strip().splitlines()
    return first[0].strip() if first else None


def own_login():
    """The tailnet account this node belongs to — the default allowed user."""
    addr = own_tailnet_addr()
    identity = whois_peer(addr) if addr else None
    return identity[0] if identity else None


_whois_lock = threading.Lock()
_whois_cache = {}
WHOIS_TTL = 300


def whois_peer(ip: str):
    """Ask tailscaled who owns `ip`. Returns (login, node name), or None.

    This is the whole authentication story: WireGuard has already proved the
    packet came from that peer's key, so the address is not forgeable and
    tailscaled can name its owner. Cached, since it shells out.
    """
    now = time.time()
    with _whois_lock:
        cached = _whois_cache.get(ip)
        if cached and now - cached[0] < WHOIS_TTL:
            return cached[1]
    identity = None
    raw = _tailscale("whois", "--json", f"{ip}:0")
    if raw:
        try:
            parsed = json.loads(raw)
            node = parsed.get("Node") or {}
            identity = (
                (parsed.get("UserProfile") or {}).get("LoginName", ""),
                node.get("ComputedName") or node.get("Name", "").rstrip("."),
            )
        except (ValueError, AttributeError):
            identity = None
    with _whois_lock:
        _whois_cache[ip] = (now, identity)
    return identity


def send_magic_packet(mac_hex: str, broadcast_ip: str) -> None:
    payload = b"\xff" * 6 + bytes.fromhex(mac_hex) * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for _ in range(3):
            sock.sendto(payload, (broadcast_ip, 9))


def probe_ping(ip: str, count: int) -> dict:
    """Ping `ip` `count` times and report awake + rtt stats."""
    count = max(1, min(count, 10))
    if sys.platform == "win32":
        # Windows ping speaks a different dialect and has no interval flag.
        args = ["ping", "-n", str(count), "-w", "1000", ip]
    else:
        args = ["ping", "-c", str(count), "-W", "1"]
        if count > 1:
            args += ["-i", "1"]
        args.append(ip)
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=count * 2 + 3)
    except subprocess.TimeoutExpired:
        return {"awake": False, "sent": count, "loss_pct": 100.0}
    body = {"awake": result.returncode == 0, "sent": count}
    loss = re.search(r"(\d+(?:\.\d+)?)% packet loss", result.stdout)
    if loss:
        body["loss_pct"] = float(loss.group(1))
    rtt = re.search(r"= ([\d.]+)/([\d.]+)/([\d.]+)", result.stdout)
    if rtt:
        body["min_ms"] = float(rtt.group(1))
        body["avg_ms"] = float(rtt.group(2))
        body["max_ms"] = float(rtt.group(3))
    else:
        win = re.search(r"Minimum = (\d+)ms, Maximum = (\d+)ms, Average = (\d+)ms", result.stdout)
        if win:
            body["min_ms"] = float(win.group(1))
            body["max_ms"] = float(win.group(2))
            body["avg_ms"] = float(win.group(3))
    if "loss_pct" not in body:
        lost = re.search(r"\((\d+)% loss\)", result.stdout)
        if lost:
            body["loss_pct"] = float(lost.group(1))
    return body


def run_command(cmd: Command) -> dict:
    kind, arg = cmd.run
    if kind == "wol":
        send_magic_packet(arg, cmd.broadcast_ip)
        return {"ok": True}
    result = subprocess.run(arg, shell=True, timeout=30,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return {"ok": result.returncode == 0, "exit": result.returncode}


class Handler(BaseHTTPRequestHandler):
    config = None  # injected in main()

    def _reply(self, code: int, body: dict) -> None:
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _reply_raw(self, code: int, content_type: str, data: bytes) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    # A confirmation code is short by design, so guessing has to be capped:
    # after 8 bad attempts in 5 minutes everything is refused until the
    # window clears. Also covers the fallback token.
    _auth_lock = threading.Lock()
    _auth_failures = []

    def _locked_out(self) -> bool:
        now = time.time()
        with Handler._auth_lock:
            Handler._auth_failures[:] = [t for t in Handler._auth_failures if now - t < 300]
            return len(Handler._auth_failures) >= 8

    def _record_failure(self, what: str) -> None:
        with Handler._auth_lock:
            Handler._auth_failures.append(time.time())
        print(f"{what} failure from {self.address_string()}", flush=True)

    def _peer(self):
        """The tailnet identity behind this request, if we can establish one."""
        if not self.config["trust_tailnet"]:
            return None
        try:
            addr = ipaddress.ip_address(self.client_address[0])
        except ValueError:
            return None
        if not any(addr in net for net in TAILNET_NETS):
            return None
        identity = whois_peer(self.client_address[0])
        if identity is None:
            return None
        allowed = self.config["allow_users"]
        if allowed and identity[0].lower() not in allowed:
            print(f"refused {identity[0]} ({identity[1]}) — not in allow_users", flush=True)
            return None
        return identity

    def _authorized(self) -> bool:
        if self._locked_out():
            print(f"auth locked out ({self.address_string()})", flush=True)
            return False
        if self._peer() is not None:
            return True
        token = self.config["token"]
        supplied = self.headers.get("Authorization", "")
        if token and supplied == f"Bearer {token}":
            return True
        if supplied:
            self._record_failure("auth")
        return False

    def _confirmed(self, cmd) -> bool:
        """Commands that interrupt a running machine need the code as well.

        Identity says who is asking; this says they meant it. Without it a
        borrowed unlocked phone could shut the machine down in one tap.
        """
        if not cmd.confirm:
            return True
        expected = self.config["confirm_code"]
        if not expected:
            return True  # nothing to check against; the app is the only gate
        if secrets.compare_digest(self.headers.get("X-WakePC-Confirm", ""), expected):
            return True
        self._record_failure("confirm")
        return False

    def _command(self, name):
        return self.config["commands"].get(name)

    def do_GET(self):
        # The panel page itself is public (it holds no secrets); every API
        # call it makes, /qr.png included, still needs the bearer token.
        if urllib.parse.urlparse(self.path).path in ("", "/"):
            return self._reply_raw(200, "text/html; charset=utf-8", PANEL_HTML.encode())
        if not self._authorized():
            return self._reply(401, {"error": "unauthorized"})
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        try:
            count = int(query.get("count", ["1"])[0])
        except ValueError:
            count = 1
        if parsed.path == "/qr.png":
            if shutil.which("qrencode") is None:
                return self._reply(500, {"error": "qrencode not installed"})
            uri = "wakepc://c?" + urllib.parse.urlencode({
                "name": socket.gethostname().lower(),
                "host": f"{self.server.server_address[0]}:{self.config['port']}",
                "token": self.config["token"],
            })
            png = subprocess.run(
                ["qrencode", "-o", "-", "-t", "PNG", "-s", "8", uri],
                capture_output=True,
            ).stdout
            return self._reply_raw(200, "image/png", png)
        if parsed.path == "/commands":
            # `elevated` is decided here, not guessed by the client: this is
            # the only place that knows a command actually runs `shutdown`.
            listing = [
                {"name": c.name, "ping": c.ping is not None, "elevated": c.confirm}
                for c in self.config["commands"].values()
            ]
            identity = self._peer()
            return self._reply(200, {
                "commands": listing,
                "you": identity[1] if identity else None,
                "confirm_enforced": bool(self.config["confirm_code"]),
            })
        if parsed.path.startswith("/status/"):
            cmd = self._command(parsed.path[len("/status/"):])
            if cmd is None:
                return self._reply(404, {"error": "unknown command"})
            if cmd.ping is None:
                return self._reply(404, {"error": "command has no ping"})
            return self._reply(200, probe_ping(cmd.ping, count))
        if parsed.path == "/status":  # legacy 0.1
            cmd = next((c for c in self.config["commands"].values() if c.ping), None)
            if cmd is None:
                return self._reply(404, {"error": "no ping-able command"})
            return self._reply(200, probe_ping(cmd.ping, 1))
        self._reply(404, {"error": "not found"})

    def do_POST(self):
        if not self._authorized():
            return self._reply(401, {"error": "unauthorized"})
        if self.path.startswith("/run/"):
            cmd = self._command(self.path[len("/run/"):])
            if cmd is None:
                return self._reply(404, {"error": "unknown command"})
            if not self._confirmed(cmd):
                return self._reply(403, {"error": "confirmation code required"})
            try:
                return self._reply(200, run_command(cmd))
            except (OSError, subprocess.TimeoutExpired) as exc:
                return self._reply(500, {"error": str(exc)})
        if self.path == "/wake":  # legacy 0.1
            cmd = next((c for c in self.config["commands"].values() if c.run[0] == "wol"), None)
            if cmd is None:
                return self._reply(404, {"error": "no WOL command"})
            send_magic_packet(cmd.run[1], cmd.broadcast_ip)
            return self._reply(200, {"sent": True})
        self._reply(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} {fmt % args}", flush=True)


PANEL_HTML = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>wakepc</title>
<style>
  body { margin:0; background:#0b0d0f; color:#e6e3dc; font-family:ui-monospace,Consolas,Menlo,monospace; }
  .wrap { max-width:560px; margin:0 auto; padding:34px 20px; }
  h1 { font-size:13px; letter-spacing:4px; font-weight:700; margin:0 0 26px 0; }
  h1 span { color:#ff5d49; }
  .label { font-size:10px; letter-spacing:2px; color:#5f6871; margin:26px 0 10px 0; }
  .row { display:flex; align-items:center; gap:12px; background:#101315; border:1px solid #1e242a;
         border-radius:8px; padding:14px; margin-bottom:10px; }
  .name { font-size:14px; flex-grow:1; }
  .status { font-size:10px; letter-spacing:1.5px; color:#5f6871; }
  .status.ok { color:#45d06d; text-shadow:0 0 10px rgba(69,208,109,.7); }
  .status.busy { color:#e2a63d; text-shadow:0 0 8px rgba(226,166,61,.7); }
  .status.err { color:#ff5d49; }
  button { background:none; border:1px solid #242b31; border-radius:5px; color:#aab2b9;
           font:inherit; font-size:12px; padding:9px 16px; cursor:pointer; }
  button:hover { border-color:#3a434c; }
  button.primary { background:#e6e3dc; color:#0b0d0f; border:none; font-weight:700;
                   letter-spacing:1px; width:100%; padding:14px; border-radius:8px; }
  input { background:#101315; border:1px solid #1e242a; border-radius:6px; color:#e6e3dc;
          font:inherit; font-size:14px; padding:12px 14px; width:100%; box-sizing:border-box; margin-bottom:12px; }
  #qrimg { display:none; margin-top:12px; border-radius:8px; width:260px; max-width:100%; }
  .hint { font-size:11px; color:#3a434c; line-height:1.7; }
</style></head><body><div class="wrap">
<h1><span>&#9211;</span> WAKEPC <span style="color:#5f6871">PANEL</span></h1>
<div id="login" style="display:none">
  <div class="label">TOKEN</div>
  <input id="tok" type="text" autocomplete="off" placeholder="pin or passphrase from /etc/wakepc.conf">
  <button class="primary" onclick="saveTok()">unlock</button>
</div>
<div id="panel" style="display:none">
  <div class="label">COMMANDS</div>
  <div id="cmds"></div>
  <div class="label">SETUP QR</div>
  <button onclick="showQr()">show setup qr</button>
  <img id="qrimg" alt="setup qr">
  <p class="hint" id="who">scan from the phone app: add connection &#8594; scan setup qr.</p>
</div>
<script>
let token = localStorage.getItem('wakepc_token') || '';
let enforced = false;
const $ = (id) => document.getElementById(id);
const api = (path, opts = {}) => {
  const headers = { ...(opts.headers || {}) };
  if (token) headers.Authorization = 'Bearer ' + token;
  return fetch(path, { ...opts, headers });
};

function saveTok() {
  token = $('tok').value.trim();
  localStorage.setItem('wakepc_token', token);
  init();
}

async function init() {
  // Try with no credential at all: on the tailnet the relay already knows
  // who we are, and only falls back to asking for a token if it does not.
  const res = await api('/commands');
  if (res.status === 401) {
    localStorage.removeItem('wakepc_token'); token = '';
    $('panel').style.display = 'none'; $('login').style.display = 'block';
    return;
  }
  const info = await res.json();
  enforced = info.confirm_enforced;
  $('login').style.display = 'none'; $('panel').style.display = 'block';
  if (info.you) $('who').textContent = 'recognised as ' + info.you + ' — no token needed here.';
  $('cmds').innerHTML = info.commands.map((c) => `
    <div class="row">
      <div class="name">${c.name}${c.elevated ? ' <span style="color:#5f6871">· code</span>' : ''}</div>
      <div class="status" id="st-${c.name}"></div>
      <button onclick="run('${c.name}', ${c.ping}, ${c.elevated})">run</button>
    </div>`).join('');
  info.commands.filter((c) => c.ping).forEach((c) => refresh(c.name));
}

async function refresh(name) {
  try {
    const s = await (await api('/status/' + name)).json();
    const el = $('st-' + name);
    el.className = 'status ' + (s.awake ? 'ok' : '');
    el.textContent = s.awake ? 'AWAKE' + (s.avg_ms ? ' · ' + s.avg_ms.toFixed(1) + 'MS' : '') : 'ASLEEP';
  } catch (e) { /* leave blank */ }
}

async function run(name, ping, elevated) {
  const el = $('st-' + name);
  const headers = {};
  if (elevated && enforced) {
    const code = prompt('confirmation code for ' + name);
    if (!code) return;
    headers['X-WakePC-Confirm'] = code.trim();
  }
  el.className = 'status busy'; el.textContent = 'RUNNING';
  const res = await api('/run/' + name, { method: 'POST', headers });
  if (res.status === 403) { el.className = 'status err'; el.textContent = 'WRONG CODE'; return; }
  if (!res.ok) { el.className = 'status err'; el.textContent = 'FAILED'; return; }
  if (!ping) { el.className = 'status ok'; el.textContent = 'OK'; setTimeout(() => { el.textContent = ''; }, 3000); return; }
  const started = Date.now();
  while (Date.now() - started < 90000) {
    await new Promise((r) => setTimeout(r, 3000));
    el.className = 'status busy';
    el.textContent = 'WAKING · ' + Math.round((Date.now() - started) / 1000) + 'S';
    const s = await (await api('/status/' + name)).json();
    if (s.awake) { el.className = 'status ok'; el.textContent = 'AWAKE'; return; }
  }
  el.className = 'status err'; el.textContent = 'NO REPLY YET';
}

async function showQr() {
  const res = await api('/qr.png');
  if (!res.ok) return;
  $('qrimg').src = URL.createObjectURL(await res.blob());
  $('qrimg').style.display = 'block';
}

init();
</script></div></body></html>
"""

WORDS = (
    "acorn apple arrow badger bamboo basil beach bear berry birch bison blaze bloom "
    "breeze brick brook bubble cabin candle canyon cedar cherry cliff cloud clover "
    "cobalt comet coral cotton crane creek cricket crystal daisy dawn delta denim "
    "drift eagle ember fable falcon fern flame flint forest fox frost galaxy garnet "
    "ginger glacier goose grape grove hazel heron honey horizon iceberg iris ivory "
    "jade jasper juniper kayak kelp koala lagoon lantern lemon lilac lily lotus "
    "lunar maple marble meadow mango melon mesa mint mist molten moss moth nectar "
    "nutmeg oasis ocean olive onyx opal orbit orchid otter owl panda peach pearl "
    "pebble penguin pepper pine planet plume pond poppy prairie prism quartz quill "
    "rain raven reef ridge river robin rocket rose rustic saffron sage salmon sand "
    "sapphire seal shadow shell sierra silver sleet slate snow solar sparrow spruce "
    "star stone storm summit sunset swan thistle thunder tiger timber topaz trout "
    "tulip tundra turtle valley velvet violet walnut wave willow winter wolf wren "
    "zephyr zebra"
).split()


def generate_passphrase(words: int) -> str:
    return "-".join(secrets.choice(WORDS) for _ in range(words))


def advertised_host(config):
    """The address to hand out — the tailnet one, which every peer can reach."""
    host = config["bind_host"]
    if host in ("", "auto", "0.0.0.0"):
        host = own_tailnet_addr() or socket.gethostname()
    return f"{host}:{config['port']}"


def print_setup_qr():
    """Print a QR the app can scan: a wakepc:// uri with the address."""
    config = load_config()
    if shutil.which("qrencode") is None:
        sys.exit("error: qrencode not installed (apt install qrencode)")
    fields = {"name": socket.gethostname().lower(), "host": advertised_host(config)}
    if config["token"]:
        fields["token"] = config["token"]
    subprocess.run(["qrencode", "-t", "ANSIUTF8", "wakepc://c?" + urllib.parse.urlencode(fields)])
    print("scan with the wakepc app: add connection -> scan setup qr")


def print_status():
    """What the app needs to reach this relay, and who it will let in."""
    config = load_config()
    print(f"address:  {advertised_host(config)}")
    if config["trust_tailnet"] and tailscale_bin():
        allowed = ", ".join(sorted(config["allow_users"])) or (own_login() or "this tailnet")
        print(f"identity: on — devices belonging to {allowed} need no credential")
    else:
        print("identity: off — callers must send the token")
    print(f"token:    {config['token'] or '(none — identity only)'}")
    print(f"confirm:  {'enforced here' if config['confirm_code'] else 'app-side only'}")
    gated = [n for n, c in config["commands"].items() if c.confirm]
    print(f"commands: {', '.join(config['commands'])}")
    if gated:
        print(f"          confirmation required for: {', '.join(gated)}")


def main():
    config = load_config()
    Handler.config = config
    host = config["bind_host"]
    if host in ("", "auto"):
        # Binding to the tailnet address means a LAN host cannot even open a
        # connection claiming to be a peer. Fall back only if there is no
        # tailnet address to bind to.
        host = own_tailnet_addr() or "0.0.0.0"
        if host == "0.0.0.0":
            print("warning: no tailnet address found — listening on all interfaces", flush=True)
    server = ThreadingHTTPServer((host, config["port"]), Handler)
    names = ", ".join(config["commands"])
    auth = "tailnet identity" if config["trust_tailnet"] and tailscale_bin() else "token"
    print(f"wakepc listening on {host}:{config['port']} ({auth}) — commands: {names}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    if len(sys.argv) == 1:
        main()
    elif sys.argv[1] == "genpass":
        count = int(sys.argv[2]) if len(sys.argv) > 2 else 4
        print(generate_passphrase(max(3, min(count, 8))))
    elif sys.argv[1] == "qr":
        print_setup_qr()
    elif sys.argv[1] == "status":
        print_status()
    else:
        sys.exit(
            f"unknown command: {sys.argv[1]}\n"
            "usage: wakepc.py            run the server (systemd does this)\n"
            "       wakepc.py status       show the address and how callers are let in\n"
            "       wakepc.py genpass [n]  generate an n-word passphrase (default 4)\n"
            "       wakepc.py qr           print the setup QR for the app"
        )
