$ErrorActionPreference = 'Stop'

function New-DevSecret {
  param([int]$Length = 24)
  $bytes = [byte[]]::new($Length)
  $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
  return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

$envFile = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\.env'))
if (Test-Path -LiteralPath $envFile) {
  $existing = [System.IO.File]::ReadAllText($envFile)
  if ($existing -notmatch '(?m)^AOC_CURSOR_SECRET=') {
    [System.IO.File]::AppendAllText($envFile, "`nAOC_CURSOR_SECRET=$(New-DevSecret)`n", [System.Text.UTF8Encoding]::new($false))
    Write-Host 'Added generated pagination signing key; existing secrets unchanged.'
  }
  foreach ($secretName in @('AOC_MFA_KEY','AOC_AUTH_PRIVACY_KEY','AOC_CSRF_KEY')) {
    if ($existing -notmatch "(?m)^${secretName}=") {
      [System.IO.File]::AppendAllText($envFile, "`n${secretName}=$(New-DevSecret -Length 32)`n", [System.Text.UTF8Encoding]::new($false))
    }
  }
  foreach ($setting in @('AOC_AUTH_ENABLED=false','AOC_MFA_KEY_ID=development-v1','AOC_AUTH_ORIGIN=https://localhost','AOC_AUTH_MAIL_MODE=local')) {
    $settingName = $setting.Split('=')[0]
    if ($existing -notmatch "(?m)^${settingName}=") {
      [System.IO.File]::AppendAllText($envFile, "`n${setting}`n", [System.Text.UTF8Encoding]::new($false))
    }
  }
  Write-Host ".env already exists; existing secrets were preserved."
  exit 0
}

$appPassword = New-DevSecret
$migrationPassword = New-DevSecret
$valkeyPassword = New-DevSecret
$lines = @(
  "AOC_DEV_APP_DB_PASSWORD=$appPassword",
  "AOC_DEV_MIGRATION_DB_PASSWORD=$migrationPassword",
  "AOC_DEV_VALKEY_PASSWORD=$valkeyPassword",
  "AOC_CURSOR_SECRET=$(New-DevSecret)",
  "AOC_MFA_KEY=$(New-DevSecret -Length 32)",
  "AOC_AUTH_PRIVACY_KEY=$(New-DevSecret -Length 32)",
  "AOC_CSRF_KEY=$(New-DevSecret -Length 32)",
  "AOC_AUTH_ENABLED=false",
  "AOC_MFA_KEY_ID=development-v1",
  "AOC_AUTH_ORIGIN=https://localhost",
  "AOC_AUTH_MAIL_MODE=local",
  "DATABASE_URL=postgresql://anime_app:$appPassword@127.0.0.1:54329/animeoncraft?schema=public",
  "MIGRATION_DATABASE_URL=postgresql://anime_migrator:$migrationPassword@127.0.0.1:54329/animeoncraft?schema=public",
  "VALKEY_URL=redis://default:$valkeyPassword@127.0.0.1:56379/0",
  "PUBLIC_HOST=127.0.0.1",
  "PUBLIC_PORT=3000",
  "INTEGRATION_HOST=127.0.0.1",
  "INTEGRATION_PORT=3001",
  "NODE_ENV=development"
)
[System.IO.File]::WriteAllLines($envFile, $lines, [System.Text.UTF8Encoding]::new($false))
Write-Host "Created local .env with generated development secrets."
