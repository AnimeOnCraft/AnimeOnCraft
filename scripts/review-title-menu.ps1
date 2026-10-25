$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$node=Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
if(-not(Test-Path -LiteralPath $node)){$node=(Get-Command node -ErrorAction Stop).Source}
$script=Join-Path $PSScriptRoot 'test-title-menu-client.mjs'
# The review has its own game directory and deliberately targets loopback, without account credentials.
Start-Process -FilePath $node -ArgumentList @(('"'+$script+'"'),'--review') -WorkingDirectory $workspace -WindowStyle Hidden
