param([Parameter(Mandatory = $true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$testRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\apps\backend\.tools'))
$resolved = [System.IO.Path]::GetFullPath($OutputDirectory)
if (-not $resolved.StartsWith($testRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Certificate output must stay in backend test tools directory' }
if ([System.IO.Path]::GetFileName($resolved) -notlike 'mail-*') { throw 'Expected own mail test directory' }
$rsa = [System.Security.Cryptography.RSA]::Create(2048)
try {
  $request = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=localhost', $rsa, [System.Security.Cryptography.HashAlgorithmName]::SHA256, [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
  $san = [System.Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
  $san.AddDnsName('localhost')
  $request.CertificateExtensions.Add($san.Build())
  $certificate = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-1), [DateTimeOffset]::UtcNow.AddDays(1))
  try {
    [System.IO.File]::WriteAllText((Join-Path $resolved 'key.pem'), $rsa.ExportPkcs8PrivateKeyPem())
    [System.IO.File]::WriteAllText((Join-Path $resolved 'certificate.pem'), $certificate.ExportCertificatePem())
  } finally { $certificate.Dispose() }
} finally { $rsa.Dispose() }
