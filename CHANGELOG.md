# Changelog

Every version is tagged in git, so any of them can be rebuilt from its tag.
Only the latest has a published APK on the
[releases page](https://github.com/Bunbob41/WakePC/releases/latest).
The reasoning behind each change lives in
[docs/ENGINEERING.md](docs/ENGINEERING.md).

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
