param([switch]$DebugFixture,[switch]$OwnerClansOnly)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixture=Join-Path $root '.tools\native-game-fixture.exe'
if(Get-NetTCPConnection -State Listen -LocalPort 19222,39431,25566 -ErrorAction SilentlyContinue){throw 'Acceptance port already occupied; existing processes preserved.'}
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class GameCredentialFixture {
 [DllImport("advapi32.dll",EntryPoint="CredReadW",CharSet=CharSet.Unicode,SetLastError=true)] private static extern bool Read(string t,uint type,uint flags,out IntPtr p);
 [DllImport("advapi32.dll")] private static extern void CredFree(IntPtr p);
 [DllImport("advapi32.dll",EntryPoint="CredDeleteW",CharSet=CharSet.Unicode,SetLastError=true)] private static extern bool Delete(string t,uint type,uint flags);
 public static void Cleanup(){if(!Delete("AnimeOnCraft/acceptance-loopback-v1/account-v1",1,0)&&Marshal.GetLastWin32Error()!=1168)throw new InvalidOperationException("Own credential fixture cleanup failed");if(!Absent())throw new InvalidOperationException("Own fixture credential remains");}
 public static bool Absent(){IntPtr p;if(Read("AnimeOnCraft/acceptance-loopback-v1/account-v1",1,0,out p)){CredFree(p);return false;}if(Marshal.GetLastWin32Error()!=1168)throw new InvalidOperationException("Credential fixture read unavailable");return true;}
}
'@
if(-not [GameCredentialFixture]::Absent()){throw 'Unexpected acceptance credential exists; preserved.'}
$previousOwner=$env:AOC_OWNER_CLANS_NATIVE_PROBE
$previousGame=$env:AOC_GAME_NATIVE_PROBE
$previousLauncher=$env:AOC_GAME_LAUNCHER_PROBE
Push-Location $root
try{
 $buildArguments=@('--features','native-acceptance','--no-bundle');if($DebugFixture){$buildArguments+='--debug'}
 corepack pnpm@12.10.1 --filter @animeoncraft/launcher run tauri:build @buildArguments
 if($LASTEXITCODE -ne 0){throw 'Native game fixture build failed.'}
 $configuration=if($DebugFixture){'debug'}else{'release'}
 Copy-Item -LiteralPath (Join-Path $root "apps\launcher\src-tauri\target\$configuration\animeoncraft-launcher.exe") -Destination $fixture -Force
 if($OwnerClansOnly){$env:AOC_OWNER_CLANS_NATIVE_PROBE='true';$env:AOC_GAME_NATIVE_PROBE=$null;$env:AOC_GAME_LAUNCHER_PROBE=$null}else{$env:AOC_GAME_NATIVE_PROBE='true';$env:AOC_GAME_LAUNCHER_PROBE='true'}
 corepack pnpm@12.10.1 --filter @animeoncraft/backend exec tsx db/friends-smoke.ts
 if($LASTEXITCODE -ne 0){throw 'Native PLAY acceptance failed; inspect game-admission-smoke logs.'}
 [GameCredentialFixture]::Cleanup()
 Write-Output 'PASS: own acceptance logout marker removed; credential absent.'
}finally{
 $cleanupFailed=$false
 try{[GameCredentialFixture]::Cleanup()}catch{$cleanupFailed=$true}
 $env:AOC_OWNER_CLANS_NATIVE_PROBE=$previousOwner
 $env:AOC_GAME_NATIVE_PROBE=$previousGame;$env:AOC_GAME_LAUNCHER_PROBE=$previousLauncher
 # Restore an ordinary build; never leave the fixture as the development artifact.
 $normal=0
 if(-not $DebugFixture){powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-launcher-installer.ps1;$normal=$LASTEXITCODE}
 if(Test-Path -LiteralPath $fixture){Remove-Item -LiteralPath $fixture}
 Pop-Location
 if($normal -ne 0){throw 'Ordinary launcher rebuild failed.'}
 if($cleanupFailed){throw 'Own acceptance credential cleanup failed.'}
}
