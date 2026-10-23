$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -ErrorAction Stop
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$nativeRoot=Join-Path $workspace 'apps\launcher\src-tauri'
$id=[guid]::NewGuid().ToString('N')
$fixtureRoot=Join-Path $workspace ".tools\installer-acceptance-$id"
$installTarget=Join-Path $fixtureRoot 'install'
$product="AnimeOnCraft Installer Test $id"
$uninstallKey="HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$product"
if(Test-Path -LiteralPath $uninstallKey){throw 'Unexpected existing installer test registry entry.'}
if(Test-Path -LiteralPath $fixtureRoot){throw 'Fixture directory already exists.'}
$null=New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'state\local')
$overlay=Join-Path $fixtureRoot 'config.json'
$fixtureConfig=Get-Content -LiteralPath (Join-Path $nativeRoot 'tauri.installer.conf.json') -Raw | ConvertFrom-Json
$fixtureConfig | Add-Member -NotePropertyName productName -NotePropertyValue $product
$fixtureConfig | Add-Member -NotePropertyName identifier -NotePropertyValue "ru.animeoncraft.launcher.installertest.$id"
$fixtureConfig.bundle.windows.webviewInstallMode=@{type='skip'}
$fixtureConfig.bundle.windows.nsis.displayLanguageSelector=$false
$fixtureConfig.bundle.windows.nsis.languages=@('English')
$fixtureConfig.bundle.windows.nsis | Add-Member -NotePropertyName startMenuFolder -NotePropertyValue $product
[IO.File]::WriteAllText($overlay,($fixtureConfig | ConvertTo-Json -Depth 8))
$config=Get-Content -LiteralPath (Join-Path $nativeRoot 'tauri.conf.json') -Raw | ConvertFrom-Json
$installer=Join-Path $nativeRoot "target\release\bundle\nsis\${product}_$($config.version)_x64-setup.exe"
$executable=Join-Path $installTarget 'animeoncraft-launcher.exe'
$uninstaller=Join-Path $installTarget 'uninstall.exe'
$appProcess=$null;$installed=$false
$previousFixture=$env:AOC_INSTALLER_ACCEPTANCE_DIRECTORY;$previousDebug=$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS;$previousProfile=$env:WEBVIEW2_USER_DATA_FOLDER
function Invoke-OwnInstaller([string]$File,[string]$Arguments){
 $resolvedFile=[IO.Path]::GetFullPath($File)
 if($resolvedFile -ne $installer -and $resolvedFile -ne $uninstaller){throw 'Unexpected executable path.'}
 $process=Start-Process -FilePath $resolvedFile -ArgumentList $Arguments -WorkingDirectory $workspace -WindowStyle Hidden -PassThru
 if(-not $process.WaitForExit(60000)){Stop-Process -Id $process.Id;throw 'Own installer timed out; fixture preserved.'}
 return $process.ExitCode
}
function Close-OwnApp{
 if($appProcess -and -not $appProcess.HasExited){$null=$appProcess.CloseMainWindow();if(-not $appProcess.WaitForExit(5000)){Stop-Process -Id $appProcess.Id}}
}
function Read-OwnStateHashes{
 $state=Join-Path $fixtureRoot 'state'
 $result=@{};Get-ChildItem -LiteralPath $state -File -Recurse | ForEach-Object {$result[$_.FullName.Substring($state.Length)]=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
 return $result
}
Push-Location $workspace
try{
 corepack pnpm@12.10.1 --filter @animeoncraft/launcher run tauri:build --features installer-acceptance --config $overlay --bundles nsis --ci --no-sign
 if($LASTEXITCODE -ne 0){throw 'Isolated installer build failed.'}
 $payload=node scripts/nsis-payload-hash.mjs (Join-Path $nativeRoot 'target\release\animeoncraft-launcher.exe')
 if($LASTEXITCODE -ne 0){throw 'Expected payload transformation failed.'}
 $expected=($payload | ConvertFrom-Json).payload_sha256
 if((Invoke-OwnInstaller $installer "/S /NS /D=$installTarget") -ne 0){throw 'Own NSIS installation failed.'}
 $installed=$true
 if((Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash -ne $expected){throw 'Installed payload differs from packaged executable.'}
 if(-not (Test-Path -LiteralPath $uninstallKey)){throw 'Own HKCU uninstall entry missing.'}
 Set-ItemProperty -LiteralPath $uninstallKey -Name DisplayVersion -Value '99.0.0'
 try{
  if((Invoke-OwnInstaller $installer "/S /NS /D=$installTarget") -eq 0){throw 'Installer accepted a downgrade.'}
  if((Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash -ne $expected){throw 'Downgrade refusal modified payload.'}
 }finally{Set-ItemProperty -LiteralPath $uninstallKey -Name DisplayVersion -Value $config.version}
 $sentinel=Join-Path $installTarget 'preserved-user-file.txt';Set-Content -LiteralPath $sentinel -Value 'foreign install-directory data' -Encoding UTF8
 $save=Join-Path $fixtureRoot 'state\synthetic-world.dat';Set-Content -LiteralPath $save -Value 'synthetic user save' -Encoding UTF8
 $env:AOC_INSTALLER_ACCEPTANCE_DIRECTORY=$fixtureRoot;$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS='--remote-debugging-port=19224';$env:WEBVIEW2_USER_DATA_FOLDER=Join-Path $fixtureRoot 'webview'
 if(Get-NetTCPConnection -State Listen -LocalPort 19224 -ErrorAction SilentlyContinue){throw 'Installed smoke CDP port occupied.'}
 foreach($pass in 1,2){
  $appProcess=Start-Process -FilePath $executable -WorkingDirectory $workspace -WindowStyle Hidden -PassThru
  node scripts/native-installed-ui.mjs $pass $fixtureRoot
  if($LASTEXITCODE -ne 0){throw 'Installed WebView2 smoke failed.'}
  if($pass -eq 1){
   if((Invoke-OwnInstaller $installer "/S /NS /D=$installTarget") -eq 0){throw 'Installer replaced a running launcher.'}
   if($appProcess.HasExited){throw 'Installer forcibly stopped own launcher.'}
   if((Invoke-OwnInstaller $uninstaller "/S _?=$installTarget") -eq 0){throw 'Uninstaller removed a running launcher.'}
   if($appProcess.HasExited -or (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash -ne $expected){throw 'Running launcher or payload was modified.'}
  }
  Close-OwnApp
  if($pass -eq 1){$firstHashes=Read-OwnStateHashes}else{$secondHashes=Read-OwnStateHashes}
 }
 if($firstHashes.Count -ne $secondHashes.Count){throw 'Read-only restart changed state files.'}
 foreach($name in $firstHashes.Keys){if($firstHashes[$name] -ne $secondHashes[$name]){throw 'Read-only restart changed persisted state.'}}
 if((Invoke-OwnInstaller $uninstaller "/S _?=$installTarget") -ne 0){throw 'Own uninstall failed.'}
 $installed=$false
 if(Test-Path -LiteralPath $executable){throw 'Uninstall retained managed executable.'}
 if(Test-Path -LiteralPath $uninstallKey){throw 'Own uninstall entry remained.'}
 # The stock installer retains its remembered path. Remove only this unique test product key.
 $rememberedKey="HKCU:\Software\animeoncraft\$product"
 if($product -ne "AnimeOnCraft Installer Test $id" -or $id -notmatch '^[a-f0-9]{32}$'){throw 'Unexpected test registry key.'}
 if(Test-Path -LiteralPath $rememberedKey){Remove-Item -LiteralPath $rememberedKey -Recurse}
 if((Get-Content -LiteralPath $sentinel -Raw).Trim() -ne 'foreign install-directory data' -or (Get-Content -LiteralPath $save -Raw).Trim() -ne 'synthetic user save'){throw 'Uninstall removed preserved data.'}
 $after=Read-OwnStateHashes;foreach($name in $secondHashes.Keys){if($after[$name] -ne $secondHashes[$name]){throw 'Uninstall changed application data.'}}
 # NSIS _?= runs its own file in place; remove only that exact file after process exit.
 if(Test-Path -LiteralPath $uninstaller){Remove-Item -LiteralPath $uninstaller}
 [IO.File]::WriteAllText((Join-Path $workspace '.tools\installer-acceptance-result.json'),(@{passed=$true;version=$config.version;fixture=$fixtureRoot;payload_sha256=$expected.ToLowerInvariant();downgrade_refused=$true;running_install_refused=$true;running_uninstall_refused=$true;settings_and_state_preserved=$true;foreign_file_preserved=$true;registry_removed=$true} | ConvertTo-Json))
 Write-Output "PASS: isolated NSIS install/installed WebView2/restart/running-app refusal/uninstall/data preservation. Evidence: $fixtureRoot"
}catch{
 Write-Output "Installer acceptance failure: $($_.Exception.Message)"
 throw
}finally{
 Close-OwnApp
 $env:AOC_INSTALLER_ACCEPTANCE_DIRECTORY=$previousFixture;$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=$previousDebug;$env:WEBVIEW2_USER_DATA_FOLDER=$previousProfile
 if($installed){Write-Output "Fixture preserved after failure: $fixtureRoot; own test registry entry: $uninstallKey"}
 # Restore the ordinary native artifact and normal candidate after acceptance.
 powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-launcher-installer.ps1
 $restoreExit=$LASTEXITCODE
 Pop-Location
 if($restoreExit -ne 0){throw 'Ordinary NSIS candidate restore failed.'}
}
