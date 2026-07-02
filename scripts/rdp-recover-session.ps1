# Recover stuck RDP sessions: log off active user RDP sessions and restart TermService.
# Run as Administrator.
param(
    [int]$SessionId = 0,
    [switch]$WhatIf
)

$ErrorActionPreference = "Stop"

$result = [ordered]@{
    success          = $false
    message          = ""
    sessionsLoggedOff = @()
    termServiceRestarted = $false
}

function Get-RdpSessionIds {
    $ids = @()
    $raw = query session 2>&1
    foreach ($line in $raw) {
        if ($line -match 'rdp-tcp#\d+' -and $line -match '\s+(\d+)\s+') {
            $id = [int]$Matches[1]
            if ($id -gt 0) { $ids += $id }
        }
    }
    return $ids | Select-Object -Unique
}

try {
    $targetIds = if ($SessionId -gt 0) { @($SessionId) } else { Get-RdpSessionIds }

    foreach ($id in $targetIds) {
        if ($WhatIf) {
            $result.sessionsLoggedOff += $id
            continue
        }
        $proc = Start-Process -FilePath "logoff" -ArgumentList $id -Wait -PassThru -NoNewWindow
        if ($proc.ExitCode -eq 0) {
            $result.sessionsLoggedOff += $id
        }
    }

    Start-Sleep -Seconds 2

    if ($WhatIf) {
        $result.termServiceRestarted = $true
        $result.success = $true
        $result.message = "WhatIf: would restart TermService after logging off $($targetIds -join ', ')"
    } else {
        Restart-Service TermService -Force
        Start-Sleep -Seconds 2
        $svc = Get-Service TermService
        $result.termServiceRestarted = $true
        if ($svc.Status -eq "Running") {
            $result.success = $true
            $logged = if ($result.sessionsLoggedOff.Count) { "Logged off sessions: $($result.sessionsLoggedOff -join ', '). " } else { "No RDP sessions to log off. " }
            $result.message = "${logged}TermService restarted successfully."
        } else {
            $result.success = $false
            $result.message = "TermService restart completed but status is $($svc.Status)"
        }
    }
} catch {
    $result.success = $false
    $result.message = $_.Exception.Message
}

$result | ConvertTo-Json -Compress
if (-not $result.success) { exit 1 }
