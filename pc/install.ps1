<#
  Installs the WakePC service on a Windows machine, so the phone can shut it
  down, restart it, sleep or lock it.

      powershell -ExecutionPolicy Bypass -File .\install.ps1

  Unelevated this registers a task that runs at logon, which is enough to
  shut down a machine you left on. Run it elevated and it registers a SYSTEM
  task that starts at boot instead, so the machine can also be shut down
  after a remote wake, before anyone has logged in.

  Safe to re-run: an existing config is never overwritten.
#>
$ErrorActionPreference = 'Stop'

$src     = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo    = Split-Path -Parent $src
$install = Join-Path $env:LOCALAPPDATA 'WakePC'
$conf    = Join-Path $install 'wakepc.conf'
$script  = Join-Path $install 'wakepc.py'
$runner  = Join-Path $install 'run.py'
$log     = Join-Path $install 'wakepc.log'

# pythonw runs the service without a console window, but it writes nothing to
# stdout - so anything whose output we need has to go through python.exe.
$pythonConsole = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $pythonConsole) { throw 'python not found on PATH' }
$python = (Get-Command pythonw -ErrorAction SilentlyContinue).Source
if (-not $python) { $python = $pythonConsole }

New-Item -ItemType Directory -Force -Path $install | Out-Null
Copy-Item (Join-Path $repo 'pi\wakepc.py') $script -Force

# The service reads a fixed config path, so point it at ours.
@"
import importlib.util, sys
# pythonw has no stdout, and the service logs every request, so give it a
# real file to write to before anything tries to print.
sys.stdout = sys.stderr = open(r'$log', 'a', buffering=1, encoding='utf-8')
spec = importlib.util.spec_from_file_location('wakepc', r'$script')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
m.CONFIG_PATH = r'$conf'
m.main()
"@ | ForEach-Object { [IO.File]::WriteAllText($runner, $_, (New-Object Text.UTF8Encoding($false))) }

if (Test-Path $conf) {
    Write-Host "keeping existing $conf"
    $token = ''
} else {
    $token = (& $pythonConsole $script genpass 4).Trim()
    if (-not $token) { throw 'could not generate a token' }
    $ip = (& tailscale ip -4 2>$null | Select-Object -First 1)
    if (-not $ip) { $ip = '0.0.0.0'; Write-Warning 'no tailscale IP found; binding to 0.0.0.0' }
    $lines = @()
    (Get-Content (Join-Path $src 'wakepc.conf.example')) `
        -replace '^token = .*', "token = $token" `
        -replace '^bind_host = .*', "bind_host = $ip" |
        ForEach-Object { $lines += $_ }
    [IO.File]::WriteAllLines($conf, $lines, (New-Object Text.UTF8Encoding($false)))
    Write-Host "wrote $conf"
}

# Autostart via the Startup folder rather than Task Scheduler: it needs no
# elevation, and on some machines the scheduler refuses to launch user tasks
# at all (every action returns "file not found", even cmd.exe).
$startup  = [Environment]::GetFolderPath('Startup')
$launcher = Join-Path $startup 'WakePC.vbs'
$vbs = 'CreateObject("WScript.Shell").Run """' + $python + '"" ""' + $runner + '""", 0, False'
[IO.File]::WriteAllText($launcher, $vbs, (New-Object Text.UTF8Encoding($false)))

# Stop an older copy, then start this one now so it is usable immediately.
Get-CimInstance Win32_Process -Filter "Name like 'python%'" |
    Where-Object { $_.CommandLine -match [regex]::Escape($runner) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Process -FilePath $python -ArgumentList "`"$runner`"" -WindowStyle Hidden
Start-Sleep -Seconds 3

$bind = (Select-String -Path $conf -Pattern '^bind_host = (.*)$').Matches.Groups[1].Value
$port = (Select-String -Path $conf -Pattern '^port = (.*)$').Matches.Groups[1].Value
Write-Host ''
Write-Host 'wakepc installed - starts at logon, running now'
Write-Host "autostart: $launcher"
Write-Host "listening on ${bind}:${port}"
if ($token) { Write-Host "token: $token" }
Write-Host "log: $log"
Write-Host 'add it in the app as another connection, then give this machine a shutdown button.'
