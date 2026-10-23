param([switch]$RequireBackend,[switch]$RequireGameConfig,[switch]$RequireGameServer,[switch]$RequireOwnerClans,[switch]$PrepareGame,[switch]$VisualRedesign,[switch]$LaunchGame,[switch]$GameplayCheck,[switch]$RequirePackUpdate)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$exe=Join-Path $root 'apps\launcher\src-tauri\target\release\animeoncraft-launcher.exe'
if(-not(Test-Path -LiteralPath $exe)){throw 'Build the native release first.'}
if(Get-NetTCPConnection -State Listen -LocalPort 19222 -ErrorAction SilentlyContinue){throw 'Preview port occupied.'}
$profile=[IO.Path]::GetFullPath((Join-Path $root ('.tools\friends-preview-'+[Guid]::NewGuid().ToString('N'))))
$previousArguments=$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS;$previousProfile=$env:WEBVIEW2_USER_DATA_FOLDER;$process=$null
try{
 $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS='--remote-debugging-port=19222';$env:WEBVIEW2_USER_DATA_FOLDER=$profile
 $process=Start-Process -FilePath $exe -WorkingDirectory $root -WindowStyle Hidden -PassThru
 $arguments=@((Join-Path $PSScriptRoot 'native-friends-preview.mjs'));if($RequireBackend){$arguments+='--require-backend'};if($RequireGameConfig){$arguments+='--require-game-config'}
 if($RequireGameServer){$arguments+='--require-game-server'}
 if($RequirePackUpdate){$arguments+='--require-pack-update'}
 if($RequireOwnerClans){$arguments+='--require-owner-clans'}
 if($PrepareGame){$arguments+='--prepare-game'}
 if($VisualRedesign){$arguments+='--visual-redesign'}
 if($LaunchGame){$arguments+='--launch-game'}
 if($GameplayCheck){$arguments+='--gameplay-check'}
 node @arguments
 if($LASTEXITCODE -ne 0){throw 'Ordinary native preview failed.'}
}finally{
 if($process -and -not $process.HasExited){$null=$process.CloseMainWindow();if(-not $process.WaitForExit(5000)){Stop-Process -Id $process.Id;$process.WaitForExit()}}
 $profileName=[IO.Path]::GetFileName($profile)
 if($profileName -notmatch '^friends-preview-[a-f0-9]{32}$'){throw 'Invalid own preview profile.'}
 foreach($child in @(Get-CimInstance Win32_Process -Filter "Name='msedgewebview2.exe' AND CommandLine LIKE '%$profileName%'")){if($child.CommandLine -and $child.CommandLine.Contains($profile)){Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue}}
 $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=$previousArguments;$env:WEBVIEW2_USER_DATA_FOLDER=$previousProfile
 if(Test-Path -LiteralPath $profile){
  $resolved=[IO.Path]::GetFullPath((Resolve-Path -LiteralPath $profile).Path)
  if([IO.Path]::GetDirectoryName($resolved) -ne (Join-Path $root '.tools') -or [IO.Path]::GetFileName($resolved) -notmatch '^friends-preview-[a-f0-9]{32}$'){throw 'Unsafe profile cleanup.'}
  for($attempt=0;$attempt -lt 8;$attempt++){
   foreach($child in @(Get-CimInstance Win32_Process -Filter "Name='msedgewebview2.exe' AND CommandLine LIKE '%$profileName%'")){if($child.CommandLine -and $child.CommandLine.Contains($profile)){Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue}}
   Start-Sleep -Milliseconds 250
   try{Remove-Item -LiteralPath $profile -Recurse -Force -ErrorAction Stop;break}catch{if($attempt -eq 7){throw}}
  }
 }
}
