$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixture=[IO.Path]::GetFullPath((Join-Path $root '.tools\native-account-fixture.exe'))
$source=Join-Path $root 'apps\launcher\src-tauri\target\release\animeoncraft-launcher.exe'
if(-not $fixture.StartsWith((Join-Path $root '.tools\'),[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe fixture executable path.'}
Push-Location $root
try{
 corepack pnpm@12.10.1 --filter @animeoncraft/launcher run tauri:build --features native-acceptance
 if($LASTEXITCODE -ne 0){throw 'Acceptance build failed.'}
 Copy-Item -LiteralPath $source -Destination $fixture -Force
 corepack pnpm@12.10.1 --filter @animeoncraft/backend exec tsx db/native-account-smoke.ts
 if($LASTEXITCODE -ne 0){throw 'Native account acceptance failed.'}
}finally{
 # Restore the ordinary development artifact even when fixture acceptance fails.
 corepack pnpm@12.10.1 --filter @animeoncraft/launcher run tauri:build
 $normalBuild=$LASTEXITCODE
 if(Test-Path -LiteralPath $fixture){Remove-Item -LiteralPath $fixture}
 Pop-Location
 if($normalBuild -ne 0){throw 'Ordinary native build failed; inspect build output.'}
}
