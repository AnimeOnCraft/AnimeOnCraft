param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$resolved=[IO.Path]::GetFullPath($OutputDirectory)
$name=[IO.Path]::GetFileName($resolved)
if([IO.Path]::GetDirectoryName($resolved) -ne (Join-Path $workspace '.tools') -or $name -notmatch '^(pack|self-update|installer)-acceptance-[a-f0-9]{32}$'){throw 'Expected isolated workspace acceptance directory.'}
$rsa=[Security.Cryptography.RSA]::Create(2048)
try{
 $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=localhost',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
 $san=[Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new();$san.AddDnsName('localhost');$request.CertificateExtensions.Add($san.Build())
 $certificate=$request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-1),[DateTimeOffset]::UtcNow.AddDays(1))
 try{[IO.File]::WriteAllText((Join-Path $resolved 'key.pem'),$rsa.ExportPkcs8PrivateKeyPem());[IO.File]::WriteAllText((Join-Path $resolved 'certificate.pem'),$certificate.ExportCertificatePem())}finally{$certificate.Dispose()}
}finally{$rsa.Dispose()}
