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

    def _authorized(self) -> bool:
        return self.headers.get("Authorization", "") == f"Bearer {self.config['token']}"

    def _command(self, name):
        return self.config["commands"].get(name)

    def do_GET(self):
        if not self._authorized():
            return self._reply(401, {"error": "unauthorized"})
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        try:
            count = int(query.get("count", ["1"])[0])
        except ValueError:
            count = 1
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
