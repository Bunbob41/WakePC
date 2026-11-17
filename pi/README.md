# wakepc: the home-side service

The Android app is only half of WakePC. This service is the other half: it runs
on a machine that stays on at home (a Pi, a NAS, anything with systemd and
python3) and does the things the phone can't reach, like broadcasting a
Wake-on-LAN packet onto your LAN.

It's stdlib-only Python: nothing to `pip install`.

## Install

On the machine that will run it:

```bash
git clone https://github.com/Bunbob41/WakePC.git
cd WakePC/pi
sudo ./install.sh
```

That creates an unprivileged `wakepc` user, installs the service to
`/opt/wakepc`, writes `/etc/wakepc.conf`, binds to your Tailscale address, and
enables it at boot. It prints the address to type into the app: there is no
token to copy. Re-running it later updates the code and **never overwrites your
config**.

No git on the box? Copy `wakepc.py`, `wakepc.service`, `wakepc.conf.example`
and `install.sh` across with `scp` and run `sudo ./install.sh` in that folder.

## Add your PC

There is nothing to edit for a first wake. The relay can look around its own
network (`GET /scan`), and the app registers the machine you pick
(`POST /machines`). A fresh install starts with no machines and no commands.

Machines added this way are kept in `/var/lib/wakepc/machines.json`. They can
only be woken: a magic packet turns a machine on and does nothing else, which
is why a phone is allowed to add one. Set `phone_setup = no` in
`/etc/wakepc.conf` if you would rather this file be the only place anything
is defined.

Until the app's new setup screen ships, you can add a machine by hand from any
device on your tailnet:

```bash
curl http://<address>:8787/scan
curl -X POST http://<address>:8787/machines \
  -d '{"name": "Desk PC", "mac": "AA:BB:CC:DD:EE:01", "ip": "192.168.1.50", "ready": 445}'
```

It then appears in the app and the panel as the command `wake-desk-pc`.

## Commands, for everything else

Anything beyond waking a machine is a named command you define here:

```bash
sudo nano /etc/wakepc.conf
sudo systemctl restart wakepc
```

```ini
[command:wake-pc]
run = WOL AA:BB:CC:DD:EE:FF     # the PC's ethernet MAC
ping = 192.168.1.50             # optional: lets the app show awake/asleep + rtt
ready = 445                     # optional: UP until this port answers, then READY
broadcast_ip = 192.168.1.255    # optional: your subnet's broadcast

[command:reboot-pi]
run = shell sudo /usr/sbin/reboot
```

The MAC is your PC's *ethernet* adapter: `ipconfig /all` on Windows,
"Physical Address". `run` is either `WOL <mac>` or `shell <command line>`.
Shell commands run as the unprivileged `wakepc` user; the installer adds a
sudoers rule for `/usr/sbin/reboot` only, so anything else privileged needs its
own rule.

The phone can only ask for these **by name**: it can never send a shell
command across the wire.

## Connect the app

```bash
sudo -u wakepc python3 /opt/wakepc/wakepc.py status
```

That prints the address to type into the app, who the relay will let in, and
which commands need the confirmation code. Add the connection in the app with
that address and an empty token.

The browser panel at `http://<address>:8787` needs no unlocking either, when you
open it from a device on the tailnet.

## Auth

Each request's source address is handed to `tailscale whois`. WireGuard has
already proved that address belongs to that peer's key, so tailscaled can name
the node and the account behind it, and a recognised device needs no credential.

- `allow_users = you@example.com` restricts to named accounts. Empty means any
  device in this tailnet, which is right unless the tailnet is shared with other people.
- `bind_host = auto` listens only on the Tailscale address, so a host on your
  home wifi cannot open a connection at all.
- `confirm_code = <digits>` is required by commands marked `confirm` (anything
  that interrupts a running machine, by default). Set it to the long code you
  use in the app. Bad guesses lock auth out for five minutes.
- `token = <secret>` is a fallback for a host with no `tailscale` CLI. Generate
  one with `python3 /opt/wakepc/wakepc.py genpass 5`; minimum 8 characters.

## API

Everything is plain JSON over HTTP on the tailnet, so the app is not the only
thing that can use it: a script, a home automation system or an assistant can
call the same endpoints.

| request | does |
| --- | --- |
| `GET /health` | is the relay well: version, who you are to it, warnings |
| `GET /scan` | devices on the relay's network: `ip`, `mac`, `name` |
| `GET /machines` | the registered machines |
| `POST /machines` | add one: `name`, `mac`, optional `ip`, `ready`, `broadcast_ip` |
| `PUT /machines/<id>` | change any of those fields |
| `DELETE /machines/<id>` | forget it |
| `POST /machines/<id>/wake` | send the wake packet and return at once |
| `POST /machines/<id>/wake?wait=90` | wake, and reply only once it is usable or 90 seconds have passed |
| `GET /machines/<id>/status` | ping stats and `state`: `asleep`, `up`, `ready` or `awake` |
| `GET /commands`, `POST /run/<name>`, `GET /status/<name>` | named commands from the config, plus `wake-<id>` for each machine |

`/health` lists `warnings` in plain words, for example that the relay's
Tailscale key is about to expire. A lapsed key drops the relay off the tailnet
silently, so turn key expiry off for this device in the Tailscale admin console.

## It looks after itself

The relay follows its Tailscale address. If Tailscale comes up late at boot,
restarts, or the address changes, the relay moves to the new address within
half a minute instead of listening where nothing arrives. systemd restarts it
if it ever exits.

## Check it's working

```bash
systemctl status wakepc
journalctl -u wakepc -f
python3 -m unittest -v        # from this directory
```

## Notes

- Binding to a Tailscale address means only devices on your tailnet can reach
  the service at all, and identity checking says *which* of them is calling.
  Traffic inside the tailnet is WireGuard-encrypted, which is why plain HTTP is
  fine here.
- If the magic packet doesn't wake the PC: enable WOL in BIOS/UEFI, enable
  "Wake on Magic Packet" on the network adapter, and turn off Windows Fast
  Startup: it breaks WOL from a full shutdown on many boards.
