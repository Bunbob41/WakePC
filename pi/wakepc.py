#!/usr/bin/env python3
"""Tiny Wake-on-LAN relay for the tailnet.

Listens on the Pi's Tailscale interface and exposes two endpoints:

    POST /wake    -> broadcast a WOL magic packet for the configured MAC
    GET  /status  -> ping the PC's LAN IP, reply {"awake": true|false}

Both require "Authorization: Bearer <token>". Stdlib only — no pip installs.
Configuration comes from /etc/wakepc.conf (see wakepc.conf.example).
"""

import configparser
import json
import socket
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONFIG_PATH = "/etc/wakepc.conf"


def load_config():
    parser = configparser.ConfigParser()
    if not parser.read(CONFIG_PATH):
        sys.exit(f"error: could not read {CONFIG_PATH}")
    cfg = parser["wakepc"]
    required = ["token", "mac", "pc_ip"]
    missing = [k for k in required if not cfg.get(k)]
    if missing:
        sys.exit(f"error: missing config keys: {', '.join(missing)}")
    if len(cfg["token"]) < 16:
        sys.exit("error: token must be at least 16 characters")
    return {
        "token": cfg["token"],
        "mac": cfg["mac"],
        "pc_ip": cfg["pc_ip"],
        "broadcast_ip": cfg.get("broadcast_ip", "255.255.255.255"),
        "bind_host": cfg.get("bind_host", "0.0.0.0"),
        "port": cfg.getint("port", 8787),
    }


def send_magic_packet(mac: str, broadcast_ip: str) -> None:
    clean = mac.replace(":", "").replace("-", "").lower()
    if len(clean) != 12:
        raise ValueError(f"bad MAC address: {mac}")
    payload = b"\xff" * 6 + bytes.fromhex(clean) * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        # Port 9 is the conventional discard port for WOL; send a few times
        # because it's fire-and-forget UDP.
        for _ in range(3):
            sock.sendto(payload, (broadcast_ip, 9))


def pc_is_awake(pc_ip: str) -> bool:
    result = subprocess.run(
        ["ping", "-c", "1", "-W", "1", pc_ip],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


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
        header = self.headers.get("Authorization", "")
        expected = f"Bearer {self.config['token']}"
        # hmac.compare_digest needs equal-length inputs to be meaningful;
        # a plain != on the full header leaks nothing useful over a tailnet.
        return header == expected

    def do_POST(self):
        if self.path != "/wake":
            return self._reply(404, {"error": "not found"})
        if not self._authorized():
            return self._reply(401, {"error": "unauthorized"})
        try:
            send_magic_packet(self.config["mac"], self.config["broadcast_ip"])
        except (ValueError, OSError) as exc:
            return self._reply(500, {"error": str(exc)})
        self._reply(200, {"sent": True})

    def do_GET(self):
        if self.path != "/status":
            return self._reply(404, {"error": "not found"})
        if not self._authorized():
            return self._reply(401, {"error": "unauthorized"})
        self._reply(200, {"awake": pc_is_awake(self.config["pc_ip"])})

    def log_message(self, fmt, *args):
        # journald picks up stdout via systemd.
        print(f"{self.address_string()} {fmt % args}", flush=True)


def main():
    config = load_config()
    Handler.config = config
    server = ThreadingHTTPServer((config["bind_host"], config["port"]), Handler)
    print(f"wakepc listening on {config['bind_host']}:{config['port']}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
