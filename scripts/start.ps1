#Requires -Version 5.1
<#
.SYNOPSIS
  Release/deploy start script for Stack Pilot (copy to F:\apps\stack-pilot or G:\apps\stack-pilot on promote).

.NOTES
  - prod  → G:\apps\stack-pilot :5091, spring.profiles.active=prod (G: managed apps)
  - preprod → F:\apps\stack-pilot :4091, spring.profiles.active=dev (grok_dev; auto-start off)
  Local mvn spring-boot:run uses spring.profiles.default=dev from application.yml (no flag needed).
#>
param(
  [ValidateSet('preprod', 'prod')]
  [string]$EnvName = 'preprod'
)
$ErrorActionPreference = 'Stop'
$HomeDir = if ($EnvName -eq 'prod') { 'G:\apps\stack-pilot' } else { 'F:\apps\stack-pilot' }
$Port = if ($EnvName -eq 'prod') { 5091 } else { 4091 }
$Profile = if ($EnvName -eq 'prod') { 'prod' } else { 'dev' }
$Java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe'
if (-not (Test-Path $Java)) {
  $Java = (Get-Command java -ErrorAction Stop).Source
}
$Jar = Join-Path $HomeDir 'stack-pilot.jar'
$EnvFile = Join-Path $HomeDir '.env'
if (-not (Test-Path $Jar)) { throw "Missing $Jar" }
if (-not (Test-Path $EnvFile)) { throw "Missing $EnvFile" }

if (netstat -ano | findstr 'LISTENING' | findstr ":$Port ") {
  Write-Host "Already listening on :$Port"
  exit 0
}

Get-Content $EnvFile | ForEach-Object {
  $line = $_.Trim()
  if (-not $line -or $line.StartsWith('#')) { return }
  $i = $line.IndexOf('=')
  if ($i -lt 1) { return }
  Set-Item -Path "env:$($line.Substring(0, $i).Trim())" -Value $line.Substring($i + 1).Trim()
}

# Scripts (RDP, run-app-foreground) live in source unless overridden
if (-not $env:STACKPILOT_SCRIPTS_HOME) {
  $env:STACKPILOT_SCRIPTS_HOME = 'E:\Source\stack-pilot'
}

$outLog = Join-Path $HomeDir "stack-pilot-$EnvName.out.log"
$errLog = Join-Path $HomeDir "stack-pilot-$EnvName.err.log"
$argList = @(
  '-jar', $Jar,
  "--server.port=$Port",
  "--spring.profiles.active=$Profile",
  "--stackpilot.logs-dir=$HomeDir\logs",
  "--stackpilot.rdp.scripts-home=$($env:STACKPILOT_SCRIPTS_HOME)"
)
# Preprod never auto-starts managed stack (prod is sole control plane after cutover).
# Prod enables boot auto-start so G: owns nginx/services/RDP mitigations.
if ($EnvName -eq 'preprod') {
  $argList += @(
    '--stackpilot.boot.auto-start-services=false',
    '--stackpilot.boot.auto-start-nginx=false',
    '--stackpilot.boot.auto-apply-rdp-mitigations=false'
  )
}

Write-Host "Starting stack-pilot $EnvName on :$Port (profile=$Profile)"
New-Item -ItemType Directory -Force -Path (Join-Path $HomeDir 'logs') | Out-Null
Start-Process -FilePath $Java -ArgumentList $argList -WorkingDirectory $HomeDir `
  -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden
Start-Sleep -Seconds 10
if (netstat -ano | findstr 'LISTENING' | findstr ":$Port ") {
  Write-Host "UP on :$Port"
  exit 0
}
Write-Host "Failed to bind :$Port - see $errLog"
Get-Content $errLog -Tail 40 -ErrorAction SilentlyContinue
exit 1
