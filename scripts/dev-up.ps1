$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  $dockerCandidates = @(
    (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin'),
    (Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin')
  )
  $dockerBin = $dockerCandidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'docker.exe') } | Select-Object -First 1
  if (-not $dockerBin) { throw 'Docker CLI not found. Install Docker Desktop and start its Linux engine.' }
  $env:Path = "$dockerBin;$env:Path"
}
if (-not (Test-Path -LiteralPath (Join-Path $root '.env'))) { & (Join-Path $PSScriptRoot 'dev-env.ps1') }
docker compose --env-file (Join-Path $root '.env') -f (Join-Path $root 'infra\compose\compose.yaml') up -d --wait postgres valkey
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
