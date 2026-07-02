# Apply RDP session recovery registry mitigations (idempotent).
# Run as Administrator.
param([switch]$WhatIf)

$ErrorActionPreference = "Stop"
$RegPath = "HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp"

$result = [ordered]@{
    success       = $false
    fResetBroken  = $null
    message       = ""
    changed       = $false
}

try {
    $current = (Get-ItemProperty -Path $RegPath -Name fResetBroken -ErrorAction SilentlyContinue).fResetBroken
    $result.fResetBroken = $current

    if ($current -eq 1) {
        $result.success = $true
        $result.message = "fResetBroken already enabled"
        $result.changed = $false
    } elseif ($WhatIf) {
        $result.success = $true
        $result.message = "WhatIf: would set fResetBroken=1"
        $result.changed = $true
    } else {
        Set-ItemProperty -Path $RegPath -Name fResetBroken -Value 1 -Type DWord
        $result.fResetBroken = 1
        $result.success = $true
        $result.message = "fResetBroken set to 1"
        $result.changed = $true
    }
} catch {
    $result.success = $false
    $result.message = $_.Exception.Message
}

$result | ConvertTo-Json -Compress

if (-not $result.success) { exit 1 }
