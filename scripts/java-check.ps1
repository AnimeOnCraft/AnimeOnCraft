$ErrorActionPreference = 'Stop'
$integrationRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\minecraft\integration'))
$javaHomeCandidate = $env:JAVA_HOME
if (-not $javaHomeCandidate -or -not (Test-Path -LiteralPath (Join-Path $javaHomeCandidate 'bin\javac.exe'))) {
  $temurin = Get-ChildItem -LiteralPath 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
  if (-not $temurin) { throw 'JDK 21 not found. Install Eclipse Temurin JDK 21.' }
  $javaHomeCandidate = $temurin.FullName
}
$env:JAVA_HOME = $javaHomeCandidate
& (Join-Path $integrationRoot 'gradlew.bat') --project-dir $integrationRoot --no-daemon clean test jar
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
