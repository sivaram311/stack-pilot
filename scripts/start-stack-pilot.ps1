# Start Stack Pilot from a packaged JAR (used by Windows Task Scheduler at boot)
$ErrorActionPreference = "Stop"

$StackPilotHome = if ($env:STACK_PILOT_HOME) { $env:STACK_PILOT_HOME } else { "E:\Source\stack-pilot" }
Set-Location $StackPilotHome

function Find-Java {
    $candidates = @("java.exe", "javaw.exe")
    foreach ($c in $candidates) {
        $found = Get-Command $c -ErrorAction SilentlyContinue
        if ($found -and (Test-Path $found.Source)) { return $found.Source }
    }
    return $null
}

function Find-StackPilotJar {
    $jars = Get-ChildItem -Path (Join-Path $StackPilotHome "target") -Filter "stack-pilot-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc|original' } |
        Sort-Object LastWriteTime -Descending
    return $jars | Select-Object -First 1
}

# Skip if already listening on 8091
$listening = netstat -ano 2>$null | Select-String ":8091\s+.*LISTENING"
if ($listening) {
    Write-Host "Stack Pilot already listening on port 8091."
    exit 0
}

$java = Find-Java
if (-not $java) {
    Write-Error "java.exe not found in PATH. Install Java 21+ and add it to PATH."
    exit 1
}

$jar = Find-StackPilotJar
if (-not $jar) {
    Write-Host "JAR not found — building with Maven..."
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvn) {
        Write-Error "Maven not found. Run: cd $StackPilotHome; mvn package -DskipTests"
        exit 1
    }
    & mvn -q package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Maven package failed."
        exit 1
    }
    $jar = Find-StackPilotJar
}

if (-not $jar) {
    Write-Error "Could not locate stack-pilot JAR under $StackPilotHome\target"
    exit 1
}

Write-Host "Starting Stack Pilot: $java -jar $($jar.FullName)"
Start-Process -FilePath $java -ArgumentList "-jar", $jar.FullName -WorkingDirectory $StackPilotHome -WindowStyle Hidden
Start-Sleep -Seconds 3

$check = netstat -ano 2>$null | Select-String ":8091\s+.*LISTENING"
if ($check) {
    Write-Host "Stack Pilot started on port 8091."
    exit 0
}

Write-Warning "Stack Pilot process launched but port 8091 is not listening yet. Check logs under $StackPilotHome\logs"
exit 0
