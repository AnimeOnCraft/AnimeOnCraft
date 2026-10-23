param([Parameter(Mandatory=$true)][string]$KeyPath)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$key=[IO.Path]::GetFullPath($KeyPath)
if(-not(Test-Path -LiteralPath $key) -or -not(Test-Path -LiteralPath ($key+'.pub'))){throw 'Signing key and public key file are required'}
$password=Read-Host 'Signing key password (Enter if the key has no password)' -AsSecureString
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
$previousPassword=$env:TAURI_SIGNING_PRIVATE_KEY_PASSWORD
try{
 $env:TAURI_SIGNING_PRIVATE_KEY_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
 Push-Location $root
 try{node (Join-Path $PSScriptRoot 'prepare-launcher-update.mjs') --key $key;if($LASTEXITCODE -ne 0){throw 'Local update signing failed; private output was withheld'}}finally{Pop-Location}
}finally{
 $env:TAURI_SIGNING_PRIVATE_KEY_PASSWORD=$previousPassword
 [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer);$password.Dispose()
}
