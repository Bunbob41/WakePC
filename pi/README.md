# wakepc — Pi setup

One-time install, done over your existing SSH access to the Pi.

## 1. Copy the files over

From this directory on your PC:

```bash
scp wakepc.py wakepc.service wakepc.conf.example pi@<pi-address>:/tmp/
```

## 2. Install on the Pi

```bash
sudo mkdir -p /opt/wakepc
sudo mv /tmp/wakepc.py /opt/wakepc/
sudo mv /tmp/wakepc.service /etc/systemd/system/
sudo mv /tmp/wakepc.conf.example /etc/wakepc.conf
sudo chmod 600 /etc/wakepc.conf
```

## 3. Configure

```bash
openssl rand -hex 32        # this is your token — you'll paste it into the app too
tailscale ip -4             # this is the bind_host
sudo nano /etc/wakepc.conf  # fill in token, mac, pc_ip, bind_host
```

The MAC is your PC's *ethernet* adapter (the one with WOL enabled in
BIOS/adapter settings) — `ipconfig /all` on the PC, "Physical Address".

## 4. Start it

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now wakepc
systemctl status wakepc
```

## 5. Test from the Pi itself

```bash
curl -X POST -H "Authorization: Bearer <token>" http://<tailscale-ip>:8787/wake
curl -H "Authorization: Bearer <token>" http://<tailscale-ip>:8787/status
```

`/wake` should answer `{"sent": true}` and the PC should power on.
`/status` answers `{"awake": true}` once the PC responds to ping.

## Notes

- Binding to the Tailscale IP means only devices in your tailnet can reach
  the service at all; the bearer token is a second layer on top.
- Traffic inside the tailnet is WireGuard-encrypted end to end, which is why
  plain HTTP is fine here.
- If the magic packet doesn't wake the PC, check: WOL enabled in BIOS/UEFI,
  "Wake on Magic Packet" enabled on the Windows network adapter, and Windows
  Fast Startup off (it breaks WOL from full shutdown on many boards).
