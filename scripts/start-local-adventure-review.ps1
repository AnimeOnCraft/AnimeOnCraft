param()
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$review=Join-Path $root '.tools\local-review-0.7.31\game-runtime.json'
$exe=Join-Path $root '.tools\local-review-0.7.31\launcher\AnimeOnCraft-gameplay-local.exe'
if(-not(Test-Path -LiteralPath $review) -or -not(Test-Path -LiteralPath $exe)){throw 'Build and verify local 0.7.31 first.'}
$candidate=Get-Content -LiteralPath (Join-Path $root '.tools\gameplay-candidate-0.7.31.json') -Raw | ConvertFrom-Json
if((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant() -ne $candidate.executable_sha256){throw 'Review executable differs from the verified candidate.'}
$value=Get-Content -LiteralPath $review -Raw | ConvertFrom-Json
if($value.server_host -ne '127.0.0.1' -or $value.server_port -ne 25565){throw 'Review must target the local Minecraft server.'}
if((Get-FileHash -LiteralPath (Join-Path $value.game_directory 'mods\animeoncraft-admission-0.3.0-friends-dev.jar') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $candidate.admission_sha256){throw 'Review game mod differs from the verified candidate.'}
if(Get-Process | Where-Object ProcessName -Like 'AnimeOnCraft*'){throw 'Close all AnimeOnCraft launcher windows before switching the shared runtime. Minecraft must also be closed.'}
if(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object { $_.CommandLine -match 'neoforgeclient|cpw.mods.bootstraplauncher.BootstrapLauncher.*--launchTarget neoforgeclient' }){throw 'Close the Minecraft client before switching runtime.'}
$homeFolder=Join-Path $env:USERPROFILE '.animeoncraft\launcher'
New-Item -ItemType Directory -Path $homeFolder -Force | Out-Null
$target=Join-Path $homeFolder 'game-runtime.json'
$backup=Join-Path $homeFolder ('game-runtime.before-local-review-'+[Guid]::NewGuid().ToString('N')+'.json')
$existed=Test-Path -LiteralPath $target
if($existed){Copy-Item -LiteralPath $target -Destination $backup}
Copy-Item -LiteralPath $review -Destination $target
try {
 Write-Output 'Local review: API and Minecraft must be running on this computer. Use your local account.'
 $process=Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe) -WindowStyle Hidden -PassThru
 $process.WaitForExit()
 while($true){
  $clients=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($value.game_directory) })
  if($clients.Count -eq 0){break}
  Write-Output 'Close the local Minecraft client to restore the public launcher runtime.'
  foreach($client in $clients){Wait-Process -Id $client.ProcessId -ErrorAction SilentlyContinue}
 }
} finally {
 # Preparation inside the launcher may have updated this runtime. Keep that file separately,
 # then restore the original public runtime even after a failed local launch.
 if(Test-Path -LiteralPath $target){Copy-Item -LiteralPath $target -Destination (Join-Path $root '.tools\local-review-0.7.31\last-game-runtime.json')}
 if($existed){Copy-Item -LiteralPath $backup -Destination $target}
 else{Remove-Item -LiteralPath $target}
 Write-Output 'Original launcher runtime restored. The recovery backup remains next to it.'
}
