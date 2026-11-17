# wakepc on Windows

The same service runs on Windows, for two different jobs.

## 1. A Windows PC as the relay

The relay is the machine at home that stays on and wakes the others. It does
not have to be a Raspberry Pi: a spare PC in the garage does the job.

You need Python (`winget install Python.Python.3.13`) and Tailscale on it,
signed in to the same account as your phone. Then, in a PowerShell window
opened **as Administrator**, from this folder:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -Relay
```

It installs the service to start at boot, offers to stop the PC sleeping
while plugged in, and prints the address to type into the app. From there the
app does the rest: it asks the relay to look around your network, you pick
your PC from the list, and it wakes it while you watch.

Three things keep a relay reachable, and the installer reminds you of each:

- **It must not sleep.** A sleeping relay takes every wake button with it.
- **It should come back after a power cut.** In its BIOS, set *restore on AC
  power loss* to *power on*.
- **Its Tailscale key must not expire.** Disable key expiry for it in the
  Tailscale admin console. The app warns you two weeks before it would lapse.

This relay mode has not yet been tested on a real Windows machine. The relay's
network scan there reads `arp -a` and assumes an ordinary home network
(255.255.255.0). Please report what you see.

## 2. Shutdown buttons for the PC you wake

> **You probably do not need this.** It exists so the phone can shut down,
> restart or sleep the PC, but any remote desktop tool already does that, and
> a machine you can remote into is already awake. Waking a machine that is off
> is the only thing WakePC does that nothing else can, and that lives on the
> relay. Install this only if you specifically want those buttons on your phone.

The relay can wake this machine, but it cannot shut it down: only the machine
itself can do that. So the same service runs here too, and the PC becomes a
second relay in the app with its own commands.

That means no SSH server, no stored Windows credentials, and no polling delay.

### Install

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

It copies the service to `%LOCALAPPDATA%\WakePC`, binds to this machine's
Tailscale address, starts it, and adds a hidden launcher to the Startup folder
so it comes back at logon. There is no token to copy: it prints the address for
the app. Re-running never overwrites an existing config.

Every command here interrupts a machine somebody may be using, so set
`confirm_code` in `%LOCALAPPDATA%\WakePC\wakepc.conf` to the long code you use
in the app; the relay then refuses a shutdown that does not carry it.

Nothing here needs administrator rights, and no firewall rule is required:
Tailscale's interface is already permitted, so the service is reachable from
your tailnet and from nowhere else.

### One machine, two relays

A machine in the app can take commands from more than one connection, so your
PC shows up as a single card with `wake-pc` (through the relay) and `shutdown-pc`
(through the PC itself) side by side. In the machine editor the commands are
grouped by connection: tick whichever you want, from either.

### Commands

`wakepc.conf.example` ships with four, and you can edit
`%LOCALAPPDATA%\WakePC\wakepc.conf` to add more:

| command | does | needs a logged-in session |
| --- | --- | --- |
| `shutdown-pc` | `shutdown /s /t 5` | no |
| `restart-pc` | `shutdown /r /t 5` | no |
| `sleep-pc` | suspends the machine | yes |
| `lock-pc` | locks the screen | yes |

The `/t 5` delay matters: `shutdown` returns immediately and the HTTP response
gets out before the machine goes away.

After editing the config, restart the service:

```powershell
Get-CimInstance Win32_Process -Filter "Name like 'python%'" |
  Where-Object { $_.CommandLine -match 'WakePC' } | Stop-Process -Force
Start-Process pythonw "$env:LOCALAPPDATA\WakePC\run.py" -WindowStyle Hidden
```

## Limits worth knowing

- **Unelevated, it runs while you are logged in.** The launcher lives in the
  Startup folder, so after a remote wake the service is not up until someone
  logs in: enough to shut down a machine you left on, not one you just woke.

  Run the installer **as Administrator** and it registers a SYSTEM task that
  starts at boot instead, which covers that case. It removes the logon
  launcher so the two cannot both run. There is no unprivileged way to start
  something before logon: the Startup folder and the Run keys are both
  logon-triggered.
- Some machines refuse to run user Scheduled Tasks at all (every action
  returns "file not found"), which is why this uses the Startup folder.

## Logs and removal

The service writes to `%LOCALAPPDATA%\WakePC\wakepc.log`. To remove it, delete
`WakePC.vbs` from your Startup folder (`shell:startup`) and the
`%LOCALAPPDATA%\WakePC` directory.
