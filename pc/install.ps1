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

$fresh = $false
if (Test-Path $conf) {
    Write-Host "keeping existing $conf"
} else {
    $fresh = $true
    # No token: this relay identifies callers with 'tailscale whois',
    # so there is nothing to carry across to the phone.
    $lines = Get-Content (Join-Path $src 'wakepc.conf.example')
    [IO.File]::WriteAllLines($conf, $lines, (New-Object Text.UTF8Encoding($false)))
    Write-Host "wrote $conf"
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$elevated = (New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)

$startup  = [Environment]::GetFolderPath('Startup')
$launcher = Join-Path $startup 'WakePC.vbs'

# Stop whatever is already running before starting a new copy.
Get-CimInstance Win32_Process -Filter "Name like 'python%'" |
    Where-Object { $_.CommandLine -match [regex]::Escape($runner) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

if ($elevated) {
    # Elevated: run at boot as SYSTEM, so the machine can be shut down even
    # after a remote wake, before anyone has logged in. The logon launcher
    # would only duplicate this, so remove it.
    Remove-Item $launcher -Force -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName 'WakePC' -Confirm:$false -ErrorAction SilentlyContinue

    $action    = New-ScheduledTaskAction -Execute $python -Argument "`"$runner`""
    $trigger   = New-ScheduledTaskTrigger -AtStartup
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval ([TimeSpan]::FromMinutes(1))

    Register-ScheduledTask -TaskName 'WakePC' -Action $action -Trigger $trigger `
        -Principal $principal -Settings $settings -Force | Out-Null
    Start-ScheduledTask -TaskName 'WakePC'
    $note = 'runs at boot as SYSTEM'
} else {
    # Unelevated: the Startup folder is the only autostart available, and it
    # needs no permissions. Task Scheduler is not used - some machines refuse
    # to run user tasks at all, returning "file not found" for any action.
    $vbs = 'CreateObject("WScript.Shell").Run """' + $python + '"" ""' + $runner + '""", 0, False'
    [IO.File]::WriteAllText($launcher, $vbs, (New-Object Text.UTF8Encoding($false)))
    Start-Process -FilePath $python -ArgumentList "`"$runner`"" -WindowStyle Hidden
    $note = 'runs at logon (re-run this as Administrator to start it at boot instead)'
}

# Do not claim success without checking: poll until it actually answers.
$port = (Select-String -Path $conf -Pattern '^port = (.*)$').Matches.Groups[1].Value.Trim()
$up = $false
foreach ($i in 1..10) {
    Start-Sleep -Seconds 1
    if (Get-CimInstance Win32_Process -Filter "Name like 'python%'" |
        Where-Object { $_.CommandLine -match [regex]::Escape($runner) }) { $up = $true; break }
}
if (-not $up) {
    Write-Warning "the service did not start - see $log"
    if ($elevated) { Write-Warning 'if the task never runs, this machine may block Scheduled Tasks; use the unelevated install instead' }
}

Write-Host ''
Write-Host "wakepc installed - $note"
$env:WAKEPC_CONFIG = $conf
& $pythonConsole $script status
Write-Host "log: $log"
if ($fresh) {
    Write-Host 'add it in the app as another connection - paste the address above, leave the token empty.'
    Write-Host 'then set confirm_code in the config to your 6-digit code so the relay checks it too.'
}
