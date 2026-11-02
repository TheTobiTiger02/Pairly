[CmdletBinding()]
param(
    # Release version, e.g. 1.4.2. Everything else is derived from it:
    #   MSIX 1.4.2.0 (the Store requires the last part to be 0), Android versionName 1.4.2 and
    #   versionCode 10402 (major*10000 + minor*100 + patch), Velopack/.NET 1.4.2.
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d{1,2}\.\d{1,2}$')]
    [string] $Version,

    # Store: unsigned MSIX with the Partner Center identity from STORE_* environment variables (the
    # Store signs it). Sideload: MSIX signed with the local self-signed certificate. None: no MSIX.
    [ValidateSet('Store', 'Sideload', 'None')]
    [string] $WindowsPackage = 'Store',

    # Skip the GitHub installer (Pairly-Setup.exe + Velopack update feed).
    [switch] $NoInstaller,

    # Build everything without Android release signing (CI dry runs). The APK is not distributable.
    [switch] $Unsigned,

    [string] $SigningDirectory = [System.IO.Path]::Combine(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData),
        'Pairly',
        'Signing')
)

# Builds every release artifact from one version number:
#   artifacts/release/android/Pairly.apk
#   artifacts/release/windows/Pairly.msix
#   artifacts/release/velopack/Pairly-Setup.exe (+ releases.win.json, *.nupkg)
# The Windows app is published once and that output feeds both the MSIX and the installer.

$ErrorActionPreference = 'Stop'

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'The release build must run on Windows (MSIX tools and the WPF app are Windows-only).'
}

$parts = $Version.Split('.') | ForEach-Object { [int] $_ }
$packageVersion = "$Version.0"
$versionCode = $parts[0] * 10000 + $parts[1] * 100 + $parts[2]

$repositoryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($PSScriptRoot, '..'))
$releaseRoot = [System.IO.Path]::Combine($repositoryRoot, 'artifacts', 'release')
$stagingRoot = [System.IO.Path]::Combine($releaseRoot, '.staging')
$appStaging = [System.IO.Path]::Combine($stagingRoot, 'app')
$msixStaging = [System.IO.Path]::Combine($stagingRoot, 'msix')
$windowsOutput = [System.IO.Path]::Combine($releaseRoot, 'windows')
$androidOutput = [System.IO.Path]::Combine($releaseRoot, 'android')
$velopackOutput = [System.IO.Path]::Combine($releaseRoot, 'velopack')

function Assert-ChildPath {
    param([string] $Parent, [string] $Child)

    $parentPath = [System.IO.Path]::GetFullPath($Parent).TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    $childPath = [System.IO.Path]::GetFullPath($Child)
    if (-not $childPath.StartsWith($parentPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify '$childPath' because it is outside '$parentPath'."
    }
}

function Find-WindowsSdkTool {
    param([string] $Name)

    $tool = Get-ChildItem -LiteralPath 'C:\Program Files (x86)\Windows Kits\10\bin' -Filter $Name -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "\\x64\\$([regex]::Escape($Name))$" } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if (-not $tool) {
        throw "Windows SDK $Name is required."
    }
    $tool.FullName
}

Assert-ChildPath -Parent $repositoryRoot -Child $stagingRoot
if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
foreach ($directory in $appStaging, $windowsOutput, $androidOutput, $velopackOutput) {
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
}

Write-Host "Pairly $Version (MSIX $packageVersion, Android versionCode $versionCode)"

# ---------------------------------------------------------------------------------------------
# Signing material. CI passes it through PAIRLY_* environment variables; local builds read the
# DPAPI-protected directory created by Initialize-PrivateSigning.ps1.
# ---------------------------------------------------------------------------------------------
$metadata = $null
$plainPassword = $null
$needsSigning = (-not $Unsigned) -or $WindowsPackage -eq 'Sideload'
if ($needsSigning) {
    if ($env:PAIRLY_SIGNING_PASSWORD) {
        $metadata = [pscustomobject] @{
            androidKeyStore = $env:PAIRLY_SIGNING_KEYSTORE
            androidAlias = $env:PAIRLY_SIGNING_KEY_ALIAS
            windowsPfx = $env:PAIRLY_SIGNING_WINDOWS_PFX
            windowsCertificate = $env:PAIRLY_SIGNING_WINDOWS_CER
            windowsPublisher = $env:PAIRLY_SIGNING_WINDOWS_PUBLISHER
        }
        $plainPassword = $env:PAIRLY_SIGNING_PASSWORD
    }
    else {
        $resolvedSigningDirectory = [System.IO.Path]::GetFullPath($SigningDirectory)
        $metadataFile = [System.IO.Path]::Combine($resolvedSigningDirectory, 'signing-metadata.json')
        $secretFile = [System.IO.Path]::Combine($resolvedSigningDirectory, 'release-password.dpapi')
        if (-not (Test-Path -LiteralPath $metadataFile) -or -not (Test-Path -LiteralPath $secretFile)) {
            throw "Signing material is missing. Run scripts\Initialize-PrivateSigning.ps1 once (and keep that directory backed up), or pass -Unsigned for a test build."
        }
        $metadata = Get-Content -LiteralPath $metadataFile -Raw | ConvertFrom-Json
        $securePassword = Get-Content -LiteralPath $secretFile -Raw | ConvertTo-SecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        try { $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
}

try {
    # -----------------------------------------------------------------------------------------
    # 1. Android: the app APK plus the ADB-mode server that the Windows app bundles.
    # -----------------------------------------------------------------------------------------
    if (-not $Unsigned) {
        if (-not (Test-Path -LiteralPath $metadata.androidKeyStore)) {
            throw "Android keystore '$($metadata.androidKeyStore)' is missing."
        }
        $env:PAIRLY_ANDROID_KEYSTORE = [string] $metadata.androidKeyStore
        $env:PAIRLY_ANDROID_STORE_PASSWORD = $plainPassword
        $env:PAIRLY_ANDROID_KEY_ALIAS = [string] $metadata.androidAlias
        $env:PAIRLY_ANDROID_KEY_PASSWORD = $plainPassword
    }

    $androidRoot = [System.IO.Path]::Combine($repositoryRoot, 'src', 'android')
    Push-Location $androidRoot
    try {
        & .\gradlew.bat --no-daemon :server:assembleRelease :app:assembleRelease "-PPairlyVersionName=$Version" "-PPairlyVersionCode=$versionCode"
        if ($LASTEXITCODE -ne 0) {
            throw "Android release build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $serverApk = [System.IO.Path]::Combine($androidRoot, 'server', 'build', 'outputs', 'apk', 'release', 'server-release.apk')
    if (-not (Test-Path -LiteralPath $serverApk)) {
        throw "The ADB-mode server was not built ($serverApk); the Windows app must bundle it."
    }

    $builtApk = Get-ChildItem -LiteralPath ([System.IO.Path]::Combine($androidRoot, 'app', 'build', 'outputs', 'apk', 'release')) -Filter '*.apk' |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if (-not $builtApk) {
        throw 'The Android release build completed without producing an APK.'
    }
    $releaseApk = [System.IO.Path]::Combine($androidOutput, 'Pairly.apk')
    Copy-Item -LiteralPath $builtApk.FullName -Destination $releaseApk -Force

    if (-not $Unsigned) {
        $androidSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else {
            [System.IO.Path]::Combine([Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData), 'Android', 'Sdk')
        }
        $apkSigner = Get-ChildItem -LiteralPath ([System.IO.Path]::Combine($androidSdk, 'build-tools')) -Filter apksigner.bat -Recurse -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if (-not $apkSigner) {
            throw 'apksigner.bat was not found; refusing to report an unverified Android release.'
        }
        & $apkSigner.FullName verify --print-certs $releaseApk
        if ($LASTEXITCODE -ne 0) {
            throw "Android signature verification failed with exit code $LASTEXITCODE."
        }
    }
    Write-Host "Android: $releaseApk"

    # -----------------------------------------------------------------------------------------
    # 2. Windows app, published once.
    # -----------------------------------------------------------------------------------------
    $windowsProject = [System.IO.Path]::Combine($repositoryRoot, 'src', 'windows', 'Pairly.Windows', 'Pairly.Windows.csproj')
    & dotnet publish $windowsProject `
        --configuration Release `
        --runtime win-x64 `
        --self-contained true `
        --output $appStaging `
        -p:Version=$Version `
        -p:DebugType=None `
        -p:DebugSymbols=false
    if ($LASTEXITCODE -ne 0) {
        throw "dotnet publish failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath ([System.IO.Path]::Combine($appStaging, 'adb', 'pairly-server.jar')))) {
        throw 'The published Windows app is missing adb\pairly-server.jar.'
    }

    # -----------------------------------------------------------------------------------------
    # 3. MSIX (Microsoft Store or sideload).
    # -----------------------------------------------------------------------------------------
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

        Copy-Item -LiteralPath $appStaging -Destination $msixStaging -Recurse
        & ([System.IO.Path]::Combine($repositoryRoot, 'packaging', 'windows', 'Generate-Assets.ps1')) -OutputDirectory ([System.IO.Path]::Combine($msixStaging, 'Assets'))

        $escape = { param([string] $value) [System.Security.SecurityElement]::Escape($value) }
        $manifestTemplatePath = [System.IO.Path]::Combine($repositoryRoot, 'packaging', 'windows', 'AppxManifest.xml.template')
        $manifest = (Get-Content -LiteralPath $manifestTemplatePath -Raw).
            Replace('__IDENTITY_NAME__', (& $escape $identity.Name)).
            Replace('__PUBLISHER_DISPLAY_NAME__', (& $escape $identity.PublisherDisplayName)).
            Replace('__DISPLAY_NAME__', (& $escape $identity.DisplayName)).
            Replace('__PUBLISHER__', (& $escape $identity.Publisher)).
            Replace('__VERSION__', $packageVersion)
        [System.IO.File]::WriteAllText(
            [System.IO.Path]::Combine($msixStaging, 'AppxManifest.xml'),
            $manifest,
            [System.Text.UTF8Encoding]::new($false))

        $msixPath = [System.IO.Path]::Combine($windowsOutput, 'Pairly.msix')
        & (Find-WindowsSdkTool 'makeappx.exe') pack /d $msixStaging /p $msixPath /o
        if ($LASTEXITCODE -ne 0) {
            throw "makeappx failed with exit code $LASTEXITCODE."
        }

        if ($WindowsPackage -eq 'Sideload') {
            $signTool = Find-WindowsSdkTool 'signtool.exe'
            & $signTool sign /fd SHA256 /f $metadata.windowsPfx /p $plainPassword $msixPath
            if ($LASTEXITCODE -ne 0) {
                throw "signtool sign failed with exit code $LASTEXITCODE."
            }
            Copy-Item -LiteralPath $metadata.windowsCertificate -Destination ([System.IO.Path]::Combine($windowsOutput, 'Pairly-private-release.cer')) -Force
        }
        Write-Host "Windows ($WindowsPackage): $msixPath"
    }

    # -----------------------------------------------------------------------------------------
    # 4. GitHub installer: Pairly-Setup.exe + Velopack update feed.
    # -----------------------------------------------------------------------------------------
    if (-not $NoInstaller) {
        if (-not (Get-Command vpk -ErrorAction SilentlyContinue)) {
            $props = Get-Content -LiteralPath ([System.IO.Path]::Combine($repositoryRoot, 'Directory.Build.props')) -Raw
            $vpkVersion = [regex]::Match($props, '<VelopackVersion>([^<]+)</VelopackVersion>').Groups[1].Value
            throw "vpk is required. Install it with: dotnet tool install -g vpk --version $vpkVersion"
        }

        # The pack ID must differ from 'Pairly': Velopack installs to %LOCALAPPDATA%\<packId>, and
        # %LOCALAPPDATA%\Pairly already holds the app's settings and pairing data.
        & vpk pack `
            --packId PairlyDesktop `
            --packVersion $Version `
            --packDir $appStaging `
            --mainExe Pairly.Windows.exe `
            --packTitle Pairly `
            --packAuthors 'Tobias Heyn' `
            --icon ([System.IO.Path]::Combine($repositoryRoot, 'src', 'windows', 'Pairly.Windows', 'Assets', 'Pairly.ico')) `
            --outputDir $velopackOutput
        if ($LASTEXITCODE -ne 0) {
            throw "vpk pack failed with exit code $LASTEXITCODE."
        }
        $setup = [System.IO.Path]::Combine($velopackOutput, 'PairlyDesktop-win-Setup.exe')
        Copy-Item -LiteralPath $setup -Destination ([System.IO.Path]::Combine($velopackOutput, 'Pairly-Setup.exe')) -Force
        Write-Host "Installer: $([System.IO.Path]::Combine($velopackOutput, 'Pairly-Setup.exe'))"
    }
}
finally {
    $plainPassword = $null
    $env:PAIRLY_ANDROID_STORE_PASSWORD = $null
    $env:PAIRLY_ANDROID_KEY_PASSWORD = $null
}
