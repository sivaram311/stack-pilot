# Sync site configs from stack-pilot/deployment/conf to the NGINX install, update nginx.conf includes, test, and optionally reload.
# Run as Administrator when NGINX uses port 80.
#
# Usage:
#   .\sync-nginx-config.ps1              # copy + update includes + nginx -t
#   .\sync-nginx-config.ps1 -Reload      # also nginx -s reload if running
#   .\sync-nginx-config.ps1 -WhatIf      # show planned changes only

param(
    [switch]$Reload,
    [switch]$WhatIf
)

$ErrorActionPreference = "Stop"

$StackPilotHome = if ($env:STACK_PILOT_HOME) { $env:STACK_PILOT_HOME } else { "E:\Source\stack-pilot" }
$SourceConfDir  = Join-Path $StackPilotHome "deployment\conf"
$NginxHome      = if ($env:NGINX_HOME) { $env:NGINX_HOME } else { "C:\nginx-1.30.3" }
$NginxConfDir   = Join-Path $NginxHome "conf"
$NginxConfFile  = Join-Path $NginxConfDir "nginx.conf"

$SiteConfigs = @(
    "delena.buzz.conf",
    "control.delena.buzz.conf"
)

Write-Host "=== Sync NGINX site configs ===" -ForegroundColor Cyan
Write-Host "Source : $SourceConfDir"
Write-Host "Target : $NginxConfDir"
Write-Host ""

if (-not (Test-Path $SourceConfDir)) {
    Write-Error "Source config directory not found: $SourceConfDir"
    exit 1
}
if (-not (Test-Path $NginxConfFile)) {
    Write-Error "nginx.conf not found: $NginxConfFile"
    exit 1
}

foreach ($file in $SiteConfigs) {
    $src = Join-Path $SourceConfDir $file
    $dst = Join-Path $NginxConfDir $file
    if (-not (Test-Path $src)) {
        Write-Error "Missing source file: $src"
        exit 1
    }
    if ($WhatIf) {
        Write-Host "[WhatIf] Copy $src -> $dst"
    } else {
        Copy-Item -Path $src -Destination $dst -Force
        Write-Host "Copied $file" -ForegroundColor Green
    }
}

$includeLines = $SiteConfigs | ForEach-Object {
    $path = (Join-Path $NginxConfDir $_) -replace '\\', '/'
    "    include $path;"
}

$confText = Get-Content $NginxConfFile -Raw

# Remove any prior includes for our site configs (any path)
$cleaned = $confText
foreach ($file in $SiteConfigs) {
    $fileEsc = [regex]::Escape($file)
    $cleaned = [regex]::Replace($cleaned, "(?m)^\s*include\s+[^;]*$fileEsc\s*;\s*\r?\n?", "")
}

$block = ($SiteConfigs | ForEach-Object {
    $path = (Join-Path $NginxConfDir $_) -replace '\\', '/'
    "    include $path;"
}) -join "`n"

$updated = $cleaned
if ($cleaned -match '(?m)^(\s*)server\s*\{') {
    $updated = [regex]::Replace(
        $cleaned,
        '(?m)^(\s*)server\s*\{',
        "$block`n`n`$0",
        1
    )
    Write-Host "Inserted site includes before first server block" -ForegroundColor Green
} else {
    Write-Warning "Could not find server block in nginx.conf - append manually:"
    Write-Host $block
}

if ($updated -ne $confText) {
    if ($WhatIf) {
        Write-Host "[WhatIf] Update include paths in nginx.conf"
    } else {
        Set-Content -Path $NginxConfFile -Value $updated -NoNewline
        Write-Host "Updated include paths in nginx.conf" -ForegroundColor Green
    }
} else {
    Write-Host "nginx.conf include paths already correct."
}

if ($WhatIf) {
    Write-Host "WhatIf complete - no changes applied."
    exit 0
}

Set-Location $NginxHome
& .\nginx.exe -t
if ($LASTEXITCODE -ne 0) {
    Write-Error "nginx -t failed after sync. Fix configs before reloading."
    exit 1
}
Write-Host "nginx -t OK" -ForegroundColor Green

if ($Reload) {
    $running = Get-Process nginx -ErrorAction SilentlyContinue
    if ($running) {
        & .\nginx.exe -s reload
        Write-Host "nginx reloaded." -ForegroundColor Green
    } else {
        Write-Warning "nginx is not running. Start with deployment/scripts/start-nginx.ps1"
    }
} else {
    Write-Host "Config synced. Run with -Reload to apply on a running nginx, or restart nginx."
}
