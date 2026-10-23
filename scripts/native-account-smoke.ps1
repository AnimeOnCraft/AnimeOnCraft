param([Parameter(Mandatory=$true)][string]$FixtureFile,[string]$FixtureExecutable)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixture=[IO.Path]::GetFullPath($FixtureFile)
if (-not $fixture.StartsWith((Join-Path $root 'apps\backend\.tools\native-account-'),[StringComparison]::OrdinalIgnoreCase)) {throw 'Invalid own fixture path.'}
if (Get-NetTCPConnection -State Listen -LocalPort 19222 -ErrorAction SilentlyContinue) {throw 'Native test debug port already occupied.'}
$exe=if($FixtureExecutable){[IO.Path]::GetFullPath($FixtureExecutable)}else{Join-Path $root '.tools\native-account-fixture.exe'}
if($exe -notin @((Join-Path $root '.tools\native-account-fixture.exe'),(Join-Path $root '.tools\native-account-regression.exe'))){throw 'Invalid own acceptance executable.'}
if (-not (Test-Path -LiteralPath $exe)) {throw 'Build and copy the native-acceptance executable first.'}
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class NativeCredentialFixture {
 private const string Target="AnimeOnCraft/acceptance-loopback-v1/account-v1";
 [DllImport("advapi32.dll",EntryPoint="CredReadW",CharSet=CharSet.Unicode,SetLastError=true)] private static extern bool Read(string t,uint type,uint flags,out IntPtr p);
 [DllImport("advapi32.dll",EntryPoint="CredDeleteW",CharSet=CharSet.Unicode,SetLastError=true)] private static extern bool Delete(string t,uint type,uint flags);
 [DllImport("advapi32.dll")] private static extern void CredFree(IntPtr p);
 public static bool Absent(){IntPtr p;if(Read(Target,1,0,out p)){CredFree(p);return false;}if(Marshal.GetLastWin32Error()!=1168)throw new InvalidOperationException("Credential fixture read unavailable");return true;}
 public static void Cleanup(){if(!Delete(Target,1,0)&&Marshal.GetLastWin32Error()!=1168)throw new InvalidOperationException("Fixture delete failed");if(!Absent())throw new InvalidOperationException("Fixture remains");}
}
'@
if (-not [NativeCredentialFixture]::Absent()) {throw 'Acceptance credential already exists; preserving it.'}
$owned=$true
$previousArguments=$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS
$previousProfile=$env:WEBVIEW2_USER_DATA_FOLDER
$profile=[IO.Path]::GetFullPath((Join-Path $root ('.tools\native-account-profile-'+[Guid]::NewGuid().ToString('N'))))
$process=$null
try{
 $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS='--remote-debugging-port=19222'
 $env:WEBVIEW2_USER_DATA_FOLDER=$profile
 foreach($phase in @('flow','restore','crash','reauth')){
  $process=Start-Process -FilePath $exe -WorkingDirectory $root -WindowStyle Hidden -PassThru
  node (Join-Path $PSScriptRoot 'native-account-smoke.mjs') $fixture $phase
  if($LASTEXITCODE -ne 0){throw "Native fixture phase $phase failed."}
  if($phase -eq 'flow'){
   $second=Start-Process -FilePath $exe -WorkingDirectory $root -WindowStyle Hidden -PassThru
   try{$null=$second.Handle;if(-not $second.WaitForExit(5000)){Stop-Process -Id $second.Id;throw 'Second fixture launcher was not rejected.'};if($second.ExitCode -ne 0){throw 'Second fixture launcher exited with an error.'}}finally{$second.Dispose()}
   Write-Output 'PASS: second native account instance exits before opening a second account window.'
  }
  if(-not $process.HasExited){Stop-Process -Id $process.Id;$process.WaitForExit()}
  $process.Dispose();$process=$null
  if($phase -eq 'crash'){Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:39431/__fixture__/release-refresh' | Out-Null}
 }
}finally{
 if($process -and -not $process.HasExited){Stop-Process -Id $process.Id;$process.WaitForExit()}
 $env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=$previousArguments;$env:WEBVIEW2_USER_DATA_FOLDER=$previousProfile
 if($owned){[NativeCredentialFixture]::Cleanup()}
 if(Test-Path -LiteralPath $profile){
  if(-not $profile.StartsWith((Join-Path $root '.tools\native-account-profile-'),[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe profile cleanup.'}
  Remove-Item -LiteralPath $profile -Recurse -Force
 }
 Write-Output 'PASS: own acceptance credential absent; native fixture processes/profile cleaned.'
}
