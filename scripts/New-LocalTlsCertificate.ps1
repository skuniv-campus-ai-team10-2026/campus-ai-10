$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$tlsDir=Join-Path $projectRoot '.runtime/tls'
New-Item -ItemType Directory -Force $tlsDir | Out-Null
# Ephemeral, workspace-only test certificate. Never adds trust to Windows/browser stores.
$rsa=[Security.Cryptography.RSA]::Create(2048)
$caRsa=[Security.Cryptography.RSA]::Create(2048)
try {
    $caRequest=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=SKOPE workspace test CA',$caRsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $caRequest.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($true,$false,0,$true))
    $caRequest.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new([Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyCertSign,$true))
    $caRequest.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509SubjectKeyIdentifierExtension]::new($caRequest.PublicKey,$false))
    $caCertificate=$caRequest.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-10),[DateTimeOffset]::UtcNow.AddDays(3))
    $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=localhost',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $san=[Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
    $san.AddDnsName('localhost');$san.AddIpAddress([Net.IPAddress]::Parse('127.0.0.1'))
    $request.CertificateExtensions.Add($san.Build())
    $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false,$false,0,$true))
    $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new([Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature,$true))
    $eku=[Security.Cryptography.OidCollection]::new()
    $eku.Add([Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.1')) | Out-Null
    $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($eku,$false))
    $serial=[Security.Cryptography.RandomNumberGenerator]::GetBytes(16)
    $serial[0]=$serial[0] -band 0x7f
    $certificate=$request.Create($caCertificate,[DateTimeOffset]::UtcNow.AddMinutes(-5),[DateTimeOffset]::UtcNow.AddDays(2),$serial)
    try {
        [IO.File]::WriteAllText((Join-Path $tlsDir 'ca-cert.pem'),$caCertificate.ExportCertificatePem())
        [IO.File]::WriteAllText((Join-Path $tlsDir 'localhost-cert.pem'),$certificate.ExportCertificatePem()+"`n"+$caCertificate.ExportCertificatePem())
        [IO.File]::WriteAllText((Join-Path $tlsDir 'localhost-key.pem'),$rsa.ExportPkcs8PrivateKeyPem())
    } finally {$certificate.Dispose();$caCertificate.Dispose()}
} finally {$rsa.Dispose();$caRsa.Dispose()}
Write-Output 'Workspace-only test TLS certificate created in .runtime/tls (valid 2 days; no trust store changes).'
