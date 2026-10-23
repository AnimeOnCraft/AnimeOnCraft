$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$source=Join-Path $workspace 'apps\launcher\src-tauri\target\release\animeoncraft-launcher.exe'
$fixture=Join-Path $workspace '.tools\pack-acceptance.exe'
if(Test-Path -LiteralPath $fixture){throw 'Existing acceptance executable preserved; inspect it before rerunning.'}
Push-Location $workspace
try{
 corepack pnpm@12.10.1 --filter @animeoncraft/launcher run tauri:build --features pack-acceptance
 if($LASTEXITCODE -ne 0){throw 'Pack acceptance build failed.'}
 Copy-Item -LiteralPath $source -Destination $fixture
 node scripts/native-pack-fixture.mjs
 if($LASTEXITCODE -ne 0){throw 'Pack HTTPS/native GUI acceptance failed; isolated files preserved.'}
}finally{
 corepack pnpm@12.10.1 --filter @animeoncraft/launcher run tauri:build
 $ordinaryBuild=$LASTEXITCODE
 # Only our exact executable; no recursive cleanup or user data removal.
 if(Test-Path -LiteralPath $fixture){Remove-Item -LiteralPath $fixture}
 Pop-Location
 if($ordinaryBuild -ne 0){throw 'Ordinary release rebuild failed.'}
}
