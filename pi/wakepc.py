#!/usr/bin/env python3
"""Named-command relay for the tailnet.

Commands are defined server-side in /etc/wakepc.conf — the phone can only
invoke them by name, never send shell. Endpoints (all bearer-token auth):

    GET  /commands       -> {"commands": [{"name": "wake-pc", "ping": true}]}
    POST /run/<name>     -> run that command
    GET  /status/<name>  -> {"awake": bool} for commands that define a ping ip

    POST /wake, GET /status: legacy 0.1 endpoints, mapped onto the first
    WOL / first ping-able command so old clients keep working.

Stdlib only — no pip installs. See wakepc.conf.example for the config format.
"""

import configparser
import json
import re
import secrets
import shutil
import socket
import subprocess
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONFIG_PATH = "/etc/wakepc.conf"
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")


class Command:
    def __init__(self, name, run, ping, broadcast_ip):
        self.name = name
        self.run = run  # ("wol", mac) or ("shell", cmdline)
        self.ping = ping
        self.broadcast_ip = broadcast_ip


def load_config():
    parser = configparser.ConfigParser()
    if not parser.read(CONFIG_PATH):
        sys.exit(f"error: could not read {CONFIG_PATH}")
    main = parser["wakepc"]
    token = main.get("token", "")
    if len(token) < 12:
        sys.exit("error: token must be at least 12 characters (try: wakepc.py genpass)")

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
        commands[name] = Command(
            name=name,
            run=run,
            ping=parser[section].get("ping", "").strip() or None,
            broadcast_ip=parser[section].get("broadcast_ip", "255.255.255.255"),
        )
    if not commands:
        sys.exit("error: no [command:*] sections defined")

    return {
        "token": token,
        "bind_host": main.get("bind_host", "0.0.0.0"),
        "port": main.getint("port", 8787),
        "commands": commands,
    }


def send_magic_packet(mac_hex: str, broadcast_ip: str) -> None:
    payload = b"\xff" * 6 + bytes.fromhex(mac_hex) * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for _ in range(3):
            sock.sendto(payload, (broadcast_ip, 9))


def probe_ping(ip: str, count: int) -> dict:
    """Ping `ip` `count` times and report awake + rtt stats (iputils output)."""
    count = max(1, min(count, 10))
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

    def _authorized(self) -> bool:
        return self.headers.get("Authorization", "") == f"Bearer {self.config['token']}"

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
            listing = [
                {"name": c.name, "ping": c.ping is not None}
                for c in self.config["commands"].values()
            ]
            return self._reply(200, {"commands": listing})
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
  <input id="tok" type="password" placeholder="from /etc/wakepc.conf">
  <button class="primary" onclick="saveTok()">unlock</button>
</div>
<div id="panel" style="display:none">
  <div class="label">COMMANDS</div>
  <div id="cmds"></div>
  <div class="label">SETUP QR</div>
  <button onclick="showQr()">show setup qr</button>
  <img id="qrimg" alt="setup qr">
  <p class="hint">scan from the phone app: add connection &#8594; scan setup qr.
     the qr contains the token &#8212; only show it to screens you trust.</p>
  <p class="hint"><a href="#" style="color:#5f6871" onclick="localStorage.removeItem('wakepc_token');location.reload();return false">forget token on this browser</a></p>
</div>
<script>
let token = localStorage.getItem('wakepc_token') || '';
const $ = (id) => document.getElementById(id);
const api = (path, opts = {}) =>
  fetch(path, { ...opts, headers: { Authorization: 'Bearer ' + token } });

function saveTok() {
  token = $('tok').value.trim();
  localStorage.setItem('wakepc_token', token);
  init();
}

async function init() {
  if (!token) { $('login').style.display = 'block'; return; }
  const res = await api('/commands');
  if (res.status === 401) {
    localStorage.removeItem('wakepc_token'); token = '';
    $('panel').style.display = 'none'; $('login').style.display = 'block';
    return;
  }
  const { commands } = await res.json();
  $('login').style.display = 'none'; $('panel').style.display = 'block';
  $('cmds').innerHTML = commands.map((c) => `
    <div class="row">
      <div class="name">${c.name}</div>
      <div class="status" id="st-${c.name}"></div>
      <button onclick="run('${c.name}', ${c.ping})">run</button>
    </div>`).join('');
  commands.filter((c) => c.ping).forEach((c) => refresh(c.name));
}

async function refresh(name) {
  try {
    const s = await (await api('/status/' + name)).json();
    const el = $('st-' + name);
    el.className = 'status ' + (s.awake ? 'ok' : '');
    el.textContent = s.awake ? 'AWAKE' + (s.avg_ms ? ' · ' + s.avg_ms.toFixed(1) + 'MS' : '') : 'ASLEEP';
  } catch (e) { /* leave blank */ }
}

async function run(name, ping) {
  const el = $('st-' + name);
  el.className = 'status busy'; el.textContent = 'RUNNING';
  const res = await api('/run/' + name, { method: 'POST' });
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


def print_setup_qr():
    """Print a QR the app can scan: address + token in a wakepc:// uri."""
    config = load_config()
    host = config["bind_host"]
    if host in ("", "0.0.0.0"):
        host = socket.gethostname()
        print(f"warning: bind_host is 0.0.0.0 — QR will use hostname '{host}'", file=sys.stderr)
    if shutil.which("qrencode") is None:
        sys.exit("error: qrencode not installed (apt install qrencode)")
    uri = "wakepc://c?" + urllib.parse.urlencode({
        "name": socket.gethostname().lower(),
        "host": f"{host}:{config['port']}",
        "token": config["token"],
    })
    subprocess.run(["qrencode", "-t", "ANSIUTF8", uri])
    print("scan with the wakepc app: add connection -> scan setup qr")


def main():
    config = load_config()
    Handler.config = config
    server = ThreadingHTTPServer((config["bind_host"], config["port"]), Handler)
    names = ", ".join(config["commands"])
    print(f"wakepc listening on {config['bind_host']}:{config['port']} — commands: {names}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    if len(sys.argv) == 1:
        main()
    elif sys.argv[1] == "genpass":
        count = int(sys.argv[2]) if len(sys.argv) > 2 else 4
        print(generate_passphrase(max(3, min(count, 8))))
    elif sys.argv[1] == "qr":
        print_setup_qr()
    else:
        sys.exit(
            f"unknown command: {sys.argv[1]}\n"
            "usage: wakepc.py            run the server (systemd does this)\n"
            "       wakepc.py genpass [n]  generate an n-word passphrase (default 4)\n"
            "       wakepc.py qr           print the setup QR for the app"
        )
