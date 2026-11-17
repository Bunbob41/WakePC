# Changelog

Every version is tagged in git, so any of them can be rebuilt from its tag.
Only the latest has a published APK on the
[releases page](https://github.com/Bunbob41/WakePC/releases/latest).
The reasoning behind each change lives in
[docs/ENGINEERING.md](docs/ENGINEERING.md).

## Unreleased

Setup to first wake, rebuilt. It used to mean finding a MAC address, editing a
file on the Pi and filling in two forms, with nothing checked until the end.

**Setup**

- Three checked steps in the app: connect to the relay, pick your PC from a
  list, wake it while you watch. No config file is edited anywhere.
- Connect is one field. If it fails, the app marks which link broke: Tailscale
  on the phone, reaching the relay, or the relay recognising the phone.
- The relay scans its own network and the app lists what it found. Tap your
  PC, name it, done. A MAC can still be typed by hand.
- The first wake is guided: packet sent, waiting, starting up, ready. If the
  PC never answers, the app lists the usual causes.
- The relay picks the port to wait for by looking at the PC while it is on,
  so READY works without anyone choosing a port.
- "Connection" is now "relay" throughout. Colour, fallback address and token
  are folded under *advanced*.

**Saying what is wrong**

- A relay that cannot be reached shows **NO RELAY** on the card, the widgets
  and the tile, instead of a timeout that asked whether the PC was awake.
- A wake the relay sent that the PC ignored shows **PC DID NOT ANSWER**.
- Errors name the relay and say what to do. The line above the main button
  shows two lines, so the reason is no longer cut off.
- The home screen shows anything the relay wants looked at, such as a
  Tailscale key about to expire.

**The relay**

- Any always-on computer can be the relay. On Windows,
  `install.ps1 -Relay` sets one up and offers to stop the PC sleeping.
- It recovers on its own. It used to read its Tailscale address once at
  startup, and if that changed it listened where nothing arrived until the
  host was rebooted. It now follows the address.
- A phone can only ever add a machine to wake, at a home-network address.
  Shell commands still come from the relay's config file alone.
  `phone_setup = no` turns even that off.
- `/health` reports problems in plain words. `POST /machines/<id>/wake?wait=90`
  replies once the machine is usable, for callers that are not the app.
- An unedited example config no longer offers a wake button that wakes
  nothing, and the example ships with no commands.
- Each machine also appears as the command `wake-<id>`, so older versions of
  the app and the panel can use it.

Existing setups keep working unchanged: saved relays, machines, widgets and
config files all load as before.

## 0.15.0 (2026-11-08)

- READY. A PC answers ping long before you can use it. Add `ready = 445` (or
  any port that only opens once the machine is usable) to a command on the Pi,
  and the app, widgets, tile and Pi panel show UP while it is still starting
  and READY once that port answers. A wake keeps waiting until READY.
- Without `ready` nothing changes: you still see AWAKE.
- The Quick Settings tile now waits up to 90 seconds, like the app.
- The Pi panel's dim text uses the same readable greys as the app.
- The relay's `status` command lists which commands check a port.

## 0.14.1 (2026-11-07)

- Easier to read. The two darkest greys were lightened: the request line above
  the main button and "via <relay>" under each machine went from 1.9 to 1 contrast
  against the background to 4.9 to 1, and the next grey up from 3.4 to 1 to 6.1 to 1.
  Both now meet the usual 4.5 to 1 minimum for text.
- No text is smaller than 11 point. The request line and "via <relay>" are 12.
- The same applies to the home screen widgets.

## 0.14.0 (2026-11-06)

- Removed the setup QR. Since tokens went away in 0.12.0 it only filled in
  an address, which is quicker to type. Add a connection by typing the Pi's
  Tailscale name or address.
- The app is now fully open source: the QR scanner was its only closed-source
  part (Google Play services). The APK is about 450 KB smaller.
- The Pi panel's QR button, the relay's `/qr.png` route and `wakepc.py qr` are gone, and
  the Pi installer no longer installs `qrencode`.
- Screen text and relay messages no longer use long dashes.

## 0.13.0 (2026-09-06)

A scope correction rather than a feature release. Remote desktop tools can
already shut down a PC; nothing else can wake one that is off. This trims the
project back to that.

- Confirmation codes are off by default. Waking a machine cannot hurt
  anything, so a tap just wakes it.
- A code can now be removed once set. Before this there was no way to unset
  one.
- The Windows relay (`pc/`) is now documented as an optional extra.
- A relay with no `confirm_code` logs a note rather than a warning, since that
  is now the normal setup.

## 0.12.0 (2026-09-06)

- No token any more. The relay asks Tailscale who sent each request, and a
  tailnet address is bound to that device's key, so a device you already
  trust is recognised without a password. Setup is one field.
- The relay decides which commands are disruptive, because it is the only
  part that knows what a command actually runs.
- The long confirmation code is checked by the relay as well as the app.
- The Quick Settings tile hands commands that need a code to the app, which
  can ask for it.
- Relays bind to their own Tailscale address by default, so nothing on the
  local network can reach them.
- New `wakepc.py status` shows a relay's address, who it lets in, and which
  commands need a code.

## 0.11.0 (2026-09-05)

- Confirmation codes: a 4 digit code for ordinary commands and a 6 digit code
  for anything that interrupts a running machine.
- Widgets ask for the code once, when placed, and run on one tap after that.
- Added the field guide and engineering record in `docs/`.

## 0.10.2 (2026-09-05)

- Command buttons on a machine card wrap onto more lines instead of crushing
  the last one.

## 0.10.1 (2026-09-05)

- A word passphrase typed with spaces is accepted.

## 0.10.0 (2026-09-05)

- One machine can take commands from several connections, so a PC woken
  through the Pi and shut down through itself is a single card.

## 0.9.2 (2026-09-05)

- Short Tailscale names like `mypi` work from the phone. Android does not
  apply Tailscale's search domain to app lookups, so the app adds it itself.

## 0.9.1 (2026-09-05)

- A failed name lookup explains what to use instead.

## 0.9.0 (2026-09-04)

- Each widget picks its own machine and command when placed.
- Widgets follow the banner, dial or mini hero style.
- New 1x1 icon widget.

## 0.8.2 (2026-09-04)

- Fixed widgets stuck on a loading spinner in release builds.

## 0.8.1 (2026-09-04)

- Code shrinking is back on with the rules it needs, so the APK is about 3 MB
  again instead of about 29 MB.

## 0.8.0 (2026-09-04)

- The widget comes in three sizes (2x1, 2x2, 4x2), each with its own preview.

## 0.7.3 (2026-09-04)

- The widget shows a real preview in the widget picker.

## 0.7.2 (2026-09-04)

- Fixed the 0.7.0 launch crash.
- Proper adaptive launcher icon.
- The widget is resizable and includes the hero button.

## 0.7.0 (2026-09-04)

- Home screen widget: a row per machine with its last known state.

## 0.6.0 (2026-09-03)

- First published release.
- Machine cards with live status, a configurable hero button, and named
  commands defined by the relay.
- The app keeps its state across rotation.
- Console log of every request, with ping times.
- QR setup and address shorthand.

## Before 0.6.0 (2026-09-02 to 2026-09-03)

Untagged early development: the first Quick Settings tile and Pi relay, the
machine and connection design, the web panel on the Pi, the console, tests,
and continuous integration.
