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
        $trimmed = $line.TrimStart()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("SESSIONNAME")) { continue }
        
        $cleanLine = $line.Replace(">", " ")
        
        # Determine if the session name column is empty by counting leading spaces
        $firstCharIndex = 0
        while ($firstCharIndex -lt $cleanLine.Length -and $cleanLine[$firstCharIndex] -eq ' ') {
            $firstCharIndex++
        }
        
        $tokens = $cleanLine.Trim() -split '\s+'
        if ($tokens.Count -lt 3) { continue }
        
        $sessionName = ""
        $username = ""
        $id = 0
        $state = ""
        
        if ($firstCharIndex -ge 10) {
            # sessionName is empty (e.g. disconnected user session)
            $username = $tokens[0]
            if ($tokens[1] -match '^\d+$') {
                $id = [int]$tokens[1]
                $state = $tokens[2]
            } else {
                continue
            }
        } else {
            $sessionName = $tokens[0]
            # Check if second token is a numeric ID
            if ($tokens[1] -match '^\d+$') {
                $username = ""
                $id = [int]$tokens[1]
                $state = $tokens[2]
            } elseif ($tokens.Count -ge 4 -and $tokens[2] -match '^\d+$') {
                $username = $tokens[1]
                $id = [int]$tokens[2]
                $state = $tokens[3]
            } else {
                continue
            }
        }
        
        # For disconnected RDP sessions, session name is empty but it's an RDP session.
        # Set sessionName to rdp-tcp#<id> to help frontend identify it as RDP session.
        if ([string]::IsNullOrEmpty($sessionName) -and $id -gt 1 -and $id -ne 65536) {
            $sessionName = "rdp-tcp#$id"
        }
        
        $sessions += [ordered]@{
            sessionName = $sessionName
            username    = $username
            id          = $id
            state       = $state
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
