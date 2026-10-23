$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $root
try {
  corepack pnpm install --frozen-lockfile
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  corepack pnpm run check
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  & (Join-Path $PSScriptRoot 'java-check.ps1')
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    $cargoBin = Join-Path $env:USERPROFILE '.cargo\bin'
    if (-not (Test-Path -LiteralPath (Join-Path $cargoBin 'cargo.exe'))) { throw 'Cargo not found. Install Rust MSVC toolchain.' }
    $env:Path = "$cargoBin;$env:Path"
  }
  cargo fmt --manifest-path apps/launcher/src-tauri/Cargo.toml -- --check
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  cargo check --locked --manifest-path apps/launcher/src-tauri/Cargo.toml
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  cargo test --locked --manifest-path apps/launcher/src-tauri/Cargo.toml
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  corepack pnpm build:native
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  corepack pnpm test:native
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
