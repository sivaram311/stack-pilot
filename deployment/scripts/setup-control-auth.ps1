# Create NGINX basic-auth credentials for control.delena.buzz
# Run as Administrator.
param(
    [string]$Username = "admin",
    [string]$Password = "",
    [string]$NginxHome = $(if ($env:NGINX_HOME) { $env:NGINX_HOME } else { "C:\nginx-1.30.3" })
)

$ErrorActionPreference = "Stop"

if (-not $Password) {
    $secure = Read-Host "Password for NGINX user '$Username'" -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $Password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

if (-not $Password) { Write-Error "Password is required"; exit 1 }

$htpasswdPath = Join-Path $NginxHome "conf\.htpasswd-control"
$confDir = Split-Path $htpasswdPath -Parent
if (-not (Test-Path $confDir)) { New-Item -ItemType Directory -Path $confDir -Force | Out-Null }

function Get-Apr1Hash([string]$plain) {
    $candidates = @(
        (Get-Command openssl -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source),
        "C:\Program Files\Git\usr\bin\openssl.exe",
        "C:\Program Files (x86)\Git\usr\bin\openssl.exe"
    ) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique

    foreach ($openssl in $candidates) {
        $hash = & $openssl passwd -apr1 $plain 2>$null
        if ($LASTEXITCODE -eq 0 -and $hash) { return $hash.Trim() }
    }
    throw "openssl not found. Install Git for Windows or OpenSSL and ensure openssl is on PATH."
}

$hash = Get-Apr1Hash $Password
"$Username`:$hash" | Set-Content -Path $htpasswdPath -Encoding ASCII -NoNewline
Write-Host "Wrote $htpasswdPath" -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "  E:\Source\stack-pilot\deployment\scripts\sync-nginx-config.ps1 -Reload"
Write-Host ""
Write-Host "Login at http://control.delena.buzz/ with user: $Username"
