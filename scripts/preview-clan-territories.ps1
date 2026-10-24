param()
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskNode=Join-Path $taskRoot '.tools/launcher-bootstrap/runtime/node/node.exe'
$taskWorld=Join-Path $taskRoot '.tools/chat-spawn-probe-1792637733010/aoc-chat-spawn-probe-1792637733010'
$taskJar=Join-Path $taskRoot '.tools/territories-20261023/animeoncraft-admission-0.3.0-friends-dev.jar'
foreach($taskFile in @($taskNode,$taskJar,(Join-Path $taskWorld 'level.dat'))){
 if(-not(Test-Path -LiteralPath $taskFile -PathType Leaf)){throw "Local territory review file missing: $taskFile"}
}
$env:TEMP=Join-Path $taskRoot '.tools/work-temp'
$env:TMP=$env:TEMP
Push-Location $taskRoot
try{
 & $taskNode (Join-Path $PSScriptRoot 'test-ingame-atlas-client.mjs') "--world=$taskWorld" "--jar=$taskJar" --review
 if($LASTEXITCODE -ne 0){throw 'Local territory review failed; inspect .tools/atlas-client-probe.log'}
}finally{Pop-Location}
