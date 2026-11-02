[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string] $SigningDirectory = [System.IO.Path]::Combine(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData),
        'Pairly',
        'Signing'),
    [switch] $Force
)

$ErrorActionPreference = 'Stop'

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'Private release signing initialization is supported on Windows only.'
}

$resolvedSigningDirectory = [System.IO.Path]::GetFullPath($SigningDirectory)
$androidKeyStore = [System.IO.Path]::Combine($resolvedSigningDirectory, 'pairly-release.p12')
$windowsPfx = [System.IO.Path]::Combine($resolvedSigningDirectory, 'Pairly.PrivateRelease.pfx')
$windowsCer = [System.IO.Path]::Combine($resolvedSigningDirectory, 'Pairly.PrivateRelease.cer')
$secretFile = [System.IO.Path]::Combine($resolvedSigningDirectory, 'release-password.dpapi')
$metadataFile = [System.IO.Path]::Combine($resolvedSigningDirectory, 'signing-metadata.json')
$androidAlias = 'pairly-release'
$publisher = 'CN=Pairly Private Release'

$existingFiles = @($androidKeyStore, $windowsPfx, $windowsCer, $secretFile, $metadataFile) |
    Where-Object { Test-Path -LiteralPath $_ }
if ($existingFiles.Count -gt 0 -and -not $Force) {
    throw "Signing material already exists in '$resolvedSigningDirectory'. Reuse it for every release, or pass -Force only when intentionally rotating the app identity."
}

if (-not $PSCmdlet.ShouldProcess($resolvedSigningDirectory, 'Create durable private release signing identities')) {
    return
}

[System.IO.Directory]::CreateDirectory($resolvedSigningDirectory) | Out-Null

$randomBytes = [byte[]]::new(36)
$randomNumberGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$randomNumberGenerator.GetBytes($randomBytes)
$plainPassword = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
$securePassword = ConvertTo-SecureString -String $plainPassword -AsPlainText -Force

try {
    $encryptedPassword = ConvertFrom-SecureString -SecureString $securePassword
    [System.IO.File]::WriteAllText($secretFile, $encryptedPassword, [System.Text.UTF8Encoding]::new($false))

    $keytool = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if (-not $keytool) {
        # Fall back to JAVA_HOME, then any JDK under the usual install folders.
        $candidates = @()
        if ($env:JAVA_HOME) { $candidates += [System.IO.Path]::Combine($env:JAVA_HOME, 'bin', 'keytool.exe') }
        $candidates += Get-ChildItem -Path 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java', 'C:\Program Files\Android\Android Studio\jbr' `
            -Filter keytool.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
        $knownKeytool = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
        if ($knownKeytool) {
            $keytool = Get-Item -LiteralPath $knownKeytool
        }
    }
    if (-not $keytool) {
        throw 'keytool.exe was not found. Install a JDK before initializing Android signing.'
    }

    if (Test-Path -LiteralPath $androidKeyStore) {
        Remove-Item -LiteralPath $androidKeyStore -Force
    }

    $keytoolPath = if ($keytool.Path) { $keytool.Path } else { $keytool.FullName }
    & $keytoolPath `
        -genkeypair `
        -keystore $androidKeyStore `
        -storetype PKCS12 `
        -storepass $plainPassword `
        -keypass $plainPassword `
        -alias $androidAlias `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -dname 'CN=Pairly Private Release, OU=Private Distribution, O=Pairly, C=DE'
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE."
    }

    $certificate = New-SelfSignedCertificate `
        -Subject $publisher `
        -Type Custom `
        -KeyAlgorithm RSA `
        -KeyLength 3072 `
        -HashAlgorithm SHA256 `
        -KeyUsage DigitalSignature `
        -KeyExportPolicy Exportable `
        -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3') `
        -CertStoreLocation 'Cert:\CurrentUser\My' `
        -FriendlyName 'Pairly private MSIX signing' `
        -NotAfter ((Get-Date).AddYears(10))

    Export-PfxCertificate -Cert $certificate -FilePath $windowsPfx -Password $securePassword -Force | Out-Null
    Export-Certificate -Cert $certificate -FilePath $windowsCer -Type CERT -Force | Out-Null

    $metadata = [ordered]@{
        schemaVersion = 1
        createdUtc = [DateTimeOffset]::UtcNow.ToString('O')
        androidKeyStore = $androidKeyStore
        androidAlias = $androidAlias
        windowsPfx = $windowsPfx
        windowsCertificate = $windowsCer
        windowsPublisher = $certificate.Subject
        windowsThumbprint = $certificate.Thumbprint
        passwordProtection = 'Windows DPAPI CurrentUser'
    } | ConvertTo-Json
    [System.IO.File]::WriteAllText($metadataFile, $metadata, [System.Text.UTF8Encoding]::new($false))

    Write-Host "Private signing identities created in $resolvedSigningDirectory"
    Write-Warning 'Back up this directory securely. Losing or rotating the Android key prevents in-place app upgrades.'
    Write-Warning 'Certificate trust is unchanged. Import only the public CER into LocalMachine\TrustedPeople on PCs that should install this private release.'
}
finally {
    if ($randomBytes) {
        [Array]::Clear($randomBytes, 0, $randomBytes.Length)
    }
    if ($randomNumberGenerator) {
        $randomNumberGenerator.Dispose()
    }
    $plainPassword = $null
    $securePassword = $null
}
