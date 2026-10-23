param()
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskNode=Join-Path $taskRoot '.tools/launcher-bootstrap/runtime/node/node.exe'
$taskWorld=Join-Path $taskRoot '.tools/chat-spawn-probe-1792637733010/aoc-chat-spawn-probe-1792637733010'
$taskStage=Join-Path $taskRoot '.tools/beta-pass-icons-local-20261022'
$taskJar=Join-Path $taskStage 'animeoncraft-admission-0.3.0-friends-dev.jar'
$taskProbe=Join-Path $taskStage 'animeoncraft-admission-0.3.0-friends-dev-server-menu-client-probe.jar'
foreach($taskFile in @($taskNode,$taskJar,$taskProbe,(Join-Path $taskWorld 'level.dat'))){if(-not(Test-Path -LiteralPath $taskFile -PathType Leaf)){throw "Local beta-pass review file missing: $taskFile"}}
Write-Output 'Isolated beta demo: example level 10 and all cosmetics. No public account, VPS or main world is used.'
Push-Location $taskRoot
try{
 & $taskNode (Join-Path $PSScriptRoot 'test-server-menu-client.mjs') "--world=$taskWorld" "--jar=$taskJar" "--probe-jar=$taskProbe" --review --profile-demo --beta-demo
 if($LASTEXITCODE -ne 0){throw 'Beta-pass preview failed; see the new menu-client-* folder under .tools.'}
}finally{Pop-Location}
