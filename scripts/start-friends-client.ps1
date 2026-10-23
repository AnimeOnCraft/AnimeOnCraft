param([switch]$PrepareOnly)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$node=Join-Path $root 'runtime\node\node.exe'
$env:JAVA_HOME=Join-Path $root 'runtime\java'
$env:Path="$env:JAVA_HOME\bin;"+$env:Path
if(-not(Test-Path -LiteralPath $node)){throw 'Extract the entire AnimeOnCraft archive first.'}
Push-Location $root
try{
 $arguments=@('scripts\prepare-friends-client.mjs');if($PrepareOnly){$arguments+='--prepare-only'}
 & $node @arguments
 if($LASTEXITCODE -ne 0){throw 'Client preparation failed. Keep this window output and retry.'}
 if(-not $PrepareOnly){& (Join-Path $root 'AnimeOnCraft.exe')}
}finally{Pop-Location}
