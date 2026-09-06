# WakePC — Engineering Record

The master record for this project: what it is, why it is shaped this way, and
what is still owed. Plain-language companion: [FIELD-GUIDE.md](FIELD-GUIDE.md).

## System

```mermaid
flowchart TB
    subgraph android["Android app (app/)"]
        UI["Compose UI<br/>HomeScreen · Editors · Settings"]
        VM["HomeViewModel"]
        STORE["Store<br/>DataStore Preferences, JSON AppState"]
        API["WakeApi (OkHttp)<br/>+ SearchDomainDns"]
        W["Glance widgets<br/>4 receivers"]
        TILE["WakeTileService (QS tile)"]
    end
    subgraph relays["Relay service (pi/wakepc.py) — same file, two hosts"]
        PI["Raspberry Pi<br/>systemd, user 'wakepc'"]
        PCS["Windows PC — OPTIONAL<br/>not installed by default"]
    end
    PC["Desk PC hardware"]
    UI --> VM --> API
    W --> API
    TILE --> API
    VM <--> STORE
    API -- "HTTP over Tailscale<br/>identified by whois" --> PI
    API -- "HTTP over Tailscale<br/>+ confirm code" --> PCS
    PI -- "UDP magic packet (LAN broadcast)" --> PC
    PI -- "ICMP ping (status)" --> PC
    PCS -- "shutdown /s /r, rundll32 sleep" --> PC
```

**Layering:** UI → `HomeViewModel` → `WakeApi` (interface, fakeable) → OkHttp.
`Store` owns the single `AppState`, serialised as JSON into DataStore
Preferences. Widgets bypass the ViewModel and call `WakeApi` directly, because a
Glance `ActionCallback` has no lifecycle to host one.

**Wire protocol** (`pi/wakepc.py`): `GET /commands`, `POST /run/<name>`,
`GET /status/<name>?count=N`, legacy `/wake` + `/status`, plus `/` (HTML panel)
and `/qr.png`. Callers are identified by `tailscale whois` on the source
address; a bearer token is the fallback. Commands the relay marks `elevated`
additionally require `X-WakePC-Confirm`. 8 failures in 5 minutes locks the
caller out. Commands are *named entries in the relay's own config* — the client
can never supply a shell string.

## Decision log

Newest first. Each entry: the decision, why, and what it replaced.

### Scope correction: the PC relay is optional, the code gate is off by default — 0.13.0

Stripped back. The Windows relay is uninstalled from the user's PC, `pc/` is
documented as an optional extra rather than one of two halves, confirmation
codes default to off and can now be **removed** once set, and the relay no
longer treats "no confirm_code" as a warning.

*Why:* the user pointed out they had never once used shutdown from the phone —
they have four remote desktop tools that do it. The asymmetry they named is the
right frame for this project: **remote desktop cannot wake a machine that is
off.** That is the only capability WakePC has that nothing else does. Shutdown
duplicated tools that already existed.

The cost of having built it was not small: five of the last eight feature
commits traced back to shutdown (Windows relay, boot-time install, multiple
relays per machine, both confirmation-code releases), 9 of 16 app source files
touched `elevated`/`confirmCode`/`connectionId`, and the Windows side generated
its own tail of bugs — Task Scheduler refusing user tasks, `pythonw` having no
stdout, PowerShell's BOM, and a boot-startup install that turned out never to
have been in place.

*Process note, worth more than the code change:* the user floated shutdown as
speculation ("theoretically we could"), then rejected a design and said "park
it." It was re-raised later with a different design and accepted. The lesson is
that "theoretically we could" is not a request, and a parked idea should stay
parked until the user brings it back.

*What was kept, because it earned its place on wake alone:* tailnet identity
(no token, one field to set up) and the R8/icon/widget fixes. Multi-connection
machines stay too — harmless, and general.

*What is NOT removed:* `pc/` stays in the repo, tested and documented. The cost
was never lines in git; it was a service listening on the user's main machine
for a feature they did not want. `pc/install.ps1` puts it back in one command.

### Tailnet identity replaces the token; the code gets teeth — 0.12.0

The relay now calls `tailscale whois` on the source address of each request.
WireGuard has already proved that address belongs to that peer's key, so it is
not forgeable, and tailscaled will name the node and the account behind it.
A recognised device therefore needs **no credential at all**.

*Why:* 0.11.0 made the relay token equal to the 4-digit PIN, which quietly cut
the relay credential from a ~60-bit passphrase to 10,000 possibilities. The
lockout slows that to roughly 2,300 guesses a day — days, not centuries. The
alternative was to keep a long token and make enrolment easier, but the honest
observation was that a token was never the right shape: the tailnet already
knows who you are, and asking the user to carry a secret across devices was
solving a problem Tailscale had solved. **Replaced:** mandatory `token`,
QR-based enrolment as the primary path, and the panel's unlock screen.

`token` still exists as a fallback for a host with no tailscale CLI, and must
now be at least 8 characters — it is no longer allowed to be a PIN.

*Anti-spoofing:* `bind_host = auto` binds to this host's own tailnet address,
so a LAN host cannot even open a connection claiming to be a peer. Source
addresses are also range-checked against Tailscale's CGNAT and ULA ranges.
`allow_users` narrows further, to named tailnet accounts.

### `elevated` is decided by the relay, and the code is enforced there — 0.12.0

`/commands` now returns `elevated` per command, derived from what the command
actually runs, and `/run/<name>` requires `X-WakePC-Confirm` for those.

*Why:* two separate holes in 0.11.0. First, `looksDisruptive()` was a regex over
a *nickname* — a command called `goodnight` that ran `shutdown /s` got the short
code, so the default failed open. The relay is the only party that knows the
command line, so it decides; the client only guesses for older relays that do
not report the field. Second, the code was checked purely client-side, so
anything that could reach the relay could shut the machine down without ever
seeing it. Now the relay checks it too, and bad attempts count toward the same
lockout.

The Quick Settings tile cannot show a keypad and, unlike a widget, was never
authorised at placement — so an elevated command from the tile opens the app to
be confirmed properly.

*Debt kept deliberately:* an authorised widget stores the code, because it must
present it to the relay. Placing the widget is the consent for exactly that.

### Confirmation codes replace the "token" as the user-facing gate — 0.11.0

Two codes live in `AppState`: `shortPin` (4 digits, ordinary commands) and
`longPin` (6 digits, disruptive ones). `CommandRef.elevated` picks which,
defaulted by `looksDisruptive(name)` (regex over shutdown/restart/reboot/
sleep/suspend/hibernate/logoff) and overridable per command in the editor.
`HomeScreen` routes every press through a `Pending` record and `PinPrompt`.

*Why:* the relay token was real security but bad ergonomics — the user had to
transcribe a passphrase into settings, and it did nothing to stop a mis-tap
shutting down a running machine. Reframing it as a confirmation gate makes the
thing the user types serve the purpose they actually cared about. **Superseded:**
`cleanToken` heuristics as the primary path (kept, but no longer the focus).

*Widget exception:* a Glance tap cannot host a dialog, so widgets are authorised
**once at placement** — `WidgetConfigActivity` shows `PinPrompt` before writing
`authorisedKey = "yes"` into that instance's prefs. `RunAction` refuses with
`NOT AUTHORISED` when a gate exists and the flag is absent. This is a deliberate
trade: placing a widget *is* the act of consent.

*Debt, since corrected:* this release also set the relay token equal to the PIN,
collapsing a credential and a confirmation into one weak secret. That was a
regression, and 0.12.0 undoes it by removing the token entirely.

### One card per machine, commands from several relays — 0.10.0

`CommandRef.connectionId` (null = the machine's own). `AppState.connectionFor()`
resolves per command; the machine editor fetches `/commands` from *every*
connection and groups them under coloured headers.
*Why:* the PC is woken through the Pi but shut down through itself, which
otherwise forced two cards for one physical machine. **Replaced:** one machine =
one connection.

### The PC runs the same relay, rather than the Pi SSH-ing into it — 0.9.0

`pc/install.ps1` installs `wakepc.py` on Windows. Unelevated → a VBS shim in the
Startup folder; elevated → a SYSTEM scheduled task that runs at boot.
*Why:* the SSH design (Pi holds Windows credentials, runs a desktop batch file)
was explicitly parked by the user as too complicated, and it meant storing
Windows credentials on the Pi. Running the same service removes both.
*Constraint discovered:* Task Scheduler on this machine refuses **user** tasks
(proved with a bare `cmd.exe` probe task) — hence the Startup-folder fallback.
*Constraint discovered:* `pythonw` has no stdout, so the first `print` killed the
service; the runner now redirects to `%LOCALAPPDATA%\WakePC\wakepc.log`.

### Resolve bare MagicDNS names ourselves — 0.8.x

`SearchDomainDns` appends the network's search domains to a dotless hostname
that fails to resolve.
*Why:* Android does not apply DNS search domains to app lookups, so `pi-nd`
worked in a browser but not in the app. **Replaced:** telling the user to type
the full `<host>.<tailnet>.ts.net` form.

### R8 back on, with explicit keep rules — 0.7.x

*Why:* 0.7.0 would not launch at all. Root cause (found on an emulator, after two
wrong theories about resource shrinking and Glance protobuf): R8 stripped
`androidx.work.impl.WorkDatabase_Impl`, so `androidx.startup.InitializationProvider`
threw before `MainActivity`. A second, quieter instance of the same class of bug
left widgets on a permanent spinner — WorkManager only *logs*
`OverwritingInputMerger has no zero argument constructor`, so nothing crashed.
Keep rules now cover Room `_Impl`s, WorkManager `InputMerger`/`ListenableWorker`
constructors, Glance `ActionCallback`s and widget classes, and the tile service.
*Method that settled it:* isolate by build type — minified dumped a
`ProgressBar`, unminified a `TextView`, via `uiautomator dump`.

### Public repo, rewritten history — after 0.6

`git-filter-repo` twice: once to scrub real infra values (Tailscale IPs, MAC,
LAN IP, hostname, tokens) into placeholders, once more because two post-rewrite
commits carried a personal email from the *global* git config. Repo-local
identity is now set.
*Standing rule:* only placeholders in tracked files — `test-token`,
`100.64.0.2`, `AA:BB:CC:DD:EE:FF`, `192.168.1.50`, `homepi`.

### Named commands, never client-supplied shell — 0.5.0

The relay registry (`[command:<name>]`, `run = WOL <mac>` or `run = shell <cmd>`)
is the security boundary. A compromised phone can only invoke the list you
already wrote down. This is the most load-bearing design choice in the project
and should not be relaxed.

### The M1 design direction — 0.4.0

The user rejected the first build as "big clanky" and, from a set of A/B hybrids
and rearrangements, chose M1: console/terminal aesthetic, machine cards with chip
rows, one configurable hero button. Mockups kept in `design/`.
*Consequence:* everything is monospace `ConsoleText` on a dark `Palette`; avoid
stock Material components, which read as generic.

### A Pi relay at all — 0.1.0

A magic packet is a LAN broadcast; it cannot cross the internet or a routed VPN.
Something already at home must send it. Tailscale carries the phone→Pi hop so
nothing is exposed to the internet and no ports are forwarded.

## Toolchain

- **AGP 9** — no `kotlin-android` plugin, `buildConfig` must be opted in, and
  only `testDebugUnitTest` exists (there is no `test` lifecycle task).
- **detekt** + **spotless(ktlint)** for Kotlin, **ruff** for Python, GitHub
  Actions for CI.
- Release is minified and **debug-signed** on purpose: a personal sideload, and
  a stable key keeps upgrades friction-free.
- Version lives only in `app/build.gradle.kts`; the settings screen reads
  `BuildConfig.VERSION_NAME`.

**Gotcha, recurring:** editing `build.gradle.kts` with `sed -i` rewrites line
endings and breaks `spotlessKotlinGradleCheck`. Always run `spotlessApply` after
a version bump, and let `spotlessCheck detekt testDebugUnitTest` pass *before*
pushing.

**Gotcha:** PowerShell's `-Encoding utf8` writes a BOM, which made configparser
throw `MissingSectionHeaderError`. Fixed on both sides — write without a BOM,
read with `utf-8-sig` — and covered by a regression test.

## Testing

34 unit tests (`app/src/test/`), covering `AppState` resolution and gating, the
paste-cleaning heuristics, saver round-trips, and the relay's config parsing.
The manual rig that has repeatedly earned its keep: emulator AVD
`Medium_Phone_API_36.1` plus a local `pi/wakepc.py` acting as a mock relay, with
`uiautomator dump` to prove whether a widget actually composed.

Widget placement can only be automated with `input motionevent DOWN/MOVE/UP`
(long-press, then move) — `input swipe` and `input draganddrop` do **not** place
widgets.

## Open questions and debts

- **Codes are stored in cleartext** in DataStore, and an authorised widget stores
  one too. Inside a tailnet this is a confirmation, not a credential — but if
  the relay is ever reachable from outside, it needs Keystore.
- **No lockout on the app-side prompt** — the relay throttles, the dialog does
  not. Matters less now the relay enforces elevated commands itself.
- **Widget authorisation is per instance and permanent.** No way to revoke short
  of removing the widget.
- **`tailscale whois` shells out per request** (cached 5 minutes). Fine at this
  scale; the local API over the unix socket would avoid the process spawn.
- **The PC relay is uninstalled** (0.13.0), so its boot-startup question is moot
  unless someone reinstalls it. If they do: the elevated SYSTEM-task path is
  still **unproven by an actual reboot**, and the unelevated Startup-folder path
  only runs after a user logs in.
- **The user's real confirmation codes were committed** in 0.11.0's `StateTest`
  and pushed to the public repo. Scrubbed from the working tree in 0.13.0; the
  history still carries them at `72fbe2a` unless rewritten. The codes should be
  treated as burned regardless — a public repo may already be cloned or cached.
  Placeholders only, and the leak scan must run over `git grep` across tracked
  files, not just the staged diff, which is how this was missed.
