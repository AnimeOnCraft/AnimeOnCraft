$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tool=Join-Path $root '.tools\cargo-audit\bin\cargo-audit.exe'
if(-not (Test-Path -LiteralPath $tool)){throw 'Install cargo-audit 0.22.2 in the project .tools directory; see LAUNCHER_PACK_POLICY.md.'}
& $tool audit --file (Join-Path $root 'apps\launcher\src-tauri\Cargo.lock') --db (Join-Path $root '.tools\rustsec-advisory-db')
exit $LASTEXITCODE
