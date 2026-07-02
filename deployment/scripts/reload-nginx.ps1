# Reload nginx configuration without dropping connections
param(
    [string]$NginxHome = $(if ($env:NGINX_HOME) { $env:NGINX_HOME } else { "C:\nginx-1.30.3" })
)

Set-Location $NginxHome

$existing = Get-Process nginx -ErrorAction SilentlyContinue
if (-not $existing) {
    Write-Error "nginx is not running. Start it first with start-nginx.ps1"
    exit 1
}

& .\nginx.exe -t
if ($LASTEXITCODE -ne 0) {
    Write-Error "Configuration test failed. Reload aborted."
    exit 1
}

& .\nginx.exe -s reload
Write-Host "nginx configuration reloaded."
