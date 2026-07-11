#Requires -Version 5.1
<#
.SYNOPSIS
  Load AppHome\.env and run a process in the foreground (for Stack Pilot ProcessBuilder).

.DESCRIPTION
  Unlike app start.ps1 scripts (Start-Process + exit), this keeps the child attached so
  Stack Pilot owns a live PID tree. Stop via port kill / process-tree still works.

  Do not put secrets in YAML — they come from each app's .env on disk.
#>
param(
  [Parameter(Mandatory = $true)]
  [string]$AppHome,

  [string]$Java = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe',

  # Jar mode (java -jar)
  [string]$Jar,

  # Extra java args (repeatable) — prefer over a quoted -JavaArgs string through cmd /c
  [string[]]$ExtraArg = @(),

  # Legacy: single string split on whitespace (avoid if calling via Stack Pilot cmd /c)
  [string]$JavaArgs = '',

  # Alternate: full command line after env load (e.g. "node server.js") — not for java jars
  [string]$CommandLine,

  # CSS start-prod.ps1 remaps CSS_* secrets → Spring datasource / seed vars
  [switch]$MapCssEnv
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $AppHome)) {
  throw "AppHome not found: $AppHome"
}
Set-Location -LiteralPath $AppHome

$EnvFile = Join-Path $AppHome '.env'
if (Test-Path -LiteralPath $EnvFile) {
  Get-Content -LiteralPath $EnvFile | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith('#')) { return }
    $i = $line.IndexOf('=')
    if ($i -lt 1) { return }
    $name = $line.Substring(0, $i).Trim()
    $value = $line.Substring($i + 1).Trim()
    Set-Item -Path "env:$name" -Value $value
  }
}

if ($MapCssEnv) {
  if ($env:CSS_JDBC_URL) { $env:SPRING_DATASOURCE_URL = $env:CSS_JDBC_URL }
  if ($env:CSS_DB_USER) { $env:SPRING_DATASOURCE_USERNAME = $env:CSS_DB_USER }
  if ($env:CSS_DB_PASSWORD) { $env:SPRING_DATASOURCE_PASSWORD = $env:CSS_DB_PASSWORD }
  if ($env:CSS_ADMIN_PASSWORD) { $env:CSS_SEED_ADMIN_PASSWORD = $env:CSS_ADMIN_PASSWORD }
  if ($env:CSS_DEMO_PASSWORD) { $env:CSS_SEED_DEMO_PASSWORD = $env:CSS_DEMO_PASSWORD }
}

if ($CommandLine) {
  # Keep process in-process for Stack Pilot PID tracking (cmd /c waits for child)
  $p = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', $CommandLine) `
    -WorkingDirectory $AppHome -NoNewWindow -Wait -PassThru
  exit $p.ExitCode
}

if (-not $Jar) {
  throw 'Specify -Jar (java mode) or -CommandLine'
}

if (-not (Test-Path -LiteralPath $Java)) {
  $Java = (Get-Command java -ErrorAction Stop).Source
}

$jarPath = Join-Path $AppHome $Jar
if (-not (Test-Path -LiteralPath $jarPath)) {
  throw "Missing jar: $jarPath"
}

$argList = [System.Collections.Generic.List[string]]::new()
$argList.Add('-jar')
$argList.Add($jarPath)
foreach ($a in $ExtraArg) {
  if ($a) { $argList.Add($a) }
}
if ($JavaArgs) {
  foreach ($token in ($JavaArgs.Trim() -split '\s+')) {
    if ($token) { $argList.Add($token) }
  }
}

# If profile not passed, honor SPRING_PROFILES_ACTIVE from .env (agent-portal pattern)
$hasProfile = $false
foreach ($a in $argList) {
  if ($a -like '--spring.profiles.active=*') { $hasProfile = $true; break }
}
if (-not $hasProfile -and $env:SPRING_PROFILES_ACTIVE) {
  $argList.Add("--spring.profiles.active=$($env:SPRING_PROFILES_ACTIVE)")
}

Write-Host "Foreground: $Java $($argList -join ' ') (cwd=$AppHome)"
# Call operator keeps java as child of this powershell; stdout inherits to Stack Pilot log capture
& $Java @argList
exit $LASTEXITCODE
