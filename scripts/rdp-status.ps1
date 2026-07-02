# RDP / TermService health snapshot (JSON stdout).
param(
    [int]$CrashLookbackHours = 168
)

$ErrorActionPreference = "SilentlyContinue"

function Get-FResetBroken {
    try {
        $v = (Get-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp" -Name fResetBroken).fResetBroken
        return [int]$v
    } catch { return $null }
}

function Get-Sessions {
    $sessions = @()
    $raw = query session 2>&1
    foreach ($line in $raw) {
        if ($line -match '^\s*(\S+)\s+(\S*)\s+(\d+)\s+(\S+)') {
            $name = $Matches[1]
            if ($name -eq "SESSIONNAME") { continue }
            $sessions += [ordered]@{
                sessionName = $name
                username    = $Matches[2]
                id          = [int]$Matches[3]
                state       = $Matches[4]
            }
        }
    }
    return $sessions
}

function Get-LastRdpcoretsCrash {
    param([int]$Hours)
    $since = (Get-Date).AddHours(-$Hours)
    $events = Get-WinEvent -FilterHashtable @{
        LogName      = "Application"
        ProviderName = "Application Error"
        Id           = 1000
    } -MaxEvents 50 -ErrorAction SilentlyContinue | Where-Object {
        $_.TimeCreated -ge $since -and $_.Message -match "rdpcorets"
    } | Select-Object -First 1

    if (-not $events) { return $null }
    return [ordered]@{
        timeCreated = $events.TimeCreated.ToString("o")
        message     = ($events.Message -split "`n")[0].Trim()
    }
}

function Get-RecentCrashCount {
    param([int]$Hours)
    $since = (Get-Date).AddHours(-$Hours)
    return (Get-WinEvent -FilterHashtable @{
        LogName      = "Application"
        ProviderName = "Application Error"
        Id           = 1000
    } -MaxEvents 100 -ErrorAction SilentlyContinue | Where-Object {
        $_.TimeCreated -ge $since -and $_.Message -match "rdpcorets"
    }).Count
}

$termService = Get-Service TermService -ErrorAction SilentlyContinue
$lastCrash = Get-LastRdpcoretsCrash -Hours $CrashLookbackHours
$crashCount24h = Get-RecentCrashCount -Hours 24
$fReset = Get-FResetBroken

$warnings = @()
if ($fReset -ne 1) { $warnings += "fResetBroken is not enabled" }
if ($termService -and $termService.Status -ne "Running") { $warnings += "TermService is not running" }
if ($crashCount24h -gt 0) { $warnings += "rdpcorets.dll crashed $crashCount24h time(s) in last 24h" }

$healthy = ($warnings.Count -eq 0) -and ($termService.Status -eq "Running")

[ordered]@{
    healthy           = $healthy
    warnings          = $warnings
    termService       = if ($termService) { [ordered]@{ name = $termService.Name; status = $termService.Status.ToString(); startType = $termService.StartType.ToString() } } else { $null }
    fResetBroken      = $fReset
    sessions          = Get-Sessions
    lastRdpcoretsCrash = $lastCrash
    crashCount24h     = $crashCount24h
    checkedAt         = (Get-Date).ToString("o")
} | ConvertTo-Json -Depth 5 -Compress
