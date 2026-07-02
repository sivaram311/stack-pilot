# Show nginx process and port status
param(
    [string]$NginxHome = $(if ($env:NGINX_HOME) { $env:NGINX_HOME } else { "C:\nginx-1.30.3" })
)

Write-Host "=== nginx processes ==="
Get-Process nginx -ErrorAction SilentlyContinue | Format-Table Id, ProcessName, StartTime -AutoSize

Write-Host "=== configuration test ==="
Set-Location $NginxHome
& .\nginx.exe -t 2>&1

Write-Host "=== port 80 listeners ==="
netstat -ano | findstr ":80 "

Write-Host "=== HTTP check (localhost) ==="
try {
    $response = Invoke-WebRequest -Uri "http://localhost/" -UseBasicParsing -TimeoutSec 5
    Write-Host "http://localhost/ -> $($response.StatusCode) $($response.StatusDescription)"
} catch {
    Write-Warning "Could not reach http://localhost/ : $($_.Exception.Message)"
}

Write-Host "=== HTTP check (delena.buzz proxy) ==="
try {
    $headers = @{ Host = "delena.buzz" }
    $response = Invoke-WebRequest -Uri "http://127.0.0.1/" -Headers $headers -UseBasicParsing -TimeoutSec 5
    Write-Host "http://delena.buzz/ (via proxy) -> $($response.StatusCode) $($response.StatusDescription)"
} catch {
    Write-Warning "Could not reach delena.buzz proxy : $($_.Exception.Message)"
}

Write-Host "=== HTTP check (control.delena.buzz proxy) ==="
try {
    $headers = @{ Host = "control.delena.buzz" }
    $response = Invoke-WebRequest -Uri "http://127.0.0.1/" -Headers $headers -UseBasicParsing -TimeoutSec 5
    Write-Host "http://control.delena.buzz/ (via proxy) -> $($response.StatusCode) $($response.StatusDescription)"
} catch {
    Write-Warning "Could not reach control.delena.buzz proxy : $($_.Exception.Message)"
}

Write-Host "=== frontend (port 4200) ==="
$frontend = netstat -ano | findstr ":4200 " | findstr "LISTENING"
if ($frontend) {
    Write-Host "App listening on port 4200"
} else {
    Write-Warning "Nothing listening on port 4200 - delena.buzz reverse proxy will return 502"
}

Write-Host "=== Stack Pilot (port 8091) ==="
$stackPilot = netstat -ano | findstr ":8091 " | findstr "LISTENING"
if ($stackPilot) {
    Write-Host "Stack Pilot listening on port 8091"
} else {
    Write-Warning "Nothing listening on port 8091 - control.delena.buzz reverse proxy will return 502"
}
