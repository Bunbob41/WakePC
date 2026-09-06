#!/usr/bin/env bash
# Install (or update) the wakepc service. Run on the machine that will relay
# commands — a Pi, a NAS, anything with systemd and python3:
#
#   sudo ./install.sh
#
# Safe to re-run: an existing /etc/wakepc.conf is never overwritten.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "error: run with sudo" >&2
  exit 1
fi

SRC="$(cd "$(dirname "$0")" && pwd)"

# Dedicated unprivileged account that owns the config and runs the service.
id wakepc >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin wakepc

install -d /opt/wakepc
install -m 644 "$SRC/wakepc.py" /opt/wakepc/wakepc.py
install -m 644 "$SRC/wakepc.service" /etc/systemd/system/wakepc.service

FRESH=""
if [ -f /etc/wakepc.conf ]; then
  echo "keeping existing /etc/wakepc.conf"
else
  FRESH="yes"
  # No token is generated: callers are identified by tailscale whois,
  # so there is nothing for you to copy across to the phone.
  cp "$SRC/wakepc.conf.example" /etc/wakepc.conf
  echo "wrote /etc/wakepc.conf"
fi
chown wakepc:wakepc /etc/wakepc.conf
chmod 600 /etc/wakepc.conf

# The example config ships a reboot command; allow just that one binary.
if [ ! -f /etc/sudoers.d/wakepc ]; then
  echo 'wakepc ALL=(root) NOPASSWD: /usr/sbin/reboot' > /etc/sudoers.d/wakepc
  chmod 440 /etc/sudoers.d/wakepc
  visudo -c -q || { rm -f /etc/sudoers.d/wakepc; echo "warning: sudoers rule rejected, removed" >&2; }
fi

# Optional: only needed for the setup-QR feature.
command -v qrencode >/dev/null 2>&1 || apt-get install -y -qq qrencode >/dev/null 2>&1 || true

systemctl daemon-reload
systemctl enable --now wakepc >/dev/null
sleep 1

if ! systemctl is-active --quiet wakepc; then
  echo "error: service failed to start — journalctl -u wakepc -n 20" >&2
  exit 1
fi

echo
python3 /opt/wakepc/wakepc.py status
if [ -n "$FRESH" ]; then
  echo
  echo "Next: edit /etc/wakepc.conf to set your PC's MAC and ping IP,"
  echo "then 'sudo systemctl restart wakepc'."
  echo "In the app: add connection, paste the address above. No token."
fi
