[CmdletBinding()]
param(
    [string] $Repository = 'TheTobiTiger02/Pairly',
    [string] $SigningDirectory = [System.IO.Path]::Combine(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData),
        'Pairly',
        'Signing')
)

# Copies the local Android release signing key into the repository's GitHub Actions secrets so the
# Release workflow signs with the same key. Windows packages are signed by the Microsoft Store.
# Values are piped to `gh` and never printed.

$ErrorActionPreference = 'Stop'

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'The GitHub CLI (gh) is required. Install it and run `gh auth login` first.'
}

$resolvedSigningDirectory = [System.IO.Path]::GetFullPath($SigningDirectory)
$metadataFile = [System.IO.Path]::Combine($resolvedSigningDirectory, 'signing-metadata.json')
$secretFile = [System.IO.Path]::Combine($resolvedSigningDirectory, 'release-password.dpapi')
if (-not (Test-Path -LiteralPath $metadataFile) -or -not (Test-Path -LiteralPath $secretFile)) {
    throw "Signing material is missing in '$resolvedSigningDirectory'. Run scripts\Initialize-PrivateSigning.ps1 first."
}

$metadata = Get-Content -LiteralPath $metadataFile -Raw | ConvertFrom-Json
$securePassword = Get-Content -LiteralPath $secretFile -Raw | ConvertTo-SecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)

function Set-RepositorySecret {
    param([string] $Name, [string] $Value)

    $Value | & gh secret set $Name --repo $Repository
    if ($LASTEXITCODE -ne 0) {
        throw "gh secret set $Name failed with exit code $LASTEXITCODE."
    }
}

function ConvertTo-Base64File {
    param([string] $Path)

    [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($Path))
}

try {
    Set-RepositorySecret 'SIGNING_PASSWORD' ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer))
    Set-RepositorySecret 'ANDROID_KEYSTORE_B64' (ConvertTo-Base64File $metadata.androidKeyStore)
    Set-RepositorySecret 'ANDROID_KEY_ALIAS' ([string] $metadata.androidAlias)
    Write-Host "Signing secrets stored in $Repository."
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}
