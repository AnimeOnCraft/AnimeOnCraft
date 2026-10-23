$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $root
try{
 node --input-type=module -e "await (await import('./scripts/prepare-local-game.mjs')).preflight()"
 if($LASTEXITCODE -ne 0){throw 'Local game preflight failed; server settings preserved.'}
 corepack pnpm@12.10.1 --filter @animeoncraft/backend exec tsx db/prepare-local-game.ts
 if($LASTEXITCODE -ne 0){throw 'Local game registration failed.'}
 node scripts/prepare-local-game.mjs
 if($LASTEXITCODE -ne 0){throw 'Local game preparation failed.'}
}finally{Pop-Location}
