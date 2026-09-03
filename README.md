# WakePC

One-tap Wake-on-LAN (and more) for the machines at home, from anywhere, over
Tailscale.

Two halves:

- **`pi/`** — a stdlib-only Python service. It sits on the tailnet and exposes
  named commands defined in `/etc/wakepc.conf`: `GET /commands` lists them,
  `POST /run/<name>` runs one (a Wake-on-LAN magic packet or a shell command),
  and `GET /status/<name>?count=N` pings a target and reports up/down plus rtt.
  It also serves a browser control panel at `/`. Everything is behind a bearer
  token (a PIN or passphrase) with a brute-force lockout. Setup is in
  [pi/README.md](pi/README.md).
- **The Android app** (`app/`) — Jetpack Compose. The home screen shows a card
  per machine with live status over a configurable hero button; a Quick
  Settings tile fires your chosen command. You set up one authenticated
  **connection** per Pi, then define **machines** whose buttons are chosen from
  the commands that connection actually offers.

## Why this architecture

A WOL magic packet is a LAN broadcast — nothing outside the home network can
deliver it, so the Pi acts as the relay. Tailscale provides the authenticated,
encrypted path with no port forwarding; the token is defense in depth on top of
tailnet membership. The phone can only invoke commands the server defines *by
name* — it can never send shell across the wire.

## Phone setup

1. Install Tailscale from the Play Store and sign in to your tailnet.
2. Install the app (see Development below, or sideload a release APK).
3. Open WakePC and add a connection: name it, enter the Pi's address
   (`100.x.y.z` or a MagicDNS name — scheme and port are filled in), and the
   token. "Scan setup qr" against the Pi panel does this without typing.
4. Add a machine, pick its commands, and drag the **Wake PC** tile into Quick
   Settings.

## Development

The app and the service each have a test suite; CI (`.github/workflows/ci.yml`)
runs both on every push.

```bash
# Android: format check, static analysis, unit tests
./gradlew spotlessCheck detekt testDebugUnitTest
./gradlew spotlessApply            # auto-fix formatting
./gradlew :app:assembleRelease     # build the APK

# Pi service: lint + tests (from pi/)
ruff check .
python -m unittest -v
```

The app targets the same toolchain as the sibling chat app (AGP 9.3.1, Kotlin
2.2.10, Compose BOM 2026.08.00). `HomeViewModel` owns the polling and in-flight
commands so they survive rotation; `WakeRepository` is the network seam that
tests fake.
