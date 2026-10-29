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
        'SmartphoneLink',
        'Signing'),
    # When set, also writes SmartphoneLink.appinstaller so installed PCs update from this location.
    [string] $ReleaseBaseUrl,
    [string] $LatestBaseUrl
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

    foreach ($requiredPath in @($metadata.androidKeyStore, $metadata.windowsPfx, $metadata.windowsCertificate)) {
        if (-not (Test-Path -LiteralPath $requiredPath)) {
            throw "Required signing file '$requiredPath' is missing."
        }
    }

    # Android first: the Windows package bundles the ADB-mode server built here.
    $env:SMARTPHONE_LINK_ANDROID_KEYSTORE = [string] $metadata.androidKeyStore
    $env:SMARTPHONE_LINK_ANDROID_STORE_PASSWORD = $plainPassword
    $env:SMARTPHONE_LINK_ANDROID_KEY_ALIAS = [string] $metadata.androidAlias
    $env:SMARTPHONE_LINK_ANDROID_KEY_PASSWORD = $plainPassword

    $androidRoot = [System.IO.Path]::Combine($repositoryRoot, 'src', 'android')
    Push-Location $androidRoot
    try {
        & .\gradlew.bat :server:assembleRelease :app:assembleRelease "-PSmartphoneLinkVersionName=$AndroidVersionName" "-PSmartphoneLinkVersionCode=$AndroidVersionCode"
        if ($LASTEXITCODE -ne 0) {
            throw "Android release build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $windowsProject = [System.IO.Path]::Combine($repositoryRoot, 'src', 'windows', 'SmartphoneLink.Windows', 'SmartphoneLink.Windows.csproj')
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

    $manifestTemplatePath = [System.IO.Path]::Combine($repositoryRoot, 'packaging', 'windows', 'AppxManifest.xml.template')
    $manifest = Get-Content -LiteralPath $manifestTemplatePath -Raw
    $publisher = [System.Security.SecurityElement]::Escape([string] $metadata.windowsPublisher)
    $manifest = $manifest.Replace('__PUBLISHER__', $publisher).Replace('__VERSION__', $PackageVersion)
    [System.IO.File]::WriteAllText(
        [System.IO.Path]::Combine($windowsStaging, 'AppxManifest.xml'),
        $manifest,
        [System.Text.UTF8Encoding]::new($false))

    $windowsKitBin = 'C:\Program Files (x86)\Windows Kits\10\bin'
    $makeAppx = Get-ChildItem -LiteralPath $windowsKitBin -Filter makeappx.exe -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\x64\\makeappx\.exe$' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    $signTool = Get-ChildItem -LiteralPath $windowsKitBin -Filter signtool.exe -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\x64\\signtool\.exe$' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if (-not $makeAppx -or -not $signTool) {
        throw 'Windows SDK makeappx.exe and signtool.exe are required.'
    }

    $msixPath = [System.IO.Path]::Combine($windowsOutput, 'SmartphoneLink.msix')
    & $makeAppx.FullName pack /d $windowsStaging /p $msixPath /o
    if ($LASTEXITCODE -ne 0) {
        throw "makeappx failed with exit code $LASTEXITCODE."
    }
    & $signTool.FullName sign /fd SHA256 /f $metadata.windowsPfx /p $plainPassword $msixPath
    if ($LASTEXITCODE -ne 0) {
        throw "signtool sign failed with exit code $LASTEXITCODE."
    }
    & $signTool.FullName verify /pa $msixPath
    if ($LASTEXITCODE -ne 0) {
        throw "signtool verification failed with exit code $LASTEXITCODE. In an elevated PowerShell window, import the public CER into Cert:\LocalMachine\TrustedPeople, then retry."
    }
    Copy-Item -LiteralPath $metadata.windowsCertificate -Destination ([System.IO.Path]::Combine($windowsOutput, 'SmartphoneLink-private-release.cer')) -Force

    if ($ReleaseBaseUrl) {
        if (-not $LatestBaseUrl) {
            throw '-LatestBaseUrl is required with -ReleaseBaseUrl.'
        }
        $appInstallerTemplatePath = [System.IO.Path]::Combine($repositoryRoot, 'packaging', 'windows', 'SmartphoneLink.appinstaller.template')
        $appInstaller = (Get-Content -LiteralPath $appInstallerTemplatePath -Raw).
            Replace('__PUBLISHER__', $publisher).
            Replace('__VERSION__', $PackageVersion).
            Replace('__RELEASE_BASE_URL__', $ReleaseBaseUrl.TrimEnd('/')).
            Replace('__LATEST_BASE_URL__', $LatestBaseUrl.TrimEnd('/'))
        [System.IO.File]::WriteAllText(
            [System.IO.Path]::Combine($windowsOutput, 'SmartphoneLink.appinstaller'),
            $appInstaller,
            [System.Text.UTF8Encoding]::new($false))
    }

    $builtApk = Get-ChildItem -LiteralPath ([System.IO.Path]::Combine($androidRoot, 'app', 'build', 'outputs', 'apk', 'release')) -Filter '*.apk' |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if (-not $builtApk) {
        throw 'The Android release build completed without producing an APK.'
    }
    $releaseApk = [System.IO.Path]::Combine($androidOutput, 'SmartphoneLink.apk')
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

    Write-Host "Windows release: $msixPath"
    Write-Host "Android release: $releaseApk"
}
finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    $plainPassword = $null
    $env:SMARTPHONE_LINK_ANDROID_STORE_PASSWORD = $null
    $env:SMARTPHONE_LINK_ANDROID_KEY_PASSWORD = $null
}
