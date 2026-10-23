param([Parameter(Mandatory=$true)][string]$Profile)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$target=[IO.Path]::GetFullPath($Profile)
if([IO.Path]::GetDirectoryName($target) -ne (Join-Path $root '.tools') -or [IO.Path]::GetFileName($target) -notmatch '^game-native-profile-[a-f0-9-]{36}$'){throw 'Unsafe own game fixture cleanup.'}
foreach($child in @(Get-CimInstance Win32_Process -Filter "Name='msedgewebview2.exe'")){
 if($child.CommandLine -and $child.CommandLine.Contains($target)){Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue}
}
if(Test-Path -LiteralPath $target){
 $resolved=[IO.Path]::GetFullPath((Resolve-Path -LiteralPath $target).Path)
 if($resolved -ne $target){throw 'Unexpected resolved game fixture path.'}
 Remove-Item -LiteralPath $target -Recurse -Force
}
