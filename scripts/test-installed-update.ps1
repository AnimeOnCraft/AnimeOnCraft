param([string]$ResumeFixture='')
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -ErrorAction Stop
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$native=Join-Path $workspace 'apps\launcher\src-tauri'
$id=[guid]::NewGuid().ToString('N');$fixture=Join-Path $workspace ".tools\installer-acceptance-$id"
if($ResumeFixture){
 $fixture=[IO.Path]::GetFullPath($ResumeFixture)
 if([IO.Path]::GetDirectoryName($fixture) -ne (Join-Path $workspace '.tools') -or [IO.Path]::GetFileName($fixture) -notmatch '^installer-acceptance-([a-f0-9]{32})$'){throw 'Unexpected own resume fixture.'}
 $id=$Matches[1]
}
$target=Join-Path $fixture 'install';$product="AnimeOnCraft Updater Test $id"
$key="HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$product"
$remembered="HKCU:\Software\animeoncraft\$product"
if(-not $ResumeFixture -and ((Test-Path -LiteralPath $fixture) -or (Test-Path -LiteralPath $key))){throw 'Fixture already exists.'}
foreach($port in 39445,19225){if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw "Own fixture port $port occupied."}}
if(-not $ResumeFixture){$null=New-Item -ItemType Directory -Path (Join-Path $fixture 'state\local')}
$overlay=Join-Path $fixture 'config.json';$exe=Join-Path $target 'animeoncraft-launcher.exe';$uninstaller=Join-Path $target 'uninstall.exe'
$config=Get-Content (Join-Path $native 'tauri.installer.conf.json') -Raw | ConvertFrom-Json
$config | Add-Member -NotePropertyName productName -NotePropertyValue $product
$config | Add-Member -NotePropertyName identifier -NotePropertyValue "ru.animeoncraft.launcher.updatertest.$id"
$config | Add-Member -NotePropertyName version -NotePropertyValue '0.7.19'
$config.bundle.windows.webviewInstallMode=@{type='skip'}
$config.bundle.windows.nsis.displayLanguageSelector=$false;$config.bundle.windows.nsis.languages=@('English')
$config.bundle.windows.nsis | Add-Member -NotePropertyName startMenuFolder -NotePropertyValue $product
$node=(Get-Command node -ErrorAction Stop).Source;$cargo=Join-Path $env:USERPROFILE '.cargo\bin\cargo.exe'
$appProcess=$null;$serverProcess=$null;$installed=$false
$previousFixture=$env:AOC_INSTALLER_ACCEPTANCE_DIRECTORY;$previousExport=$env:AOC_INSTALLED_UPDATE_DIRECTORY
$previousDebug=$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS;$previousProfile=$env:WEBVIEW2_USER_DATA_FOLDER
function Close-OwnApp{
 if($appProcess -and -not $appProcess.HasExited){$null=$appProcess.CloseMainWindow();if(-not $appProcess.WaitForExit(5000)){Stop-Process -Id $appProcess.Id}}
}
function OwnInstaller([string]$file,[string]$arguments){
 $allowed=@($oldInstaller,$newInstaller,$uninstaller)
 if([IO.Path]::GetFullPath($file) -notin $allowed){throw 'Unexpected own installer.'}
 $process=Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $workspace -WindowStyle Hidden -PassThru
 if(-not $process.WaitForExit(60000)){Stop-Process -Id $process.Id;throw 'Own installer timed out.'}
 return $process.ExitCode
}
function StableHashes{
 $values=@{}
 foreach($folder in @('state\settings','state\local\AnimeOnCraft.InstallDevelopment.v1')){
  $path=Join-Path $fixture $folder
  Get-ChildItem -LiteralPath $path -Recurse -File | ForEach-Object {$values[$_.FullName.Substring($fixture.Length)]=(Get-FileHash -LiteralPath $_.FullName).Hash}
 }
 foreach($path in @($save,$foreign)){$values[$path.Substring($fixture.Length)]=(Get-FileHash -LiteralPath $path).Hash}
 return $values
}
function AssertStable($before,$after){
 if($before.Count -ne $after.Count){throw 'Update changed stable application-data inventory.'}
 foreach($name in $before.Keys){if($before[$name] -ne $after[$name]){throw "Update modified preserved file: $name"}}
}
Push-Location $workspace
try{
 if($ResumeFixture){
  $oldInstaller=Join-Path $native "target\release\bundle\nsis\${product}_0.7.18_x64-setup.exe"
  $newInstaller=Join-Path $native "target\release\bundle\nsis\${product}_0.7.19_x64-setup.exe"
  $oldHash=(Get-Content -LiteralPath (Join-Path $fixture 'payload-0.7.18.json') -Raw | ConvertFrom-Json).payload_sha256
  $newHash=(Get-Content -LiteralPath (Join-Path $fixture 'payload-0.7.19.json') -Raw | ConvertFrom-Json).payload_sha256
  if($oldHash -notmatch '^[a-f0-9]{64}$' -or $newHash -notmatch '^[a-f0-9]{64}$' -or (Get-FileHash -LiteralPath $exe).Hash -ne $oldHash){throw 'Resume payload evidence does not match original own compiled installer.'}
  # Keep failed own staging as evidence outside the bounded active staging parent.
  $local=Join-Path $fixture 'state\local';$evidence=Join-Path $fixture 'failed-stage-evidence'
  if(-not(Test-Path -LiteralPath $evidence)){$null=New-Item -ItemType Directory -Path $evidence}
  foreach($child in @(Get-ChildItem -LiteralPath $local -Directory -Filter 'AnimeOnCraft.LauncherUpdate.*')){
   $from=[IO.Path]::GetFullPath($child.FullName);$to=[IO.Path]::GetFullPath((Join-Path $evidence $child.Name))
   if($child.Name -notmatch '^AnimeOnCraft\.LauncherUpdate\.[a-f0-9]{32}$' -or $child.Attributes -band [IO.FileAttributes]::ReparsePoint -or [IO.Path]::GetDirectoryName($from) -ne $local -or [IO.Path]::GetDirectoryName($to) -ne $evidence -or -not $from.StartsWith($fixture+'\') -or -not $to.StartsWith($fixture+'\')){throw 'Unexpected staging evidence move scope.'}
   $marker=Get-Content -LiteralPath (Join-Path $from 'aoc-instance.json') -Raw | ConvertFrom-Json
   $names=@(Get-ChildItem -LiteralPath $from -Name)
   if($marker.environment -ne 'launcher-update-stage-v1' -or $names.Count -ne 2 -or 'setup.exe' -notin $names -or 'aoc-instance.json' -notin $names -or (Get-FileHash -LiteralPath (Join-Path $from 'setup.exe')).Hash -ne (Get-FileHash -LiteralPath (Join-Path $fixture 'signed-installer.exe')).Hash){throw 'Unexpected prior own staging evidence.'}
   Move-Item -LiteralPath $from -Destination $to
  }
 }else{foreach($version in @('0.7.19','0.7.18')){
  $config.version=$version;[IO.File]::WriteAllText($overlay,($config | ConvertTo-Json -Depth 8))
  corepack pnpm@12.10.1 --filter @animeoncraft/launcher run tauri:build --features self-update-acceptance --config $overlay --bundles nsis --ci --no-sign
  if($LASTEXITCODE -ne 0){throw "Own native$version build failed."}
  $info=[Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $native 'target\release\animeoncraft-launcher.exe'))
  if("$($info.ProductMajorPart).$($info.ProductMinorPart).$($info.ProductBuildPart)" -ne $version -or $info.ProductPrivatePart -ne 0){throw 'Runtime executable resource VERSION mismatch.'}
  $built=Join-Path $native "target\release\bundle\nsis\${product}_${version}_x64-setup.exe"
  $payload=node scripts/nsis-payload-hash.mjs (Join-Path $native 'target\release\animeoncraft-launcher.exe')
  if($LASTEXITCODE -ne 0){throw 'Payload marker/hash calculation failed.'}
  $hash=($payload | ConvertFrom-Json).payload_sha256
  [IO.File]::WriteAllText((Join-Path $fixture "payload-$version.json"),$payload)
  if($version -eq '0.7.19'){$newInstaller=$built;$newHash=$hash;Copy-Item -LiteralPath $built -Destination (Join-Path $fixture 'signed-installer.exe')}
  else{$oldInstaller=$built;$oldHash=$hash}
 }}
 $env:AOC_INSTALLED_UPDATE_DIRECTORY=$fixture
 & $cargo test --locked --manifest-path apps/launcher/src-tauri/Cargo.toml export_installed_update_fixture -- --ignored --test-threads=1
 if($LASTEXITCODE -ne 0){throw 'Actual installer resource/signature export failed.'}
 & pwsh -NoProfile -NonInteractive -File scripts/test-pack-certificate.ps1 -OutputDirectory $fixture
 if($LASTEXITCODE -ne 0){throw 'Own TLS certificate generation failed.'}
 [IO.File]::WriteAllText((Join-Path $fixture 'choice.json'),'{"mode":"wrong"}')
 $serverProcess=Start-Process -FilePath $node -ArgumentList @((Join-Path $workspace 'scripts\installed-update-server.mjs'),$fixture) -WorkingDirectory $workspace -WindowStyle Hidden -RedirectStandardOutput (Join-Path $fixture 'server.log') -RedirectStandardError (Join-Path $fixture 'server-errors.log') -PassThru
 $deadline=[DateTime]::UtcNow.AddSeconds(15)
 while(-not (Test-Path -LiteralPath (Join-Path $fixture 'server-ready.json'))){if($serverProcess.HasExited -or [DateTime]::UtcNow -gt $deadline){throw 'Own TLS fixture server did not start.'};Start-Sleep -Milliseconds 100}
 if((OwnInstaller $oldInstaller "/S /NS /D=$target") -ne 0){throw 'Own old-version installation failed.'}
 $installed=$true
 if((Get-FileHash -LiteralPath $exe).Hash -ne $oldHash){throw 'Old installed payload hash mismatch.'}
 if((OwnInstaller $newInstaller "/S /NS /AOC_PARENT_PID=0 /D=$target") -eq 0){throw 'Malformed updater parent accepted.'}
 if((Get-FileHash -LiteralPath $exe).Hash -ne $oldHash){throw 'Malformed parent changed payload.'}
 $save=Join-Path $fixture 'state\synthetic-world.dat';$foreign=Join-Path $target 'preserved-user-file.txt'
 [IO.File]::WriteAllText($save,'preserved synthetic world');[IO.File]::WriteAllText($foreign,'preserved foreign install data')
 $env:AOC_INSTALLER_ACCEPTANCE_DIRECTORY=$fixture;$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS='--remote-debugging-port=19225';$env:WEBVIEW2_USER_DATA_FOLDER=Join-Path $fixture 'webview'
 $appProcess=Start-Process -FilePath $exe -WorkingDirectory $workspace -WindowStyle Hidden -PassThru
 node scripts/native-update-ui.mjs wrong $fixture
 if($LASTEXITCODE -ne 0){throw 'Wrong-version native refusal failed.'}
 if(@(Get-ChildItem -LiteralPath (Join-Path $fixture 'state\local') -Directory -Filter 'AnimeOnCraft.LauncherUpdate.*').Count -ne 0){throw 'Rejected binary VERSION left owned staging behind.'}
 if($appProcess.HasExited -or (Get-FileHash -LiteralPath $exe).Hash -ne $oldHash){throw 'Rejected metadata VERSION changed launcher.'}
 Close-OwnApp
 $before=StableHashes
 $appProcess=Start-Process -FilePath $exe -WorkingDirectory $workspace -WindowStyle Hidden -PassThru
 if((OwnInstaller $newInstaller "/S /NS /AOC_PARENT_PID=$($appProcess.Id) /D=$target") -eq 0){throw 'Installer replaced a parent that never exited.'}
 if($appProcess.HasExited -or (Get-FileHash -LiteralPath $exe).Hash -ne $oldHash){throw 'Parent timeout killed or modified launcher.'}
 Close-OwnApp
 AssertStable $before (StableHashes)
 $appProcess=Start-Process -FilePath $exe -WorkingDirectory $workspace -WindowStyle Hidden -PassThru
 node scripts/native-update-ui.mjs update $fixture
 if($LASTEXITCODE -ne 0){throw 'Native updater confirmation failed.'}
 if(-not $appProcess.WaitForExit(30000)){throw 'Old launcher did not exit after handoff.'}
 $deadline=[DateTime]::UtcNow.AddSeconds(45)
 while([DateTime]::UtcNow -lt $deadline){if((Get-ItemProperty -LiteralPath $key).DisplayVersion -eq '0.7.19' -and (Get-FileHash -LiteralPath $exe).Hash -eq $newHash){break};Start-Sleep -Milliseconds 200}
 if((Get-ItemProperty -LiteralPath $key).DisplayVersion -ne '0.7.19' -or (Get-FileHash -LiteralPath $exe).Hash -ne $newHash){throw 'New-version installed payload/version mismatch.'}
 AssertStable $before (StableHashes)
 $appProcess=Start-Process -FilePath $exe -WorkingDirectory $workspace -WindowStyle Hidden -PassThru
 node scripts/native-update-ui.mjs restart $fixture
 if($LASTEXITCODE -ne 0){throw 'New-version WebView/settings/current-state restart failed.'}
 Close-OwnApp;AssertStable $before (StableHashes)
 if(@(Get-ChildItem -LiteralPath (Join-Path $fixture 'state\local') -Directory -Filter 'AnimeOnCraft.LauncherUpdate.*').Count -ne 1){throw 'Handed-off installer staging was prematurely removed.'}
 if((OwnInstaller $uninstaller "/S _?=$target") -ne 0){throw 'Own updated-app uninstall failed.'}
 $installed=$false
 if((Test-Path -LiteralPath $exe) -or (Test-Path -LiteralPath $key)){throw 'Own updater installer entries were not removed.'}
 if($id -notmatch '^[a-f0-9]{32}$' -or [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($fixture)) -ne (Join-Path $workspace '.tools')){throw 'Unexpected cleanup scope.'}
 if(Test-Path -LiteralPath $remembered){Remove-Item -LiteralPath $remembered -Recurse}
 if(Test-Path -LiteralPath $uninstaller){Remove-Item -LiteralPath $uninstaller}
 AssertStable $before (StableHashes)
 [IO.File]::WriteAllText((Join-Path $workspace '.tools\installed-update-result.json'),(@{passed=$true;fixture=$fixture;old_version='0.7.18';new_version='0.7.19';old_payload_sha256=$oldHash;new_payload_sha256=$newHash;installer_sha256=(Get-FileHash -LiteralPath (Join-Path $fixture 'signed-installer.exe')).Hash;embedded_version_refusal=$true;pre_handoff_stage_cleanup=$true;handed_off_stage_preserved=$true;process_rollback_refusal=$true;malformed_parent_refusal=$true;live_parent_timeout_refusal=$true;native_handoff=$true;old_process_exited=$true;new_version_restarted=$true;data_preserved=$true;own_registry_removed=$true;automatic_restart_tested=$false;production_release=$false} | ConvertTo-Json))
 Write-Output "PASS: signed HTTPS native handoff0.7.18 -> actual NSIS0.7.19, restart/data preservation. Evidence: $fixture"
}catch{
 Write-Output "Installed update failure: $($_.Exception.Message)"
 Write-Output $_.ScriptStackTrace
 throw
}finally{
 Close-OwnApp
 if($serverProcess -and -not $serverProcess.HasExited){Stop-Process -Id $serverProcess.Id}
 $env:AOC_INSTALLER_ACCEPTANCE_DIRECTORY=$previousFixture;$env:AOC_INSTALLED_UPDATE_DIRECTORY=$previousExport;$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=$previousDebug;$env:WEBVIEW2_USER_DATA_FOLDER=$previousProfile
 if(Test-Path -LiteralPath (Join-Path $fixture 'key.pem')){Remove-Item -LiteralPath (Join-Path $fixture 'key.pem')}
 if($installed){Write-Output "Failed own fixture preserved: $fixture; uninstall key: $key"}
 powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-launcher-installer.ps1
 $restoreExit=$LASTEXITCODE;Pop-Location
 if($restoreExit -ne 0){throw 'Ordinary installer restore failed.'}
}
