#!/usr/bin/env python3
"""Named-command relay for the tailnet.

Commands are defined server-side in /etc/wakepc.conf: the phone can only
invoke them by name, never send shell.

Callers are identified by asking tailscaled who owns the source address
(`tailscale whois`), so a device you already trust on the tailnet needs no
credential at all. A bearer token remains as a fallback for hosts without
the tailscale CLI. Endpoints:

    GET  /commands       -> {"commands": [{"name": "wake-pc", "ping": true,
                                           "elevated": false}]}
    POST /run/<name>     -> run that command; commands marked `confirm` also
                            require the header X-WakePC-Confirm: <code>
    GET  /status/<name>  -> {"awake": bool} for commands that define a ping ip,
                            plus "ready": bool when they also name a ready port

Machines are wake targets the phone registers itself, so nobody has to edit
this host's config to get a first wake. They can only ever be woken: a magic
packet turns a machine on and does nothing else, which is why the phone may
add one while shell commands stay config-only.

    GET    /health                 -> is this relay well, and who are you to it
    GET    /scan                   -> devices on this host's LAN, to pick a PC from
    GET    /machines               -> the registered machines
    POST   /machines               -> add one: {"name", "mac", "ip"?, "ready"?};
                                      "ready": "auto" picks the port by looking
    PUT    /machines/<id>          -> change one
    DELETE /machines/<id>          -> forget one
    POST   /machines/<id>/wake     -> wake it; ?wait=90 holds the reply until it
                                      is usable (or the time is up)
    GET    /machines/<id>/status   -> ping stats plus "state": asleep|up|ready|awake

    Each machine also appears in /commands as wake-<id>, so older apps and the
    panel can press it without knowing about machines.

    POST /wake, GET /status: legacy 0.1 endpoints, mapped onto the first
    WOL / first ping-able command so old clients keep working.

Stdlib only: no pip installs. See wakepc.conf.example for the config format.
"""

import configparser
import datetime
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

# What /health reports, so an app can tell an old relay from a current one.
API_VERSION = 2
MAX_MACHINES = 32
MAX_BODY = 4096
MAX_WAIT_S = 180
# Ports worth waiting for after a wake, most telling first: remote desktop,
# Windows file sharing, SSH. Each opens well after the machine starts to ping.
READY_CANDIDATES = (3389, 445, 22)
WAIT_STEP_S = 2
# The MAC in wakepc.conf.example. A relay still carrying it wakes nothing.
PLACEHOLDER_MAC = "aabbccddeeff"

# The ranges Tailscale hands out. An address outside them was never a peer.
TAILNET_NETS = (
    ipaddress.ip_network("100.64.0.0/10"),
    ipaddress.ip_network("fd7a:115c:a1e0::/48"),
)

# Commands that interrupt a machine somebody may be using. Used only to pick
# a default for `confirm`: fail safe, so an unrecognised shell command is
# treated as disruptive and the config can relax it.
DISRUPTIVE_RE = re.compile(
    r"shut ?down|restart|reboot|sleep|suspend|hibernate|logoff|log ?out|poweroff",
    re.IGNORECASE,
)


class Command:
    def __init__(self, name, run, ping, broadcast_ip, confirm, ready=None):
        self.name = name
        self.run = run  # ("wol", mac) or ("shell", cmdline)
        self.ping = ping
        # A TCP port on the ping address that only answers once the machine is
        # usable, not just powered: ping comes back early in a boot.
        self.ready = ready
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
            if mac == PLACEHOLDER_MAC:
                # An unedited example would offer a button that wakes nothing.
                print(f"note: ignoring [{section}], its MAC is still the example one")
                continue
            run = ("wol", mac)
        elif raw.startswith("shell "):
            run = ("shell", raw[6:].strip())
        else:
            sys.exit(f"error: [{section}] run must start with 'WOL ' or 'shell '")
        # A WOL packet can only ever turn something on, so it is never
        # disruptive; anything else is, until the config says otherwise.
        default_confirm = run[0] == "shell" and bool(DISRUPTIVE_RE.search(raw))
        ping = parser[section].get("ping", "").strip() or None
        ready_raw = parser[section].get("ready", "").strip()
        ready = None
        if ready_raw:
            if not ready_raw.isdigit() or not 1 <= int(ready_raw) <= 65535:
                sys.exit(f"error: [{section}] ready must be a TCP port number, 1 to 65535")
            if ping is None:
                sys.exit(f"error: [{section}] ready needs a ping address to check the port on")
            ready = int(ready_raw)
        commands[name] = Command(
            name=name,
            run=run,
            ping=ping,
            broadcast_ip=parser[section].get("broadcast_ip", "255.255.255.255"),
            confirm=parser[section].getboolean("confirm", default_confirm),
            ready=ready,
        )
    if confirm_code and not confirm_code.isdigit():
        sys.exit("error: confirm_code must be digits")
    if any(c.confirm for c in commands.values()) and not confirm_code:
        # Normal for a wake-only setup: the app is then the only gate, and
        # with no code set there is no gate at all. Said once, not as a warning.
        print("note: no confirm_code set, so elevated commands are gated by the app only")

    return {
        "token": token,
        "trust_tailnet": trust_tailnet,
        "allow_users": allow_users,
        "confirm_code": confirm_code,
        "phone_setup": main.getboolean("phone_setup", True),
        "bind_host": main.get("bind_host", "auto"),
        "port": main.getint("port", 8787),
        "commands": commands,
    }


class BadRequest(ValueError):
    """The caller sent something we will not store; the message is for them."""


class StorageError(OSError):
    """The machine list could not be written."""


def default_state_path():
    """Where registered machines live. The service account must be able to
    write here, which it cannot do to its own config under /etc."""
    explicit = os.environ.get("WAKEPC_STATE")
    if explicit:
        return explicit
    if sys.platform == "win32":
        return os.path.join(os.path.dirname(os.path.abspath(CONFIG_PATH)), "machines.json")
    return "/var/lib/wakepc/machines.json"


def clean_mac(raw) -> str:
    """Bare lowercase hex, or BadRequest saying what is wrong with it."""
    if not isinstance(raw, str):
        raise BadRequest("mac must be text")
    mac = re.sub(r"[:\-. ]", "", raw).lower()
    if not re.fullmatch(r"[0-9a-f]{12}", mac):
        raise BadRequest("mac must be 12 hex digits, like AA:BB:CC:DD:EE:FF")
    if mac == PLACEHOLDER_MAC:
        raise BadRequest("that is the example MAC, not a real machine's")
    if mac == "0" * 12 or int(mac[:2], 16) & 1:
        raise BadRequest("that MAC is a broadcast or group address, not a machine")
    return mac


def format_mac(mac_hex: str) -> str:
    return ":".join(mac_hex[i:i + 2] for i in range(0, 12, 2))


def _lan_like(addr) -> bool:
    """A home-network address: private IPv4, and not ours on the tailnet."""
    return (
        addr.version == 4
        and addr.is_private
        and not addr.is_loopback
        and not addr.is_unspecified
        and not addr.is_link_local
    )


def clean_lan_ip(raw, what: str) -> str:
    """The relay pings and port-checks this address for whoever asks, so it
    must be a home-network one: otherwise a machine entry would be a way to
    probe arbitrary hosts from here."""
    try:
        addr = ipaddress.ip_address(str(raw).strip())
    except ValueError:
        raise BadRequest(f"{what} must be an IPv4 address") from None
    if not _lan_like(addr):
        raise BadRequest(f"{what} must be an address on the home network")
    return str(addr)


def clean_name(raw) -> str:
    if not isinstance(raw, str):
        raise BadRequest("name must be text")
    name = " ".join("".join(ch for ch in raw if ch.isprintable()).split())
    if not 1 <= len(name) <= 40:
        raise BadRequest("name must be 1 to 40 characters")
    return name


def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:40].strip("-") or "pc"


def validate_machine(body: dict, base=None) -> dict:
    """The stored fields of a machine, from a request body. `base` is the
    machine being changed, whose fields stand in for any the body leaves out."""
    merged = dict(base or {})
    merged.update({k: v for k, v in body.items() if k in ("name", "mac", "ip", "ready", "broadcast_ip")})
    if "name" not in merged or "mac" not in merged:
        raise BadRequest("a machine needs a name and a mac")
    ip = merged.get("ip") or None
    if ip is not None:
        ip = clean_lan_ip(ip, "ip")
    ready = merged.get("ready")
    if ready in ("", None):
        ready = None
    else:
        if isinstance(ready, bool) or not str(ready).isdigit() or not 1 <= int(ready) <= 65535:
            raise BadRequest("ready must be a TCP port number, 1 to 65535")
        if ip is None:
            raise BadRequest("ready needs an ip to check the port on")
        ready = int(ready)
    broadcast = merged.get("broadcast_ip") or None
    if broadcast is None or (base and "ip" in body and "broadcast_ip" not in body):
        # Not given, or the machine just moved: work it out from our own LAN.
        broadcast = broadcast_for(ip)
    else:
        try:
            broadcast = str(ipaddress.IPv4Address(str(broadcast).strip()))
        except ValueError:
            raise BadRequest("broadcast_ip must be an IPv4 address") from None
    return {
        "name": clean_name(merged["name"]),
        "mac": clean_mac(merged["mac"]),
        "ip": ip,
        "ready": ready,
        "broadcast_ip": broadcast,
    }


class MachineStore:
    """Wake targets registered from the phone, kept in one small JSON file.

    `reserved` holds the config's own command names: a machine shows up in
    /commands as wake-<id>, and must never shadow a command defined there.
    A path of None keeps everything in memory, which is what tests want.
    """

    def __init__(self, path, reserved=()):
        self.path = path
        self._reserved = set(reserved)
        self._lock = threading.Lock()
        self._machines = self._load()

    def _load(self) -> dict:
        if self.path is None:
            return {}
        try:
            with open(self.path, encoding="utf-8") as handle:
                raw = json.load(handle)
        except FileNotFoundError:
            return {}
        except (OSError, ValueError) as exc:
            print(f"warning: could not read {self.path} ({exc}), starting with no machines")
            return {}
        machines = {}
        for item in raw.get("machines", []) if isinstance(raw, dict) else []:
            try:
                ident = item["id"]
                if not NAME_RE.match(ident) or not NAME_RE.match(f"wake-{ident}"):
                    continue
                machines[ident] = {"id": ident, **validate_machine(item)}
            except (BadRequest, KeyError, TypeError):
                continue  # one bad entry should not take the rest down
        return machines

    def _save(self) -> None:
        if self.path is None:
            return
        try:
            os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as handle:
                json.dump({"machines": list(self._machines.values())}, handle, indent=2)
                handle.write("\n")
            os.replace(tmp, self.path)  # atomic: never a half-written list
        except OSError as exc:
            raise StorageError(f"this relay cannot save machines to {self.path}: {exc}") from exc

    def writable(self) -> bool:
        if self.path is None:
            return True
        folder = os.path.dirname(self.path) or "."
        while not os.path.exists(folder):
            parent = os.path.dirname(folder)
            if parent == folder:
                return False
            folder = parent
        return os.access(folder, os.W_OK)

    def all(self) -> list:
        with self._lock:
            return [dict(m) for m in self._machines.values()]

    def get(self, ident):
        with self._lock:
            found = self._machines.get(ident)
            return dict(found) if found else None

    def _commit(self, before) -> None:
        try:
            self._save()
        except StorageError:
            self._machines = before  # memory must not claim what disk lacks
            raise

    def add(self, fields: dict) -> dict:
        with self._lock:
            if len(self._machines) >= MAX_MACHINES:
                raise BadRequest(f"this relay already holds {MAX_MACHINES} machines")
            for other in self._machines.values():
                if other["mac"] == fields["mac"]:
                    raise BadRequest(f"that machine is already here as {other['name']}")
            base = slug(fields["name"])
            ident, n = base, 1
            while ident in self._machines or f"wake-{ident}" in self._reserved:
                n += 1
                ident = f"{base[:36]}-{n}"
            before = dict(self._machines)
            self._machines[ident] = {"id": ident, **fields}
            self._commit(before)
            return dict(self._machines[ident])

    def update(self, ident, fields: dict) -> dict:
        with self._lock:
            for other in self._machines.values():
                if other["id"] != ident and other["mac"] == fields["mac"]:
                    raise BadRequest(f"that machine is already here as {other['name']}")
            before = dict(self._machines)
            self._machines[ident] = {"id": ident, **fields}
            self._commit(before)
            return dict(self._machines[ident])

    def remove(self, ident) -> bool:
        with self._lock:
            if ident not in self._machines:
                return False
            before = dict(self._machines)
            del self._machines[ident]
            self._commit(before)
            return True


def pick_ready_port(ip):
    """The first candidate port the machine answers on right now, or None.

    A machine is added while it is switched on, so this is the moment to see
    what it offers. Guessing instead (445 on a PC with file sharing off) would
    leave every wake stuck at UP, waiting for a port that never opens.
    """
    if not ip:
        return None
    for port in READY_CANDIDATES:
        if probe_port(ip, port, timeout=0.6):
            return port
    return None


def resolve_auto_ready(body: dict) -> dict:
    """Swap `"ready": "auto"` for a port chosen by looking at the machine."""
    if body.get("ready") != "auto":
        return body
    ip = body.get("ip") or None
    if ip is not None:
        ip = clean_lan_ip(ip, "ip")  # never probe an address we would not store
    return {**body, "ready": pick_ready_port(ip)}


def machine_command(machine: dict) -> Command:
    """A machine as the command older clients know how to press."""
    return Command(
        name=f"wake-{machine['id']}",
        run=("wol", machine["mac"]),
        ping=machine["ip"],
        broadcast_ip=machine["broadcast_ip"],
        confirm=False,  # a magic packet can only ever turn something on
        ready=machine["ready"],
    )


def machine_json(machine: dict) -> dict:
    return {**machine, "mac": format_mac(machine["mac"])}


def all_commands(config, store) -> dict:
    """Config commands plus one wake command per machine; the config wins."""
    merged = {}
    for machine in store.all():
        cmd = machine_command(machine)
        merged[cmd.name] = cmd
    merged.update(config["commands"])
    return merged


# Interfaces that are not the home LAN: the tailnet itself, containers, VMs.
SKIP_IFACES = ("tailscale", "docker", "veth", "br-", "virbr", "wg", "tun", "zt")
SWEEP_LIMIT = 1024
SCAN_TTL = 15
NETWORKS_TTL = 60

_net_lock = threading.Lock()
_net_cache = (0.0, [])


def _outbound_ip():
    """The address this host would send from. No packet leaves: connecting a
    UDP socket only asks the kernel which interface it would use."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("192.0.2.1", 9))
            return sock.getsockname()[0]
    except OSError:
        return None


def parse_ip_addr(text: str) -> list:
    """Home-LAN interfaces out of `ip -j -4 addr show`."""
    try:
        links = json.loads(text)
    except ValueError:
        return []
    found = []
    for link in links if isinstance(links, list) else []:
        name = link.get("ifname", "")
        if name == "lo" or name.startswith(SKIP_IFACES):
            continue
        for info in link.get("addr_info", []):
            try:
                iface = ipaddress.ip_interface(f"{info['local']}/{info['prefixlen']}")
            except (KeyError, ValueError):
                continue
            if _lan_like(iface.ip):
                found.append(iface)
    return found


def local_networks() -> list:
    """The IPv4 networks this host sits on directly, as ip_interface objects."""
    global _net_cache
    now = time.time()
    with _net_lock:
        if now - _net_cache[0] < NETWORKS_TTL:
            return _net_cache[1]
    found = []
    if sys.platform.startswith("linux") and shutil.which("ip"):
        try:
            done = subprocess.run(["ip", "-j", "-4", "addr", "show"],
                                  capture_output=True, text=True, timeout=5)
            found = parse_ip_addr(done.stdout)
        except (OSError, subprocess.TimeoutExpired):
            found = []
    if not found:
        # No prefix length to be had cheaply (Windows, macOS): nearly every
        # home network is a /24, and a wrong guess only narrows the scan.
        ip = _outbound_ip()
        if ip and _lan_like(ipaddress.ip_address(ip)):
            found = [ipaddress.ip_interface(f"{ip}/24")]
    with _net_lock:
        _net_cache = (now, found)
    return found


def broadcast_for(ip) -> str:
    """The broadcast address of whichever of our networks holds `ip`."""
    networks = local_networks()
    if ip:
        addr = ipaddress.ip_address(ip)
        for iface in networks:
            if addr in iface.network:
                return str(iface.network.broadcast_address)
    if len(networks) == 1:
        return str(networks[0].network.broadcast_address)
    return "255.255.255.255"


def sweep_targets(iface) -> list:
    """Every other address on the interface's network, capped so a huge
    subnet is not swept whole: then just the /24 around our own address."""
    net = iface.network
    if net.num_addresses > SWEEP_LIMIT:
        net = ipaddress.ip_network(f"{iface.ip}/24", strict=False)
    return [str(host) for host in net.hosts() if host != iface.ip]


def nudge_arp(addresses) -> None:
    """Make the kernel resolve each address, so the neighbour table fills.
    An empty datagram to the discard port needs no privilege, unlike ARP or
    raw ICMP, and a machine that is off simply never answers."""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    except OSError:
        return
    with sock:
        sock.setblocking(False)
        for address in addresses:
            try:
                sock.sendto(b"", (address, 9))
            except OSError:
                continue


_NEIGH_RE = re.compile(
    r"\(?(\d{1,3}(?:\.\d{1,3}){3})\)?\s.*?"
    r"(?<![0-9A-Fa-f:-])([0-9A-Fa-f]{1,2}(?:[:-][0-9A-Fa-f]{1,2}){5})(?![0-9A-Fa-f:-])"
)


def parse_neighbours(text: str) -> list:
    """(ip, mac hex) pairs from `ip neigh`, or `arp -a` in the Linux, Windows
    or macOS dialect. Entries that never resolved carry no MAC and drop out."""
    pairs = []
    for line in text.splitlines():
        if "FAILED" in line or "INCOMPLETE" in line or "incomplete" in line:
            continue
        match = _NEIGH_RE.search(line)
        if not match:
            continue
        # macOS prints 0:1c:42 for 00:1c:42.
        mac = "".join(part.zfill(2) for part in re.split(r"[:-]", match.group(2))).lower()
        if mac == "0" * 12 or int(mac[:2], 16) & 1:
            continue  # unresolved, broadcast or multicast: not a machine
        pairs.append((match.group(1), mac))
    return pairs


def read_neighbours() -> list:
    if sys.platform.startswith("linux") and shutil.which("ip"):
        args = ["ip", "-4", "neigh", "show"]
    else:
        args = ["arp", "-a"]
    try:
        done = subprocess.run(args, capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return []
    return parse_neighbours(done.stdout)


def resolve_names(addresses, deadline: float = 2.0) -> dict:
    """Reverse-DNS names where the router knows them. Lookups have no timeout
    of their own, so they run on threads and whatever has not come back by
    the deadline simply goes unnamed."""
    names = {}

    def lookup(address):
        try:
            names[address] = socket.gethostbyaddr(address)[0].split(".")[0]
        except OSError:
            pass

    threads = [threading.Thread(target=lookup, args=(a,), daemon=True) for a in addresses]
    for thread in threads:
        thread.start()
    end = time.monotonic() + deadline
    for thread in threads:
        thread.join(max(0.0, end - time.monotonic()))
    return dict(names)


_scan_lock = threading.Lock()
_scan_cache = (0.0, None)


def scan_lan(store, settle: float = 1.5) -> dict:
    """Who is on this host's LAN right now: what the phone picks a PC from.
    Only machines that are switched on can answer, so setup says to scan
    with the PC running."""
    global _scan_cache
    with _scan_lock:  # one sweep at a time; a second caller gets the first's result
        if _scan_cache[1] is None or time.time() - _scan_cache[0] >= SCAN_TTL:
            networks = local_networks()
            for iface in networks:
                nudge_arp(sweep_targets(iface))
            if networks:
                time.sleep(settle)
            own = {str(iface.ip) for iface in networks}
            seen = {}
            for ip, mac in read_neighbours():
                addr = ipaddress.ip_address(ip)
                if ip in own or not any(addr in iface.network for iface in networks):
                    continue
                seen[ip] = mac
            names = resolve_names(list(seen))
            devices = [
                {"ip": ip, "mac": mac, "name": names.get(ip)}
                for ip, mac in sorted(seen.items(), key=lambda kv: ipaddress.ip_address(kv[0]))
            ]
            _scan_cache = (time.time(), {
                "devices": devices,
                "networks": [str(iface.network) for iface in networks],
            })
        result = _scan_cache[1]
    known = {m["mac"]: m["id"] for m in store.all()}
    return {
        "networks": result["networks"],
        "devices": [
            {"ip": d["ip"], "mac": format_mac(d["mac"]), "name": d["name"],
             "machine": known.get(d["mac"])}
            for d in result["devices"]
        ],
    }


def state_word(status: dict) -> str:
    """One word for a /status body, matching what the app shows."""
    if not status.get("awake"):
        return "asleep"
    if "ready" not in status:
        return "awake"
    return "ready" if status["ready"] else "up"


def wake_and_wait(cmd: Command, wait: int) -> dict:
    """Send the packet, and with `wait` hold on until the machine is usable.
    A caller that is not a phone (an assistant, a script) wants one request
    that ends in an answer, not a polling loop of its own."""
    send_magic_packet(cmd.run[1], cmd.broadcast_ip)
    body = {"sent": True}
    if wait <= 0 or cmd.ping is None:
        return body
    started = time.monotonic()
    while True:
        status = probe_status(cmd, 1)
        state = state_word(status)
        waited = time.monotonic() - started
        if state in ("ready", "awake") or waited >= wait:
            break
        time.sleep(WAIT_STEP_S)
    # The ping's own "sent" is a packet count; ours says the wake went out.
    body.update({k: v for k, v in status.items() if k != "sent"})
    body.update(state=state, waited_s=round(waited))
    return body


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
    """This host's own tailnet address, for binding and for `status`."""
    out = _tailscale("ip", "-4")
    if not out:
        return None
    first = out.decode(errors="replace").strip().splitlines()
    return first[0].strip() if first else None


def own_login():
    """The tailnet account this node belongs to: the default allowed user."""
    addr = own_tailnet_addr()
    identity = whois_peer(addr) if addr else None
    return identity[0] if identity else None


_expiry_cache = (0.0, None)
EXPIRY_TTL = 600
EXPIRY_WARN_DAYS = 14


def parse_key_expiry(raw, now=None):
    """Days until this node's key expires, from `tailscale status --json`.
    None when expiry is switched off for the node, which is what a relay wants."""
    try:
        stamp = (json.loads(raw).get("Self") or {}).get("KeyExpiry")
        # Seconds are precision enough, and older Pythons cannot parse the
        # nanoseconds or the trailing Z that tailscale prints.
        expiry = datetime.datetime.strptime(stamp[:19], "%Y-%m-%dT%H:%M:%S")
    except (ValueError, TypeError, AttributeError):
        return None
    now = now or datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
    return (expiry - now).days


def key_expiry_days():
    """A relay whose key lapses drops off the tailnet without a word, months
    after anyone last thought about it. Worth saying before it happens."""
    global _expiry_cache
    now = time.time()
    if now - _expiry_cache[0] < EXPIRY_TTL:
        return _expiry_cache[1]
    raw = _tailscale("status", "--json", "--peers=false")
    days = parse_key_expiry(raw) if raw else None
    _expiry_cache = (now, days)
    return days


def health(config, store, bound, identity) -> dict:
    """Is this relay well, in terms an app can show without interpreting."""
    warnings = []
    if tailscale_bin() is None:
        warnings.append("this relay has no tailscale CLI, so callers need the token")
    elif bound == "0.0.0.0":
        warnings.append("no Tailscale address found, so the relay is listening on every interface")
    if not store.writable():
        warnings.append(f"machines cannot be saved: {store.path} is not writable")
    days = key_expiry_days()
    if days is not None and days <= EXPIRY_WARN_DAYS:
        when = "has expired" if days < 0 else f"expires in {days} days"
        warnings.append(
            f"this relay's Tailscale key {when}: turn off key expiry for it in the Tailscale admin console"
        )
    return {
        "ok": True,
        "api": API_VERSION,
        "name": socket.gethostname(),
        "platform": sys.platform,
        "you": identity[1] if identity else None,
        "machines": len(store.all()),
        "commands": len(config["commands"]),
        "phone_setup": config["phone_setup"],
        "warnings": warnings,
    }


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


def probe_port(ip: str, port: int, timeout: float = 1.0) -> bool:
    """True if something accepts a TCP connection on ip:port within `timeout`."""
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except OSError:
        return False


def probe_status(cmd: Command, count: int) -> dict:
    """Ping stats, plus whether the ready port answers when the command has one.
    An asleep machine is never ready, and its port is not tried, which would
    only add a timeout to every poll of a sleeping machine."""
    body = probe_ping(cmd.ping, count)
    if cmd.ready is not None:
        body["ready"] = body["awake"] and probe_port(cmd.ping, cmd.ready)
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
    store = MachineStore(None)  # replaced in main() with the one on disk
    bound = None  # the address we are listening on, for /health

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
            print(f"refused {identity[0]} ({identity[1]}): not in allow_users", flush=True)
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
        return all_commands(self.config, self.store).get(name)

    def _body(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise BadRequest("bad Content-Length") from None
        if length > MAX_BODY:
            raise BadRequest("request body too large")
        if length <= 0:
            return {}
        try:
            parsed = json.loads(self.rfile.read(length))
        except ValueError:
            raise BadRequest("the body must be JSON") from None
        if not isinstance(parsed, dict):
            raise BadRequest("the body must be a JSON object")
        return parsed

    def _machine_route(self, path: str):
        """(machine or None, trailing action) for /machines/<id>[/<action>]."""
        parts = path.split("/")[2:]
        machine = self.store.get(parts[0]) if parts and parts[0] else None
        return machine, "/".join(parts[1:])

    def _change_machines(self, change) -> None:
        """Run a change to the machine list, turning its failures into replies.
        Registering a wake target is the one thing a phone may set up here."""
        if not self._authorized():
            return self._reply(401, {"error": "unauthorized"})
        if not self.config["phone_setup"]:
            return self._reply(403, {"error": "phone setup is turned off on this relay"})
        try:
            code, body = change()
        except BadRequest as exc:
            return self._reply(400, {"error": str(exc)})
        except StorageError as exc:
            print(str(exc), flush=True)
            return self._reply(500, {"error": str(exc)})
        self._reply(code, body)

    def do_GET(self):
        # The panel page itself is public (it holds no secrets); every API
        # call it makes still needs the caller to be recognised.
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
        if parsed.path == "/health":
            return self._reply(200, health(self.config, self.store, self.bound, self._peer()))
        if parsed.path == "/scan":
            return self._reply(200, scan_lan(self.store))
        if parsed.path == "/machines":
            return self._reply(200, {"machines": [machine_json(m) for m in self.store.all()]})
        if parsed.path.startswith("/machines/"):
            machine, action = self._machine_route(parsed.path)
            if machine is None or action != "status":
                return self._reply(404, {"error": "unknown machine" if machine is None else "not found"})
            if machine["ip"] is None:
                return self._reply(404, {"error": "this machine has no address to check"})
            status = probe_status(machine_command(machine), count)
            return self._reply(200, {**status, "state": state_word(status)})
        if parsed.path == "/commands":
            # `elevated` is decided here, not guessed by the client: this is
            # the only place that knows a command actually runs `shutdown`.
            listing = [
                {"name": c.name, "ping": c.ping is not None, "elevated": c.confirm}
                for c in all_commands(self.config, self.store).values()
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
            return self._reply(200, probe_status(cmd, count))
        if parsed.path == "/status":  # legacy 0.1
            cmd = next((c for c in all_commands(self.config, self.store).values() if c.ping), None)
            if cmd is None:
                return self._reply(404, {"error": "no ping-able command"})
            return self._reply(200, probe_ping(cmd.ping, 1))
        self._reply(404, {"error": "not found"})

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/machines":
            return self._change_machines(
                lambda: (201, machine_json(
                    self.store.add(validate_machine(resolve_auto_ready(self._body())))
                ))
            )
        if not self._authorized():
            return self._reply(401, {"error": "unauthorized"})
        if parsed.path.startswith("/machines/"):
            machine, action = self._machine_route(parsed.path)
            if machine is None or action != "wake":
                return self._reply(404, {"error": "unknown machine" if machine is None else "not found"})
            try:
                wait = int(urllib.parse.parse_qs(parsed.query).get("wait", ["0"])[0])
            except ValueError:
                wait = 0
            try:
                return self._reply(
                    200, wake_and_wait(machine_command(machine), max(0, min(wait, MAX_WAIT_S)))
                )
            except OSError as exc:
                return self._reply(500, {"error": f"could not send the wake packet: {exc}"})
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
            wol = (c for c in all_commands(self.config, self.store).values() if c.run[0] == "wol")
            cmd = next(wol, None)
            if cmd is None:
                return self._reply(404, {"error": "no WOL command"})
            send_magic_packet(cmd.run[1], cmd.broadcast_ip)
            return self._reply(200, {"sent": True})
        self._reply(404, {"error": "not found"})

    def do_PUT(self):
        machine, action = self._machine_route(urllib.parse.urlparse(self.path).path)
        if not self.path.startswith("/machines/") or action:
            return self._reply(404, {"error": "not found"})

        def change():
            if machine is None:
                return 404, {"error": "unknown machine"}
            body = self._body()
            if body.get("ready") == "auto":
                body = resolve_auto_ready({"ip": machine["ip"], **body})
            fields = validate_machine(body, base=machine)
            return 200, machine_json(self.store.update(machine["id"], fields))

        self._change_machines(change)

    def do_DELETE(self):
        machine, action = self._machine_route(urllib.parse.urlparse(self.path).path)
        if not self.path.startswith("/machines/") or action:
            return self._reply(404, {"error": "not found"})

        def change():
            if machine is None or not self.store.remove(machine["id"]):
                return 404, {"error": "unknown machine"}
            return 200, {"ok": True}

        self._change_machines(change)

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
  .label { font-size:10px; letter-spacing:2px; color:#8d969f; margin:26px 0 10px 0; }
  .row { display:flex; align-items:center; gap:12px; background:#101315; border:1px solid #1e242a;
         border-radius:8px; padding:14px; margin-bottom:10px; }
  .name { font-size:14px; flex-grow:1; }
  .status { font-size:10px; letter-spacing:1.5px; color:#8d969f; }
  .status.ok { color:#45d06d; text-shadow:0 0 10px rgba(69,208,109,.7); }
  .status.busy { color:#e2a63d; text-shadow:0 0 8px rgba(226,166,61,.7); }
  .status.err { color:#ff5d49; }
  button { background:none; border:1px solid #242b31; border-radius:5px; color:#aab2b9;
           font:inherit; font-size:12px; padding:9px 16px; cursor:pointer; }
  button:hover { border-color:#7c858e; }
  button.primary { background:#e6e3dc; color:#0b0d0f; border:none; font-weight:700;
                   letter-spacing:1px; width:100%; padding:14px; border-radius:8px; }
  input { background:#101315; border:1px solid #1e242a; border-radius:6px; color:#e6e3dc;
          font:inherit; font-size:14px; padding:12px 14px; width:100%; box-sizing:border-box; margin-bottom:12px; }
  .hint { font-size:11px; color:#7c858e; line-height:1.7; }
</style></head><body><div class="wrap">
<h1><span>&#9211;</span> WAKEPC <span style="color:#8d969f">PANEL</span></h1>
<div id="login" style="display:none">
  <div class="label">TOKEN</div>
  <input id="tok" type="text" autocomplete="off" placeholder="pin or passphrase from /etc/wakepc.conf">
  <button class="primary" onclick="saveTok()">unlock</button>
</div>
<div id="panel" style="display:none">
  <div class="label">COMMANDS</div>
  <div id="cmds"></div>
  <p class="hint" id="who">add this relay in the phone app with its tailscale name or address.</p>
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
  if (info.you) $('who').textContent = 'recognised as ' + info.you + ', no token needed here.';
  $('cmds').innerHTML = info.commands.map((c) => `
    <div class="row">
      <div class="name">${c.name}${c.elevated ? ' <span style="color:#8d969f">· code</span>' : ''}</div>
      <div class="status" id="st-${c.name}"></div>
      <button onclick="run('${c.name}', ${c.ping}, ${c.elevated})">run</button>
    </div>`).join('');
  info.commands.filter((c) => c.ping).forEach((c) => refresh(c.name));
}

// READY: the ready port answers. UP: it pings, but the port does not answer
// yet. AWAKE: it pings, and the relay has no port to check.
function label(s) {
  if (!s.awake) return 'ASLEEP';
  if (s.ready === undefined) return 'AWAKE';
  return s.ready ? 'READY' : 'UP';
}

async function refresh(name) {
  try {
    const s = await (await api('/status/' + name)).json();
    const el = $('st-' + name);
    const word = label(s);
    el.className = 'status ' + (word === 'UP' ? 'busy' : s.awake ? 'ok' : '');
    el.textContent = word + (s.awake && s.avg_ms ? ' · ' + s.avg_ms.toFixed(1) + 'MS' : '');
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
    const s = await (await api('/status/' + name)).json();
    const word = label(s);
    if (word === 'READY' || word === 'AWAKE') { el.className = 'status ok'; el.textContent = word; return; }
    el.className = 'status busy';
    el.textContent = (word === 'UP' ? 'UP' : 'WAKING') + ' · ' + Math.round((Date.now() - started) / 1000) + 'S';
  }
  el.className = 'status err';
  el.textContent = el.textContent.startsWith('UP') ? 'UP, NOT READY YET' : 'NO REPLY YET';
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
    """The address to hand out: the tailnet one, which every peer can reach."""
    host = config["bind_host"]
    if host in ("", "auto", "0.0.0.0"):
        host = own_tailnet_addr() or socket.gethostname()
    return f"{host}:{config['port']}"


def print_status():
    """What the app needs to reach this relay, and who it will let in."""
    config = load_config()
    print(f"address:  {advertised_host(config)}")
    if config["trust_tailnet"] and tailscale_bin():
        allowed = ", ".join(sorted(config["allow_users"])) or (own_login() or "this tailnet")
        print(f"identity: on, devices belonging to {allowed} need no credential")
    else:
        print("identity: off, callers must send the token")
    print(f"token:    {config['token'] or '(none, identity only)'}")
    print(f"confirm:  {'enforced here' if config['confirm_code'] else 'app-side only'}")
    store = MachineStore(default_state_path(), reserved=config["commands"])
    machines = store.all()
    listed = ", ".join(f"{m['name']} ({format_mac(m['mac'])})" for m in machines)
    print(f"machines: {listed or 'none yet, add one from the app'}")
    if not config["phone_setup"]:
        print("          phone setup is off: the app cannot add or change them")
    elif not store.writable():
        print(f"          warning: {store.path} is not writable, so the app cannot add any")
    gated = [n for n, c in config["commands"].items() if c.confirm]
    print(f"commands: {', '.join(config['commands']) or '(none)'}")
    if gated:
        print(f"          confirmation required for: {', '.join(gated)}")
    checked = [f"{n} (port {c.ready})" for n, c in config["commands"].items() if c.ready]
    if checked:
        print(f"          ready when the port answers: {', '.join(checked)}")


BIND_RETRY_S = 5
REBIND_CHECK_S = 30


def wanted_bind(config, current=None) -> str:
    """The address to listen on. With `auto` that is our tailnet address, so
    a LAN host cannot even open a connection claiming to be a peer.

    While tailscaled is briefly away there is no address to read: keep the
    one we have rather than flapping. Listening on every interface is only
    for a host that has never had a tailnet address at all.
    """
    host = config["bind_host"]
    if host not in ("", "auto"):
        return host
    return own_tailnet_addr() or current or "0.0.0.0"


def watch_bind(config, server, host) -> None:
    """Stop the server when the address it should be on has changed.

    The address was once read only at startup. If tailscaled came up late,
    restarted or re-registered, the relay went on listening where nothing
    arrives, and looked dead from the phone until someone rebooted the host.
    """
    while True:
        time.sleep(REBIND_CHECK_S)
        want = wanted_bind(config, host)
        if want != host:
            print(f"listen address changed from {host} to {want}, rebinding", flush=True)
            server.shutdown()
            return


def serve(config) -> None:
    auto = config["bind_host"] in ("", "auto")
    names = ", ".join(all_commands(config, Handler.store)) or "none yet"
    host = None
    while True:
        host = wanted_bind(config, host)
        try:
            server = ThreadingHTTPServer((host, config["port"]), Handler)
        except OSError as exc:
            # Usually the tailnet address is not on the interface yet at boot.
            print(f"cannot listen on {host}:{config['port']} ({exc}), retrying", flush=True)
            host = None
            time.sleep(BIND_RETRY_S)
            continue
        Handler.bound = host
        if host == "0.0.0.0" and auto:
            print("warning: no tailnet address found, listening on all interfaces", flush=True)
        auth = "tailnet identity" if config["trust_tailnet"] and tailscale_bin() else "token"
        print(f"wakepc listening on {host}:{config['port']} ({auth}), commands: {names}", flush=True)
        if auto:
            threading.Thread(target=watch_bind, args=(config, server, host), daemon=True).start()
        try:
            server.serve_forever()
        finally:
            server.server_close()
        if not auto:
            return


def main():
    config = load_config()
    Handler.config = config
    Handler.store = MachineStore(default_state_path(), reserved=config["commands"])
    if not Handler.store.writable():
        print(f"warning: {Handler.store.path} is not writable, the app cannot add machines", flush=True)
    serve(config)


if __name__ == "__main__":
    if len(sys.argv) == 1:
        main()
    elif sys.argv[1] == "genpass":
        count = int(sys.argv[2]) if len(sys.argv) > 2 else 4
        print(generate_passphrase(max(3, min(count, 8))))
    elif sys.argv[1] == "status":
        print_status()
    else:
        sys.exit(
            f"unknown command: {sys.argv[1]}\n"
            "usage: wakepc.py            run the server (systemd does this)\n"
            "       wakepc.py status       show the address and how callers are let in\n"
            "       wakepc.py genpass [n]  generate an n-word passphrase (default 4)"
        )
