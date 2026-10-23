param()
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskNode=Join-Path $taskRoot '.tools/launcher-bootstrap/runtime/node/node.exe'
$taskWorld=Join-Path $taskRoot '.tools/chat-spawn-probe-1792637733010/aoc-chat-spawn-probe-1792637733010'
$taskStage=Join-Path $taskRoot '.tools/beta-cosmetics-wardrobe-local-20261023'
$taskJar=Join-Path $taskStage 'animeoncraft-admission-0.3.0-friends-dev.jar'
$taskProbe=Join-Path $taskStage 'animeoncraft-admission-0.3.0-friends-dev-server-menu-client-probe.jar'
$taskTemp=Join-Path $taskRoot '.tools/work-temp'
foreach($taskFile in @($taskNode,$taskJar,$taskProbe,(Join-Path $taskWorld 'level.dat'))){
 if(-not(Test-Path -LiteralPath $taskFile -PathType Leaf)){throw "Local cosmetic review file missing: $taskFile"}
}
New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
$taskOldTemp=$env:TEMP
$taskOldTmp=$env:TMP
Write-Output 'Local review: eight detailed cosmetic models, level 10, and two example capes in the shared Back slot. All work files and temp files are on F:. No real account or public server is used.'
Push-Location $taskRoot
try {
 $env:TEMP=$taskTemp
 $env:TMP=$taskTemp
 & $taskNode (Join-Path $PSScriptRoot 'test-server-menu-client.mjs') "--world=$taskWorld" "--jar=$taskJar" "--probe-jar=$taskProbe" --review --profile-demo --beta-demo --full-cosmetics
 if($LASTEXITCODE -ne 0){throw 'Cosmetic preview failed; see its menu-client-* folder under .tools.'}
} finally {
 $env:TEMP=$taskOldTemp
 $env:TMP=$taskOldTmp
 Pop-Location
}
