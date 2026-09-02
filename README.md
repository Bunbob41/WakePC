# WakePC

One-tap Wake-on-LAN for the PC, from anywhere, over Tailscale.

Two halves:

- **`pi/`** — a stdlib-only Python service for the Raspberry Pi. It sits on the
  tailnet and exposes `POST /wake` (broadcasts the magic packet on the LAN) and
  `GET /status` (pings the PC), both behind a bearer token. Setup steps are in
  [pi/README.md](pi/README.md).
- **The Android app** — a Quick Settings tile. Tap it: it tells the Pi to wake
  the PC, then polls `/status` and flips to "PC awake" once the PC answers
  ping. The launcher activity is just the settings screen (Pi base URL + token)
  with manual Wake / Check status buttons for testing.

## Phone setup

1. Install Tailscale from the Play Store and sign in to your tailnet.
2. Install the app: `gradlew :app:assembleDebug`, then
   `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. Open WakePC, enter `http://<pi-tailscale-ip>:8787` and the token from
   `/etc/wakepc.conf`, hit Save, and try "Wake PC".
4. Edit the Quick Settings panel (pencil icon) and drag the **Wake PC** tile in.

## Why this architecture

A WOL magic packet is a LAN broadcast — nothing outside the home network can
deliver it, so the Pi acts as the relay. Tailscale provides the authenticated,
encrypted path to the Pi with no port forwarding; the token is defense in
depth on top of tailnet membership.
