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
`/opt/wakepc`, writes `/etc/wakepc.conf` with a freshly generated token, binds
to your Tailscale IP if you have one, and enables it at boot. It prints the
token — you'll enter that in the app. Re-running it later updates the code and
**never overwrites your config**.

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
sudo python3 /opt/wakepc/wakepc.py qr    # prints a QR the app can scan
grep token /etc/wakepc.conf              # or read the token to type by hand
```

There's also a browser panel at `http://<address>:8787` — unlock it with the
token to run commands, see live status, and display the setup QR on a screen
big enough for the phone to scan.

## Auth

Any token of 4+ characters works: a PIN is fine behind a tailnet, and repeated
bad guesses lock auth out for five minutes. For something stronger:

```bash
python3 /opt/wakepc/wakepc.py genpass 5   # maple-otter-comet-birch-flint
openssl rand -hex 32                      # maximum entropy
```

## Check it's working

```bash
systemctl status wakepc
journalctl -u wakepc -f
python3 -m unittest -v        # from this directory
```

## Notes

- Binding to a Tailscale IP means only devices on your tailnet can reach the
  service at all; the token is a second layer on top. Traffic inside the
  tailnet is WireGuard-encrypted, which is why plain HTTP is fine here.
- If the magic packet doesn't wake the PC: enable WOL in BIOS/UEFI, enable
  "Wake on Magic Packet" on the network adapter, and turn off Windows Fast
  Startup — it breaks WOL from a full shutdown on many boards.
