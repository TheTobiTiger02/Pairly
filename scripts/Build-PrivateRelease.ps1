[CmdletBinding()]
param(
    [ValidatePattern('^\d+\.\d+\.\d+\.\d+$')]
    [string] $PackageVersion = '1.0.1.1',
    [ValidatePattern('^\d+\.\d+\.\d+([-.][0-9A-Za-z.-]+)?$')]
    [string] $AndroidVersionName = '1.0.1.1',
    [ValidateRange(1, 2100000000)]
    [int] $AndroidVersionCode = 10002,
    [string] $SigningDirectory = [System.IO.Path]::Combine(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData),
        'Pairly',
        'Signing'),
    # Sideload: self-signed MSIX for local test installs. Store: unsigned MSIX with the Partner
    # Center identity from STORE_* environment variables; the Store signs it. None: Android only.
    [ValidateSet('Sideload', 'Store', 'None')]
    [string] $WindowsPackage = 'Sideload'
)

$ErrorActionPreference = 'Stop'

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'The private Windows and Android release pipeline must run on Windows.'
}

$repositoryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($PSScriptRoot, '..'))
$releaseRoot = [System.IO.Path]::Combine($repositoryRoot, 'artifacts', 'release')
$stagingRoot = [System.IO.Path]::Combine($releaseRoot, '.staging')
$windowsStaging = [System.IO.Path]::Combine($stagingRoot, 'windows')
$windowsOutput = [System.IO.Path]::Combine($releaseRoot, 'windows')
$androidOutput = [System.IO.Path]::Combine($releaseRoot, 'android')

function Assert-ChildPath {
    param([string] $Parent, [string] $Child)

    $parentPath = [System.IO.Path]::GetFullPath($Parent).TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    $childPath = [System.IO.Path]::GetFullPath($Child)
    if (-not $childPath.StartsWith($parentPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify '$childPath' because it is outside '$parentPath'."
    }
}

Assert-ChildPath -Parent $repositoryRoot -Child $stagingRoot
if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
[System.IO.Directory]::CreateDirectory($windowsStaging) | Out-Null
[System.IO.Directory]::CreateDirectory($windowsOutput) | Out-Null
[System.IO.Directory]::CreateDirectory($androidOutput) | Out-Null

# CI supplies signing material through SL_* environment variables; local builds read the
# DPAPI-protected signing directory created by Initialize-PrivateSigning.ps1.
if ($env:SL_SIGNING_PASSWORD) {
    $metadata = [pscustomobject] @{
        androidKeyStore = $env:SL_ANDROID_KEYSTORE
        androidAlias = $env:SL_ANDROID_KEY_ALIAS
        windowsPfx = $env:SL_WINDOWS_PFX
        windowsCertificate = $env:SL_WINDOWS_CER
        windowsPublisher = $env:SL_WINDOWS_PUBLISHER
    }
    $securePassword = ConvertTo-SecureString -String $env:SL_SIGNING_PASSWORD -AsPlainText -Force
}
else {
    $resolvedSigningDirectory = [System.IO.Path]::GetFullPath($SigningDirectory)
    $metadataFile = [System.IO.Path]::Combine($resolvedSigningDirectory, 'signing-metadata.json')
    $secretFile = [System.IO.Path]::Combine($resolvedSigningDirectory, 'release-password.dpapi')
    if (-not (Test-Path -LiteralPath $metadataFile) -or -not (Test-Path -LiteralPath $secretFile)) {
        throw "Signing material is missing. Run scripts\Initialize-PrivateSigning.ps1 once, then keep that external directory backed up."
    }

    $metadata = Get-Content -LiteralPath $metadataFile -Raw | ConvertFrom-Json
    $securePassword = Get-Content -LiteralPath $secretFile -Raw | ConvertTo-SecureString
}
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$plainPassword = $null

try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)

    $requiredPaths = @($metadata.androidKeyStore)
    if ($WindowsPackage -eq 'Sideload') {
        $requiredPaths += @($metadata.windowsPfx, $metadata.windowsCertificate)
    }
    foreach ($requiredPath in $requiredPaths) {
        if (-not (Test-Path -LiteralPath $requiredPath)) {
            throw "Required signing file '$requiredPath' is missing."
        }
    }

    # Android first: the Windows package bundles the ADB-mode server built here.
    $env:PAIRLY_ANDROID_KEYSTORE = [string] $metadata.androidKeyStore
    $env:PAIRLY_ANDROID_STORE_PASSWORD = $plainPassword
    $env:PAIRLY_ANDROID_KEY_ALIAS = [string] $metadata.androidAlias
    $env:PAIRLY_ANDROID_KEY_PASSWORD = $plainPassword

    $androidRoot = [System.IO.Path]::Combine($repositoryRoot, 'src', 'android')
    Push-Location $androidRoot
    try {
        & .\gradlew.bat :server:assembleRelease :app:assembleRelease "-PPairlyVersionName=$AndroidVersionName" "-PPairlyVersionCode=$AndroidVersionCode"
        if ($LASTEXITCODE -ne 0) {
            throw "Android release build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $msixPath = $null
    if ($WindowsPackage -ne 'None') {
        if ($WindowsPackage -eq 'Store') {
            foreach ($name in 'STORE_IDENTITY_NAME', 'STORE_PUBLISHER', 'STORE_PUBLISHER_DISPLAY_NAME', 'STORE_DISPLAY_NAME') {
                if (-not [Environment]::GetEnvironmentVariable($name)) {
                    throw "$name is required for a Store package; copy it from Partner Center > Product identity."
                }
            }
            $identity = @{
                Name = $env:STORE_IDENTITY_NAME
                Publisher = $env:STORE_PUBLISHER
                PublisherDisplayName = $env:STORE_PUBLISHER_DISPLAY_NAME
                DisplayName = $env:STORE_DISPLAY_NAME
            }
        }
        else {
            $identity = @{
                Name = 'Pairly.Private'
                Publisher = [string] $metadata.windowsPublisher
                PublisherDisplayName = 'Pairly'
                DisplayName = 'Pairly'
            }
        }

        $windowsProject = [System.IO.Path]::Combine($repositoryRoot, 'src', 'windows', 'Pairly.Windows', 'Pairly.Windows.csproj')
        & dotnet publish $windowsProject `
            --configuration Release `
            --runtime win-x64 `
            --self-contained true `
            --output $windowsStaging `
            -p:Version=$AndroidVersionName `
            -p:DebugType=None `
            -p:DebugSymbols=false
        if ($LASTEXITCODE -ne 0) {
            throw "dotnet publish failed with exit code $LASTEXITCODE."
        }

        $assetDirectory = [System.IO.Path]::Combine($windowsStaging, 'Assets')
        & ([System.IO.Path]::Combine($repositoryRoot, 'packaging', 'windows', 'Generate-Assets.ps1')) -OutputDirectory $assetDirectory

        $escape = { param([string] $value) [System.Security.SecurityElement]::Escape($value) }
        $manifestTemplatePath = [System.IO.Path]::Combine($repositoryRoot, 'packaging', 'windows', 'AppxManifest.xml.template')
        $manifest = (Get-Content -LiteralPath $manifestTemplatePath -Raw).
            Replace('__IDENTITY_NAME__', (& $escape $identity.Name)).
            Replace('__PUBLISHER_DISPLAY_NAME__', (& $escape $identity.PublisherDisplayName)).
            Replace('__DISPLAY_NAME__', (& $escape $identity.DisplayName)).
            Replace('__PUBLISHER__', (& $escape $identity.Publisher)).
            Replace('__VERSION__', $PackageVersion)
        [System.IO.File]::WriteAllText(
            [System.IO.Path]::Combine($windowsStaging, 'AppxManifest.xml'),
            $manifest,
            [System.Text.UTF8Encoding]::new($false))

        $windowsKitBin = 'C:\Program Files (x86)\Windows Kits\10\bin'
        $makeAppx = Get-ChildItem -LiteralPath $windowsKitBin -Filter makeappx.exe -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\x64\\makeappx\.exe$' } |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if (-not $makeAppx) {
            throw 'Windows SDK makeappx.exe is required.'
        }

        $msixPath = [System.IO.Path]::Combine($windowsOutput, 'Pairly.msix')
        & $makeAppx.FullName pack /d $windowsStaging /p $msixPath /o
        if ($LASTEXITCODE -ne 0) {
            throw "makeappx failed with exit code $LASTEXITCODE."
        }

        if ($WindowsPackage -eq 'Sideload') {
            $signTool = Get-ChildItem -LiteralPath $windowsKitBin -Filter signtool.exe -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -match '\\x64\\signtool\.exe$' } |
                Sort-Object FullName -Descending |
                Select-Object -First 1
            if (-not $signTool) {
                throw 'Windows SDK signtool.exe is required.'
            }
            & $signTool.FullName sign /fd SHA256 /f $metadata.windowsPfx /p $plainPassword $msixPath
            if ($LASTEXITCODE -ne 0) {
                throw "signtool sign failed with exit code $LASTEXITCODE."
            }
            & $signTool.FullName verify /pa $msixPath
            if ($LASTEXITCODE -ne 0) {
                throw "signtool verification failed with exit code $LASTEXITCODE. In an elevated PowerShell window, import the public CER into Cert:\LocalMachine\TrustedPeople, then retry."
            }
            Copy-Item -LiteralPath $metadata.windowsCertificate -Destination ([System.IO.Path]::Combine($windowsOutput, 'Pairly-private-release.cer')) -Force
        }
    }

    $builtApk = Get-ChildItem -LiteralPath ([System.IO.Path]::Combine($androidRoot, 'app', 'build', 'outputs', 'apk', 'release')) -Filter '*.apk' |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if (-not $builtApk) {
        throw 'The Android release build completed without producing an APK.'
    }
    $releaseApk = [System.IO.Path]::Combine($androidOutput, 'Pairly.apk')
    Copy-Item -LiteralPath $builtApk.FullName -Destination $releaseApk -Force

    $androidSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else {
        [System.IO.Path]::Combine(
            [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData),
            'Android',
            'Sdk')
    }
    $apkSigner = Get-ChildItem -LiteralPath ([System.IO.Path]::Combine($androidSdk, 'build-tools')) -Filter apksigner.bat -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if (-not $apkSigner) {
        throw 'apksigner.bat was not found; refusing to report an unverified Android release.'
    }
    & $apkSigner.FullName verify --verbose --print-certs $releaseApk
    if ($LASTEXITCODE -ne 0) {
        throw "Android signature verification failed with exit code $LASTEXITCODE."
    }

    if ($msixPath) {
        Write-Host "Windows release ($WindowsPackage): $msixPath"
    }
    Write-Host "Android release: $releaseApk"
}
finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    $plainPassword = $null
    $env:PAIRLY_ANDROID_STORE_PASSWORD = $null
    $env:PAIRLY_ANDROID_KEY_PASSWORD = $null
}
