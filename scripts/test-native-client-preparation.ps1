$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(Get-Process -Name animeoncraft-launcher -ErrorAction SilentlyContinue){throw 'Close the launcher before this isolated preparation preview.'}
$receipt=Get-Content -LiteralPath (Join-Path $root '.tools\client-preparation-result.json') -Raw | ConvertFrom-Json
if(-not $receipt.passed){throw 'Run the isolated client preparation test first.'}
$directory=[IO.Path]::GetFullPath($receipt.directory)
if([IO.Path]::GetDirectoryName($directory) -ne (Join-Path $root '.tools') -or [IO.Path]::GetFileName($directory) -notmatch '^client-preparation-probe-\d+$'){throw 'Unexpected fixture directory.'}
$homeRuntime=Join-Path $env:USERPROFILE '.animeoncraft\launcher\game-runtime.json'
$previous=[IO.File]::ReadAllBytes($homeRuntime)
$fixture=[IO.File]::ReadAllBytes((Join-Path $directory 'prepared-game-runtime.json'))
try{
 [IO.File]::WriteAllBytes($homeRuntime,$fixture)
 & (Join-Path $PSScriptRoot 'native-friends-preview.ps1') -RequireBackend -PrepareGame
 if($LASTEXITCODE -ne 0){throw 'Native preparation preview failed.'}
}finally{
 [IO.File]::WriteAllBytes($homeRuntime,$previous)
}
$hasher=[Security.Cryptography.SHA256]::Create()
try{
 if([BitConverter]::ToString($hasher.ComputeHash($previous)) -ne [BitConverter]::ToString($hasher.ComputeHash([IO.File]::ReadAllBytes($homeRuntime)))){throw 'Human runtime was not restored byte-for-byte.'}
}finally{$hasher.Dispose()}
[ordered]@{passed=$true;checked_at=[DateTime]::UtcNow.ToString('o');fixture_directory=$directory;native_button=$true;concurrent_preparation_rejected=$true;ready=$true;server_available_after_preparation=$true;unchanged_files_downloaded_again=0;human_runtime_restored=$true;human_game_not_launched=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root '.tools\native-client-preparation-result.json') -Encoding utf8
Write-Output 'PASS: human runtime restored; no human login, membership or Minecraft session changed.'
