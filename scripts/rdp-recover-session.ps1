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
        # Set sessionName to rdp-tcp#$id to help frontend/scripts identify it.
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

function Get-RdpSessionIds {
    $sessions = Get-Sessions
    $ids = @()
    foreach ($s in $sessions) {
        if ($s.id -gt 1 -and $s.id -ne 65536 -and ($s.sessionName -like "*rdp*")) {
            $ids += $s.id
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
