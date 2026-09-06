# wakepc — the home-side service

The Android app is only half of WakePC. This service is the other half: it runs
on a machine that stays on at home (a Pi, a NAS — anything with systemd and
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
enables it at boot. It prints the address to type into the app — there is no
token to copy. Re-running it later updates the code and **never overwrites your
config**.

No git on the box? Copy `wakepc.py`, `wakepc.service`, `wakepc.conf.example`
and `install.sh` across with `scp` and run `sudo ./install.sh` in that folder.

## Configure your commands

Everything the phone can do is a named command you define here:

```bash
sudo nano /etc/wakepc.conf
sudo systemctl restart wakepc
```

```ini
[command:wake-pc]
run = WOL AA:BB:CC:DD:EE:FF     # the PC's ethernet MAC
ping = 192.168.1.50             # optional: lets the app show awake/asleep + rtt
broadcast_ip = 192.168.1.255    # optional: your subnet's broadcast

[command:reboot-pi]
run = shell sudo /usr/sbin/reboot
```

The MAC is your PC's *ethernet* adapter — `ipconfig /all` on Windows,
"Physical Address". `run` is either `WOL <mac>` or `shell <command line>`.
Shell commands run as the unprivileged `wakepc` user; the installer adds a
sudoers rule for `/usr/sbin/reboot` only, so anything else privileged needs its
own rule.

The phone can only ask for these **by name** — it can never send a shell
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
  device in this tailnet — right unless the tailnet is shared with other people.
- `bind_host = auto` listens only on the Tailscale address, so a host on your
  home wifi cannot open a connection at all.
- `confirm_code = <digits>` is required by commands marked `confirm` (anything
  that interrupts a running machine, by default). Set it to the long code you
  use in the app. Bad guesses lock auth out for five minutes.
- `token = <secret>` is a fallback for a host with no `tailscale` CLI. Generate
  one with `python3 /opt/wakepc/wakepc.py genpass 5`; minimum 8 characters.

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
  Startup — it breaks WOL from a full shutdown on many boards.
