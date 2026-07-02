# Stop nginx gracefully (Windows)
param(
    [string]$NginxHome = $(if ($env:NGINX_HOME) { $env:NGINX_HOME } else { "C:\nginx-1.30.3" })
)

Set-Location $NginxHome

$existing = Get-Process nginx -ErrorAction SilentlyContinue
if (-not $existing) {
    Write-Host "nginx is not running."
    exit 0
}

& .\nginx.exe -s quit
Start-Sleep -Seconds 2

$remaining = Get-Process nginx -ErrorAction SilentlyContinue
if ($remaining) {
    Write-Warning "nginx did not stop gracefully. Remaining PIDs: $($remaining.Id -join ', ')"
    exit 1
}

Write-Host "nginx stopped."
