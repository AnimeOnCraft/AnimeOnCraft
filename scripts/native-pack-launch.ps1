param([Parameter(Mandatory=$true)][string]$FixtureDirectory,[Parameter(Mandatory=$true)][ValidateSet('first','update','repair','restart','foreign')][string]$Mode)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$resolved=[IO.Path]::GetFullPath($FixtureDirectory)
if([IO.Path]::GetDirectoryName($resolved) -ne (Join-Path $workspace '.tools') -or [IO.Path]::GetFileName($resolved) -notmatch '^pack-acceptance-[a-f0-9]{32}$'){throw 'Unsafe pack fixture directory.'}
if(Get-NetTCPConnection -State Listen -LocalPort 19223 -ErrorAction SilentlyContinue){throw 'Pack CDP port occupied.'}
$previousDebug=$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS;$previousProfile=$env:WEBVIEW2_USER_DATA_FOLDER;$previousFixture=$env:AOC_PACK_ACCEPTANCE_DIRECTORY
$appProcess=$null
try{
 $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS='--remote-debugging-port=19223'
 $env:WEBVIEW2_USER_DATA_FOLDER=Join-Path $resolved 'webview'
 $env:AOC_PACK_ACCEPTANCE_DIRECTORY=$resolved
 $appProcess=Start-Process -FilePath (Join-Path $workspace '.tools\pack-acceptance.exe') -WorkingDirectory $workspace -WindowStyle Hidden -PassThru
 node (Join-Path $workspace 'scripts\native-pack-ui.mjs') $Mode $resolved
 if($LASTEXITCODE -ne 0){throw 'Pack WebView2 acceptance failed.'}
}finally{
 $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=$previousDebug;$env:WEBVIEW2_USER_DATA_FOLDER=$previousProfile;$env:AOC_PACK_ACCEPTANCE_DIRECTORY=$previousFixture
 if($appProcess -and -not $appProcess.HasExited){$null=$appProcess.CloseMainWindow();if(-not $appProcess.WaitForExit(5000)){Stop-Process -Id $appProcess.Id}}
}
