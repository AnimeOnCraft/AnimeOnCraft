param([ValidateRange(1024, 16384)][int]$MemoryMiB = 2048)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -ErrorAction Stop
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$server = Join-Path $workspace '.tools\modpack-mvp\server'
$profile = Get-Content -LiteralPath (Join-Path $workspace 'minecraft\modpack\profile.json') -Raw | ConvertFrom-Json
$argumentsFile = Join-Path $server "libraries\net\neoforged\neoforge\$($profile.loader.version)\win_args.txt"
if (-not (Test-Path -LiteralPath $argumentsFile)) { throw 'NeoForge server is not installed. See MODPACK_RUNTIME.md.' }
if (-not ((Get-Content -LiteralPath (Join-Path $server 'eula.txt') -Raw) -match '(?m)^eula=true\s*$')) { throw 'The server owner must accept the Minecraft EULA before starting.' }
$properties = Get-Content -LiteralPath (Join-Path $server 'server.properties') -Raw
if (-not (Test-Path -LiteralPath (Join-Path $server 'config\animeoncraft-clan-spawns.json') -PathType Leaf)) { throw 'Clan territory configuration is missing; refusing unprotected startup.' }
foreach($key in @('server-ip','server-port','online-mode','enforce-secure-profile','white-list','enforce-whitelist')){
 if([regex]::Matches($properties,"(?m)^$key=").Count -ne 1){throw 'Duplicate or missing critical server property.'}
}
if($properties -notmatch '(?m)^online-mode=false\s*$' -or $properties -notmatch '(?m)^enforce-secure-profile=false\s*$'){throw 'Keep the prepared own-account server mode.'}
if ($properties -notmatch '(?m)^server-ip=127\.0\.0\.1\s*$' -or $properties -notmatch '(?m)^server-port=25565\s*$') { throw 'The local server must listen only on 127.0.0.1:25565.' }
$gameConfig=Join-Path $workspace '.tools\friends-game-server.json'
$ownedJar=Join-Path $server 'mods\animeoncraft-admission-0.3.0-friends-dev.jar'
$game=$null
if(Test-Path -LiteralPath $gameConfig){
 if((Get-Item -LiteralPath $gameConfig).Length -gt 1024){throw 'Invalid local game configuration size.'}
 try{$game=Get-Content -LiteralPath $gameConfig -Raw | ConvertFrom-Json}catch{throw 'Invalid local game configuration JSON.'}
 if($game.format_version -ne 1 -or $game.server_id -cnotmatch '^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$' -or $game.server_key -cnotmatch '^[a-f0-9]{64}$'){throw 'Invalid local game configuration.'}
 $builtJar=Join-Path $workspace 'minecraft\integration\neoforge\build\libs\animeoncraft-admission-0.3.0-friends-dev.jar'
 if(-not(Test-Path -LiteralPath $ownedJar) -or -not(Test-Path -LiteralPath $builtJar)){throw 'Prepare the local account admission mod first.'}
 if((Get-FileHash -LiteralPath $ownedJar -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $builtJar -Algorithm SHA256).Hash){throw 'Installed admission mod differs from the local build.'}
 if($properties -notmatch '(?m)^white-list=false\s*$' -or $properties -notmatch '(?m)^enforce-whitelist=false\s*$'){throw 'Run prepare:minecraft:local to finish switching admission.'}
}elseif((Test-Path -LiteralPath $ownedJar) -or $properties -notmatch '(?m)^white-list=true\s*$'){throw 'Keep the vanilla whitelist until local account admission is fully prepared.'}
$javaExecutable = (Get-Command java -ErrorAction Stop).Source
$version = & $javaExecutable --version | Out-String
if ($version -notmatch '(?m)^(?:openjdk|java) 21\.') { throw 'This modpack requires Java 21 x64.' }
# Consistent private backups only while Minecraft is stopped.
& node (Join-Path $workspace 'scripts\backup-friends-server.mjs')
if($LASTEXITCODE -ne 0){throw 'Pre-start backup failed; server was not started.'}
$cleanStop=$false
Push-Location -LiteralPath $server
$previousId=$env:AOC_GAME_SERVER_ID;$previousKey=$env:AOC_GAME_SERVER_KEY;$previousUrl=$env:AOC_GAME_REDEEM_URL
try {
  if($game){$env:AOC_GAME_SERVER_ID=$game.server_id;$env:AOC_GAME_SERVER_KEY=$game.server_key;$env:AOC_GAME_REDEEM_URL='http://127.0.0.1:3000/api/v1/game/admission/redeem'}
  $serverArguments = "@libraries/net/neoforged/neoforge/$($profile.loader.version)/win_args.txt"
  & $javaExecutable "-Xms512m" "-Xmx$($MemoryMiB)m" "-Djava.io.tmpdir=$(Join-Path $workspace '.tools\modpack-mvp\tmp')" $serverArguments 'nogui'
  if ($LASTEXITCODE -ne 0) { throw "Minecraft server exited with code $LASTEXITCODE" }
  $cleanStop=$true
} finally { $env:AOC_GAME_SERVER_ID=$previousId;$env:AOC_GAME_SERVER_KEY=$previousKey;$env:AOC_GAME_REDEEM_URL=$previousUrl;Pop-Location }

if($cleanStop){
 & node (Join-Path $workspace 'scripts\backup-friends-server.mjs')
 if($LASTEXITCODE -ne 0){throw 'Server stopped cleanly, but the final backup failed. Check free space and PostgreSQL.'}
}
