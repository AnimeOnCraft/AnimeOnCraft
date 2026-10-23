param(
  [Parameter(Mandatory = $true)][ValidateSet('bootstrap','transfer','recover-mfa')][string]$Action,
  [Parameter(Mandatory = $true)][string]$UserId,
  [string]$TargetId
)
$ErrorActionPreference = 'Stop'
function Read-OperatorSecret([string]$Prompt) {
  $secure = Read-Host -Prompt $Prompt -AsSecureString
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
}
$command = @{
  action = $Action; user_id = $UserId
  password = (Read-OperatorSecret 'Existing account password')
  proof = (Read-OperatorSecret 'MFA code; leave empty for first OWNER setup without MFA')
}
if ($Action -eq 'transfer') {
  if (-not $TargetId) { throw 'TargetId is required for transfer' }
  $command.target_id = $TargetId
  $command.target_password = Read-OperatorSecret 'New owner account password'
  $command.target_proof = Read-OperatorSecret 'New owner MFA proof'
}
Push-Location ([System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\apps\backend')))
$previousEncoding = $OutputEncoding
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
try {
  $command | ConvertTo-Json -Compress | & node --import ./node_modules/tsx/dist/loader.mjs ./db/owner.ts
  if ($LASTEXITCODE -ne 0) { throw 'Local OWNER command failed' }
} finally { $OutputEncoding = $previousEncoding; $command.Clear(); Pop-Location }
