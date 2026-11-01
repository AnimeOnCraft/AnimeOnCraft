param([switch]$NoPlay)
# One-click local gacha check: starts whatever is not running yet (Docker database, local API, local game server)
# and then opens the local launcher through start-gacha-local.ps1. Nothing here touches the VPS.
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Listening($port){[bool](Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)}
function WaitFor($port,$name,$seconds){for($i=0;$i -lt $seconds;$i++){if(Listening $port){return};Start-Sleep 1};throw "$name did not start in $seconds s (port $port)."}

if(-not(Listening 54329)){
 Write-Output 'Starting the local database (Docker)...'
 if(-not(Get-Process 'Docker Desktop' -ErrorAction SilentlyContinue)){Start-Process "$env:LOCALAPPDATA\Programs\DockerDesktop\Docker Desktop.exe"}
 # Through cmd, so Docker's stderr warnings don't stop the script under ErrorActionPreference=Stop.
 for($i=0;$i -lt 120;$i++){cmd /c 'docker info >nul 2>&1';if($LASTEXITCODE -eq 0){break};Start-Sleep 2}
 cmd /c 'docker start animeoncraft-dev-postgres-1 animeoncraft-dev-valkey-1 >nul 2>&1'
 WaitFor 54329 'Database' 60
}
if(-not(Listening 3000)){
 Write-Output 'Starting the local API...'
 Start-Process powershell -WindowStyle Hidden -WorkingDirectory $root -ArgumentList '-NoProfile','-Command',"corepack pnpm@12.10.1 run db:migrate *> .tools\gacha-local-migrate.log; node scripts/dev-backend-auth.mjs *> .tools\gacha-local-api.log"
 WaitFor 3000 'API' 180
}
if(-not(Listening 25565)){
 Write-Output 'Starting the local game server (1-2 minutes)...'
 Start-Process powershell -WindowStyle Minimized -WorkingDirectory (Join-Path $root '.tools\gacha-local\server') -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $root '.tools\gacha-local\start-server.ps1')
 WaitFor 25565 'Game server' 300
}
if($NoPlay){return}
# The local launcher build demands the released mod JAR, so the client is started directly, as the launcher would,
# with its own ticket bridge and the local review account (record-gacha-video.mjs --play).
if(Get-Process | Where-Object ProcessName -Like 'animeoncraft*'){throw 'Close all AnimeOnCraft launcher windows first.'}
Push-Location $root
try{node scripts/record-gacha-video.mjs --play}finally{Pop-Location}
