# wakepc on Windows

The Pi can wake this machine, but it cannot shut it down — only the machine
itself can do that. So the same service runs here too, and the PC becomes a
second connection in the app with its own commands.

That means no SSH server, no stored Windows credentials, and no polling delay.

## Install

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

It copies the service to `%LOCALAPPDATA%\WakePC`, generates a token, binds to
this machine's Tailscale IP, starts it, and adds a hidden launcher to the
Startup folder so it comes back at logon. It prints the token — that goes in
the app. Re-running never overwrites an existing config.

Nothing here needs administrator rights, and no firewall rule is required:
Tailscale's interface is already permitted, so the service is reachable from
your tailnet and from nowhere else.

## One machine, two relays

A machine in the app can take commands from more than one connection, so your
PC shows up as a single card with `wake-pc` (through the Pi) and `shutdown-pc`
(through the PC itself) side by side. In the machine editor the commands are
grouped by connection — tick whichever you want, from either.

## Commands

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

- **It runs while you are logged in.** The launcher lives in the Startup
  folder, so after a remote wake the service is not up until someone logs in.
  Enough to shut down a machine you left on; not enough to shut down one you
  just woke. Running it as a boot-time service needs administrator rights.
- Some machines refuse to run user Scheduled Tasks at all (every action
  returns "file not found"), which is why this uses the Startup folder.

## Logs and removal

The service writes to `%LOCALAPPDATA%\WakePC\wakepc.log`. To remove it, delete
`WakePC.vbs` from your Startup folder (`shell:startup`) and the
`%LOCALAPPDATA%\WakePC` directory.
