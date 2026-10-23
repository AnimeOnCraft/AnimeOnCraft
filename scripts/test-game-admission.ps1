$ErrorActionPreference = 'Stop'
$aocRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'build-minecraft-integration.ps1')
$aocPreviousProbe = $env:AOC_GAME_NATIVE_PROBE
try {
    $env:AOC_GAME_NATIVE_PROBE = 'true'
    & corepack pnpm@12.10.1 --dir $aocRoot run test:auth
    if ($LASTEXITCODE -ne 0) { throw 'Minecraft admission acceptance failed; inspect .tools/game-admission-smoke logs.' }
} finally {
    $env:AOC_GAME_NATIVE_PROBE = $aocPreviousProbe
}
