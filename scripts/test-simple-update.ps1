param([string]$ResumeFixture='')
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -ErrorAction Stop
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$native=Join-Path $workspace 'apps\launcher\src-tauri'
$id=[Guid]::NewGuid().ToString('N');$fixture=Join-Path $workspace ".tools\installer-acceptance-$id"
if($ResumeFixture){$fixture=[IO.Path]::GetFullPath($ResumeFixture);if([IO.Path]::GetDirectoryName($fixture) -ne (Join-Path $workspace '.tools') -or [IO.Path]::GetFileName($fixture) -notmatch '^installer-acceptance-([a-f0-9]{32})$'){throw 'Invalid own resume scope'};$id=$Matches[1]}
$product="AnimeOnCraft Simple Update Test $id";$target=Join-Path $fixture 'install';$exe=Join-Path $target 'animeoncraft-launcher.exe'
$registry="HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$product";$remembered="HKCU:\Software\animeoncraft\$product"
foreach($port in 39447,19226){if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw 'Own updater test port occupied.'}}
if(-not $ResumeFixture){if((Test-Path -LiteralPath $fixture) -or (Test-Path -LiteralPath $registry)){throw 'Own fixture already exists.'};$null=New-Item -ItemType Directory -Path (Join-Path $fixture 'state\settings')}
$overlay=Join-Path $fixture 'config.json';$config=Get-Content (Join-Path $native 'tauri.installer.conf.json') -Raw | ConvertFrom-Json
$config|Add-Member productName $product;$config|Add-Member identifier "ru.animeoncraft.launcher.simpletest.$id"
$config.bundle.windows.webviewInstallMode=@{type='skip'};$config.bundle.windows.nsis.displayLanguageSelector=$false;$config.bundle.windows.nsis.languages=@('English')
$config.bundle.windows.nsis|Add-Member startMenuFolder $product
$previousFixture=$env:AOC_INSTALLER_ACCEPTANCE_DIRECTORY;$previousDebug=$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS;$previousProfile=$env:WEBVIEW2_USER_DATA_FOLDER
$appProcess=$null;$server=$null;$installed=$false;$node=(Get-Command node).Source
function Close-OwnApp{
 foreach($process in @(Get-CimInstance Win32_Process -Filter "Name='animeoncraft-launcher.exe'")){if($process.ExecutablePath -eq $exe){$own=Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue;if($own){$null=$own.CloseMainWindow();if(-not $own.WaitForExit(5000)){Stop-Process -Id $own.Id;$own.WaitForExit()}}}}
 $profileName="simple-webview-$id"
 foreach($process in @(Get-CimInstance Win32_Process -Filter "Name='msedgewebview2.exe' AND CommandLine LIKE '%$profileName%'")){if($process.CommandLine -and $process.CommandLine.Contains((Join-Path $fixture $profileName))){Stop-Process -Id $process.ProcessId -ErrorAction SilentlyContinue}}
}
function Installer([string]$path,[string]$arguments){
 $allowed=@((Join-Path $fixture 'installer-0.7.21.exe'),(Join-Path $fixture 'installer-0.7.22.exe'),(Join-Path $target 'uninstall.exe'))
 if([IO.Path]::GetFullPath($path) -notin $allowed){throw 'Unexpected own installer.'}
 $process=Start-Process -FilePath $path -ArgumentList $arguments -WindowStyle Hidden -PassThru
 if(-not $process.WaitForExit(60000)){throw 'Own installer timed out; fixture preserved.'};if($process.ExitCode -ne 0){throw "Own installer failed: $($process.ExitCode)"}
}
Push-Location $workspace
try{
 if(-not $ResumeFixture){foreach($version in @('0.7.22','0.7.21')){
  $config|Add-Member version $version -Force;[IO.File]::WriteAllText($overlay,($config|ConvertTo-Json -Depth 8))
  corepack pnpm@12.10.1 --filter @animeoncraft/launcher run tauri:build --features simple-update-acceptance --config $overlay --bundles nsis --ci --no-sign
  if($LASTEXITCODE -ne 0){throw "Own updater $version build failed."}
  $hash=node scripts/nsis-payload-hash.mjs (Join-Path $native 'target\release\animeoncraft-launcher.exe');if($LASTEXITCODE -ne 0){throw 'Own payload hash failed.'}
  [IO.File]::WriteAllText((Join-Path $fixture "payload-$version.json"),($hash -join "`n"))
  Copy-Item -LiteralPath (Join-Path $native "target\release\bundle\nsis\${product}_${version}_x64-setup.exe") -Destination (Join-Path $fixture "installer-$version.exe")
 }
 Copy-Item -LiteralPath (Join-Path $fixture 'installer-0.7.22.exe') -Destination (Join-Path $fixture 'signed-installer.exe')
 node scripts/simple-update-sign-fixture.mjs $fixture;if($LASTEXITCODE -ne 0){throw 'Own update signing failed.'}
 }else{if((Get-ItemProperty -LiteralPath $registry).DisplayVersion -ne '0.7.21'){throw 'Resume requires original own installed version'};if(-not(Test-Path -LiteralPath (Join-Path $fixture 'signed-installer.exe.sig'))){throw 'Own signed artifact absent'}}
 & (Get-Command pwsh).Source -NoProfile -File scripts/test-pack-certificate.ps1 -OutputDirectory $fixture;if($LASTEXITCODE -ne 0){throw 'Own TLS certificate failed.'}
 Copy-Item -LiteralPath (Join-Path $fixture 'certificate.pem') -Destination (Join-Path $fixture 'cert.pem')
 [IO.File]::WriteAllText((Join-Path $fixture 'mode.txt'),'tampered')
 if(Test-Path -LiteralPath (Join-Path $fixture 'server-ready.json')){Remove-Item -LiteralPath (Join-Path $fixture 'server-ready.json')}
 $server=Start-Process -FilePath $node -ArgumentList @((Join-Path $PSScriptRoot 'simple-update-server.mjs'),$fixture) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $fixture 'server.log') -RedirectStandardError (Join-Path $fixture 'server-errors.log')
 $deadline=[DateTime]::UtcNow.AddSeconds(15);while(-not(Test-Path -LiteralPath (Join-Path $fixture 'server-ready.json'))){if($server.HasExited -or [DateTime]::UtcNow -gt $deadline){throw 'Own HTTPS server failed.'};Start-Sleep -Milliseconds 100}
 if(-not $ResumeFixture){Installer (Join-Path $fixture 'installer-0.7.21.exe') "/S /NS /D=$target"};$installed=$true
 $old=(Get-Content (Join-Path $fixture 'payload-0.7.21.json') -Raw|ConvertFrom-Json).payload_sha256
 $new=(Get-Content (Join-Path $fixture 'payload-0.7.22.json') -Raw|ConvertFrom-Json).payload_sha256
 if((Get-FileHash -LiteralPath $exe).Hash.ToLowerInvariant() -ne $old){throw 'Own old installed payload mismatch.'}
 $world=Join-Path $fixture 'state\synthetic-world.dat';$foreign=Join-Path $target 'preserved-user-file.txt'
 [IO.File]::WriteAllText($world,'synthetic preserved world');[IO.File]::WriteAllText($foreign,'preserved foreign file')
 $worldHash=(Get-FileHash -LiteralPath $world).Hash;$foreignHash=(Get-FileHash -LiteralPath $foreign).Hash
 $env:AOC_INSTALLER_ACCEPTANCE_DIRECTORY=$fixture;$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS='--remote-debugging-port=19226';$env:WEBVIEW2_USER_DATA_FOLDER=Join-Path $fixture "simple-webview-$id"
 $appProcess=Start-Process -FilePath $exe -WorkingDirectory $workspace -WindowStyle Hidden -PassThru
 node scripts/native-simple-update-ui.mjs $fixture update;if($LASTEXITCODE -ne 0){throw 'Own updater GUI flow failed.'}
 if(-not $appProcess.WaitForExit(30000)){throw 'Old launcher did not exit.'}
 $deadline=[DateTime]::UtcNow.AddSeconds(45);$updated=$false
 while([DateTime]::UtcNow -lt $deadline){if((Get-ItemProperty -LiteralPath $registry).DisplayVersion -eq '0.7.22' -and (Get-FileHash -LiteralPath $exe).Hash.ToLowerInvariant() -eq $new){$updated=$true;break};Start-Sleep -Milliseconds 200}
 if(-not $updated){throw 'Own update installation did not finish.'}
 node scripts/native-simple-update-ui.mjs $fixture restart;if($LASTEXITCODE -ne 0){throw 'Automatic restart failed.'}
 Close-OwnApp
 if((Get-FileHash -LiteralPath $world).Hash -ne $worldHash -or (Get-FileHash -LiteralPath $foreign).Hash -ne $foreignHash){throw 'Update changed preserved files.'}
 Installer (Join-Path $target 'uninstall.exe') "/S _?=$target";$installed=$false
 if((Test-Path -LiteralPath $exe) -or (Test-Path -LiteralPath $registry)){throw 'Own fixture uninstall incomplete.'}
 if(Test-Path -LiteralPath $remembered){if($remembered -ne "HKCU:\Software\animeoncraft\AnimeOnCraft Simple Update Test $id"){throw 'Invalid own registry scope'};Remove-Item -LiteralPath $remembered -Recurse}
 [IO.File]::WriteAllText((Join-Path $workspace '.tools\simple-update-result.json'),(@{passed=$true;fixture=$fixture;old_version='0.7.21';new_version='0.7.22';actual_tls=$true;signature_and_signed_version_refusal=$true;progress_and_cancel=$true;automatic_restart=$true;settings_and_saves_preserved=$true;normal_source_configured=$false}|ConvertTo-Json))
 Write-Output "PASS: simple Tauri updater actual TLS/NSIS/GUI 0.7.21 -> 0.7.22, automatic restart and data preservation. $fixture"
}finally{
 Close-OwnApp
 if($server -and -not $server.HasExited){Stop-Process -Id $server.Id}
 $env:AOC_INSTALLER_ACCEPTANCE_DIRECTORY=$previousFixture;$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=$previousDebug;$env:WEBVIEW2_USER_DATA_FOLDER=$previousProfile
 foreach($name in @('key.pem','updater.key')){$secret=Join-Path $fixture $name;if(Test-Path -LiteralPath $secret){Remove-Item -LiteralPath $secret}}
 if($installed){Write-Output "Failed own installation preserved: $fixture"}
 powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-launcher-installer.ps1
 if($LASTEXITCODE -ne 0){throw 'Ordinary installer restore failed.'}
 Pop-Location
}
