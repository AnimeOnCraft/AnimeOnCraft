param()
# Local gacha review: the local launcher build (API 127.0.0.1:3000) plays on the local server 127.0.0.1:25565
# from the separate client copy in .tools/gacha-local. The public launcher runtime is restored on exit.
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$exe=Join-Path $root '.tools\gacha-local\launcher-target\release\animeoncraft-launcher.exe'
$client=Join-Path $root '.tools\gacha-local\client'
if(-not(Test-Path -LiteralPath $exe) -or -not(Test-Path -LiteralPath $client)){throw 'Prepare gacha-local first.'}
if(Get-Process | Where-Object ProcessName -Like 'animeoncraft*'){throw 'Close all AnimeOnCraft launcher windows first.'}
$game=Get-Content -LiteralPath (Join-Path $root '.tools\friends-game-server.json') -Raw | ConvertFrom-Json
$homeFolder=Join-Path $env:USERPROFILE '.animeoncraft\launcher'
$target=Join-Path $homeFolder 'game-runtime.json'
$backup=Join-Path $homeFolder ('game-runtime.before-gacha-local-'+[Guid]::NewGuid().ToString('N')+'.json')
Copy-Item -LiteralPath $target -Destination $backup
$public=Join-Path $root '.tools\modpack-mvp\client'
$value=Get-Content -LiteralPath $target -Raw | ConvertFrom-Json
$remote="$($value.server_host):$($value.server_port)"
$value.args=@($value.args | ForEach-Object {
 if($_ -eq $remote){'127.0.0.1:25565'}
 elseif($_ -eq $public){$client}
 elseif($_.StartsWith($public+'\')){$client+$_.Substring($public.Length)}
 else{$_}
})
$value.server_host='127.0.0.1';$value.server_port=25565;$value.server_id=$game.server_id;$value.game_directory=$client
[IO.File]::WriteAllText($target,($value | ConvertTo-Json -Depth 5))
try{
 Write-Output 'Local gacha review: local launcher, local API and local server. Close the launcher to restore the public runtime.'
 $process=Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe) -PassThru
 $process.WaitForExit()
 while(@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($client) }).Count -gt 0){Start-Sleep 5}
}finally{
 Copy-Item -LiteralPath $backup -Destination $target -Force
 Write-Output 'Public launcher runtime restored.'
}
