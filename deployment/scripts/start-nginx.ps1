# Start nginx (Windows)
param(
    [string]$NginxHome = $(if ($env:NGINX_HOME) { $env:NGINX_HOME } else { "C:\nginx-1.30.3" })
)

Set-Location $NginxHome

& .\nginx.exe -t
if ($LASTEXITCODE -ne 0) {
    Write-Error "Configuration test failed. Fix nginx.conf before starting."
    exit 1
}

$existing = Get-Process nginx -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "nginx is already running (PIDs: $($existing.Id -join ', '))."
    exit 0
}

Start-Process -FilePath ".\nginx.exe" -WorkingDirectory $NginxHome -WindowStyle Hidden
Start-Sleep -Seconds 1

$processes = Get-Process nginx -ErrorAction SilentlyContinue
if ($processes) {
    Write-Host "nginx started successfully."
    Write-Host "  URL:  http://localhost/"
    Write-Host "  PIDs: $($processes.Id -join ', ')"
} else {
    Write-Error "nginx failed to start. Check $NginxHome\logs\error.log"
    exit 1
}
