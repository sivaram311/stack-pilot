# Periodic RDP health check — restart TermService if stopped or recent rdpcorets crash.
# Intended for scheduled task StackPilot-RDP-Health-Check.
param(
    [string]$LogFile = "",
    [int]$CrashRestartWindowMinutes = 5
)

$ErrorActionPreference = "SilentlyContinue"
$StackPilotHome = if ($env:STACK_PILOT_HOME) { $env:STACK_PILOT_HOME } else { "E:\Source\stack-pilot" }
if (-not $LogFile) {
    $LogFile = Join-Path $StackPilotHome "logs\rdp-health.log"
}

function Write-Log($msg) {
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg"
    $dir = Split-Path $LogFile -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

$statusScript = Join-Path $StackPilotHome "scripts\rdp-status.ps1"
$recoverScript = Join-Path $StackPilotHome "scripts\rdp-recover-session.ps1"
$mitigateScript = Join-Path $StackPilotHome "scripts\rdp-apply-mitigations.ps1"

& $mitigateScript | Out-Null

$statusJson = & $statusScript | ConvertFrom-Json
$termStatus = $statusJson.termService.status

if ($statusJson.fResetBroken -ne 1) {
    Write-Log "WARN fResetBroken not set; mitigation script should have fixed this"
}

$LastRecoveredFile = Join-Path $StackPilotHome "logs\rdp-last-recovered-crash.txt"
$recentCrash = $false
$crashTimeStr = ""

if ($statusJson.lastRdpcoretsCrash -and $statusJson.lastRdpcoretsCrash.timeCreated) {
    $crashTimeStr = $statusJson.lastRdpcoretsCrash.timeCreated
    
    $lastRecoveredTime = ""
    if (Test-Path $LastRecoveredFile) {
        $lastRecoveredTime = (Get-Content -Path $LastRecoveredFile -ErrorAction SilentlyContinue).Trim()
    }
    
    if ($crashTimeStr -ne $lastRecoveredTime) {
        $crashTime = [DateTime]::Parse($crashTimeStr)
        # Check if the crash occurred within a reasonable maximum window (e.g. 24 hours) to avoid triggering on old historical crashes on setup
        if ($crashTime -gt (Get-Date).AddHours(-24)) {
            $recentCrash = $true
        }
    }
}

if ($termStatus -ne "Running" -or $recentCrash) {
    $reason = if ($termStatus -ne "Running") { "TermService=$termStatus" } else { "rdpcorets crash at $crashTimeStr" }
    Write-Log "ACTION recover triggered ($reason)"
    $recover = & $recoverScript | ConvertFrom-Json
    if ($recover.success) {
        Write-Log "OK $($recover.message)"
        if ($recentCrash -and $crashTimeStr) {
            $crashTimeStr | Out-File -FilePath $LastRecoveredFile -Encoding UTF8 -Force
        }
    } else {
        Write-Log "FAIL $($recover.message)"
        exit 1
    }
} else {
    Write-Log "OK healthy; sessions=$($statusJson.sessions.Count) crashes24h=$($statusJson.crashCount24h)"
}
