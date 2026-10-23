param([ValidateSet('build','test')][string]$Task = 'build')
$ErrorActionPreference = 'Stop'
$aocRoot = Split-Path -Parent $PSScriptRoot
$aocJava = (Get-Command java -ErrorAction Stop).Source
$aocJavaDirectory = Split-Path -Parent (Split-Path -Parent $aocJava)
if (!(Test-Path -LiteralPath (Join-Path $aocJavaDirectory 'release')) -or
    !(Select-String -LiteralPath (Join-Path $aocJavaDirectory 'release') -Pattern '^JAVA_VERSION="21\.' -Quiet)) {
    throw 'Java 21 must be available on PATH.'
}
$env:JAVA_HOME = $aocJavaDirectory
$env:GRADLE_USER_HOME = Join-Path $aocRoot '.tools/gradle-user-home'
$aocGradle = Join-Path $aocRoot '.tools/gradle-9.8.1/bin/gradle.bat'
if (!(Test-Path -LiteralPath $aocGradle)) { $aocGradle = Join-Path $aocRoot 'minecraft/integration/gradlew.bat' }
& $aocGradle -p (Join-Path $aocRoot 'minecraft/integration') $Task --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Minecraft integration build failed.' }
