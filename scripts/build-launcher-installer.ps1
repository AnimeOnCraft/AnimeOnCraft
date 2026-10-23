$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -ErrorAction Stop
# Resolve the security module for this PowerShell edition, not inherited Core module paths.
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Security\Microsoft.PowerShell.Security.psd1') -ErrorAction Stop
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$nativeRoot=Join-Path $workspace 'apps\launcher\src-tauri'
$config=Get-Content -LiteralPath (Join-Path $nativeRoot 'tauri.conf.json') -Raw | ConvertFrom-Json
$package=Get-Content -LiteralPath (Join-Path $workspace 'apps\launcher\package.json') -Raw | ConvertFrom-Json
$cargo=Get-Content -LiteralPath (Join-Path $nativeRoot 'Cargo.toml') -Raw
$version=[regex]::Match($cargo,'(?m)^version\s*=\s*"([^"]+)"').Groups[1].Value
if($version -ne $config.version -or $package.version -notlike "$version-*"){throw 'Launcher version mismatch.'}
Push-Location $workspace
try{
 corepack pnpm@12.10.1 --filter @animeoncraft/launcher run tauri:build --config src-tauri/tauri.installer.conf.json --bundles nsis --ci --no-sign
 if($LASTEXITCODE -ne 0){throw 'Development NSIS build failed.'}
 $executable=Join-Path $nativeRoot 'target\release\animeoncraft-launcher.exe'
 $installer=Join-Path $nativeRoot "target\release\bundle\nsis\AnimeOnCraft_${version}_x64-setup.exe"
 if(-not(Test-Path -LiteralPath $installer)){throw 'Expected x64 installer not found.'}
 if((Get-Item -LiteralPath $installer).Length -gt 64MB){throw 'Installer exceeds the 64 MiB download limit of installed launchers. Reduce the payload or improve compression before signing.'}
 $signature=(Get-AuthenticodeSignature -LiteralPath $installer).Status.ToString()
 if((Get-AuthenticodeSignature -LiteralPath $executable).Status.ToString() -ne 'NotSigned'){throw 'This hash pipeline is for unsigned development payload only.'}
 $payload=node scripts/nsis-payload-hash.mjs $executable
 if($LASTEXITCODE -ne 0){throw 'NSIS payload hash verification failed.'}
 $payload=$payload | ConvertFrom-Json
 $report=[ordered]@{schema_version=1;version=$version;profile='development-nsis';architecture='x86_64';install_mode='currentUser';release_eligible=$false;installer=[ordered]@{path=$installer;bytes=(Get-Item -LiteralPath $installer).Length;sha256=(Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant();authenticode=$signature};payload=[ordered]@{bytes=$payload.bytes;sha256=$payload.payload_sha256;bundle_type=$payload.bundle_type;source_path=$executable;source_sha256=$payload.source_sha256;cli_version=$payload.cli_version};gates=@('Real HTTPS updater endpoint and public key','First installer with embedded updater configuration','Real game pack/runtime/source setup','Deployment acceptance with friends')}
 $reportPath=Join-Path $workspace ".tools\installer-candidate-$version.json"
 [IO.File]::WriteAllText($reportPath,($report | ConvertTo-Json -Depth 5))
 Write-Output "Development installer: $installer"
 Write-Output "Candidate report: $reportPath"
}finally{Pop-Location}
