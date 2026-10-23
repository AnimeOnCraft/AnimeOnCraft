param([switch]$RequireBackend, [switch]$Visual, [switch]$SkipAccount, [switch]$ExpectBlockedLocal)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$executable = Join-Path $root 'apps\launcher\src-tauri\target\release\animeoncraft-launcher.exe'
if (-not (Test-Path -LiteralPath $executable)) { throw 'Build the native release first.' }
if (Get-NetTCPConnection -State Listen -LocalPort 19222 -ErrorAction SilentlyContinue) { throw 'Smoke port 19222 is already in use.' }
$previousArguments = $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS
$previousProfile = $env:WEBVIEW2_USER_DATA_FOLDER
$appProcess = $null
try {
  $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = '--remote-debugging-port=19222'
  $env:WEBVIEW2_USER_DATA_FOLDER = Join-Path $root '.tools\native-smoke-profile'
  $appProcess = Start-Process -FilePath $executable -WorkingDirectory $root -WindowStyle Hidden -PassThru
  $smokeArguments = @((Join-Path $PSScriptRoot 'native-smoke.mjs'))
  if ($RequireBackend) { $smokeArguments += '--require-backend' }
  if ($Visual) { $smokeArguments += '--visual' }
  if ($SkipAccount) { $smokeArguments += '--skip-account' }
  if ($ExpectBlockedLocal) { $smokeArguments += '--expect-blocked-local' }
  node @smokeArguments
  if ($LASTEXITCODE -ne 0) { throw 'Native WebView2 smoke failed.' }
} finally {
  $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = $previousArguments
  $env:WEBVIEW2_USER_DATA_FOLDER = $previousProfile
  if ($appProcess -and -not $appProcess.HasExited) {
    $null = $appProcess.CloseMainWindow()
    if (-not $appProcess.WaitForExit(5000)) { Stop-Process -Id $appProcess.Id }
  }
}
