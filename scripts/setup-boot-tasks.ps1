# Register Windows Scheduled Tasks so NGINX and Stack Pilot start after machine reboot.
# Run as Administrator in PowerShell.
#
# Creates:
#   StackPilot-NGINX-Boot    - starts NGINX ~30s after boot
#   StackPilot-Manager-Boot  - starts Stack Pilot JAR ~60s after boot
#
# Stack Pilot then auto-starts nginx (fallback) + all grok_dev services per application.yml boot settings.

$ErrorActionPreference = "Stop"

$StackPilotHome      = if ($env:STACK_PILOT_HOME) { $env:STACK_PILOT_HOME } else { "E:\Source\stack-pilot" }
$DeploymentScripts   = if ($env:DEPLOYMENT_SCRIPTS) { $env:DEPLOYMENT_SCRIPTS } else { (Join-Path $StackPilotHome "deployment\scripts") }
$StartNginxScript    = Join-Path $DeploymentScripts "start-nginx.ps1"
$StartPilotScript    = Join-Path $StackPilotHome "scripts\start-stack-pilot.ps1"

Write-Host "=== Stack Pilot - Boot Task Setup ===" -ForegroundColor Cyan
Write-Host "NGINX script : $StartNginxScript"
Write-Host "Pilot script : $StartPilotScript"
Write-Host ""

if (-not (Test-Path $StartNginxScript)) {
    Write-Error "Missing $StartNginxScript - set DEPLOYMENT_SCRIPTS or install Deployment repo."
    exit 1
}
if (-not (Test-Path $StartPilotScript)) {
    Write-Error "Missing $StartPilotScript"
    exit 1
}

    Write-Host "Building Stack Pilot JAR (mvn package)..." -ForegroundColor Yellow
Push-Location $StackPilotHome
try {
    & mvn -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
Write-Host "JAR build OK." -ForegroundColor Green

function Register-BootTask {
    param(
        [string]$TaskName,
        [string]$Description,
        [string]$ScriptPath,
        [int]$DelaySeconds
    )

    $existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($existing) {
        Write-Host "Removing existing task '$TaskName'..."
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    }

    $action = New-ScheduledTaskAction `
        -Execute "powershell.exe" `
        -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$ScriptPath`""

    $trigger = New-ScheduledTaskTrigger -AtStartup
    $trigger.Delay = "PT${DelaySeconds}S"

    $settings = New-ScheduledTaskSettingsSet `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -StartWhenAvailable `
        -RunOnlyIfNetworkAvailable `
        -DontStopOnIdleEnd `
        -RestartCount 3 `
        -RestartInterval (New-TimeSpan -Minutes 2)

    $principal = New-ScheduledTaskPrincipal `
        -UserId "SYSTEM" `
        -RunLevel Highest `
        -LogonType ServiceAccount

    Register-ScheduledTask `
        -TaskName $TaskName `
        -Action $action `
        -Trigger $trigger `
        -Settings $settings `
        -Principal $principal `
        -Description $Description `
        -Force | Out-Null

    Write-Host "Registered task '$TaskName' (startup + ${DelaySeconds}s delay)" -ForegroundColor Green
}

try {
    Register-BootTask `
        -TaskName "StackPilot-NGINX-Boot" `
        -Description "Start NGINX reverse proxy after Windows boot (delena.buzz, control.delena.buzz)" `
        -ScriptPath $StartNginxScript `
        -DelaySeconds 30

    Register-BootTask `
        -TaskName "StackPilot-Manager-Boot" `
        -Description "Start Stack Pilot service manager JAR after Windows boot (port 8091)" `
        -ScriptPath $StartPilotScript `
        -DelaySeconds 60

    Write-Host ""
    Write-Host "Boot tasks registered successfully." -ForegroundColor Green
    Write-Host ""
    Write-Host "After reboot:"
    Write-Host "  ~30s  NGINX on port 80"
    Write-Host "  ~60s  Stack Pilot on port 8091"
    Write-Host "  ~105s grok_dev services (auto-start via Stack Pilot boot settings)"
    Write-Host ""
    Write-Host "Verify:"
    Write-Host "  Get-ScheduledTask -TaskName 'StackPilot-*'"
    Write-Host "  E:\Source\Deployment\scripts\status-nginx.ps1"
    Write-Host "  curl http://localhost:8091/api/services"
} catch {
    Write-Error "Failed to register boot tasks: $_"
    Write-Host "Tip: Right-click PowerShell and 'Run as Administrator'." -ForegroundColor Yellow
    exit 1
}
