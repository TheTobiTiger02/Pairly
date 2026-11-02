[CmdletBinding()]
param(
    # Semantic version, e.g. 1.3.1 (Velopack does not accept four-part versions).
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string] $Version,
    [string] $OutputDirectory
)

# Builds the GitHub installer (Pairly-Setup.exe) and the Velopack update packages that installed
# copies download. Run Build-PrivateRelease.ps1 first so the ADB-mode server is bundled.
# Requires the vpk tool: dotnet tool install -g vpk --version 1.2.161

$ErrorActionPreference = 'Stop'

if (-not (Get-Command vpk -ErrorAction SilentlyContinue)) {
    throw 'vpk is required. Install it with: dotnet tool install -g vpk --version 1.2.161'
}

$repositoryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($PSScriptRoot, '..'))
if (-not $OutputDirectory) {
    $OutputDirectory = [System.IO.Path]::Combine($repositoryRoot, 'artifacts', 'release', 'velopack')
}
$publishDirectory = [System.IO.Path]::Combine($repositoryRoot, 'artifacts', 'release', '.staging', 'velopack')
if (Test-Path -LiteralPath $publishDirectory) {
    Remove-Item -LiteralPath $publishDirectory -Recurse -Force
}
[System.IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null

$windowsProject = [System.IO.Path]::Combine($repositoryRoot, 'src', 'windows', 'Pairly.Windows', 'Pairly.Windows.csproj')
& dotnet publish $windowsProject `
    --configuration Release `
    --runtime win-x64 `
    --self-contained true `
    --output $publishDirectory `
    -p:Version=$Version `
    -p:DebugType=None `
    -p:DebugSymbols=false
if ($LASTEXITCODE -ne 0) {
    throw "dotnet publish failed with exit code $LASTEXITCODE."
}

# The pack ID must differ from 'Pairly': Velopack installs to %LOCALAPPDATA%\<packId>, and
# %LOCALAPPDATA%\Pairly already holds the app's settings and pairing data.
& vpk pack `
    --packId PairlyDesktop `
    --packVersion $Version `
    --packDir $publishDirectory `
    --mainExe Pairly.Windows.exe `
    --packTitle Pairly `
    --packAuthors 'Tobias Heyn' `
    --icon ([System.IO.Path]::Combine($repositoryRoot, 'src', 'windows', 'Pairly.Windows', 'Assets', 'Pairly.ico')) `
    --outputDir $OutputDirectory
if ($LASTEXITCODE -ne 0) {
    throw "vpk pack failed with exit code $LASTEXITCODE."
}

$setup = [System.IO.Path]::Combine($OutputDirectory, 'PairlyDesktop-win-Setup.exe')
Copy-Item -LiteralPath $setup -Destination ([System.IO.Path]::Combine($OutputDirectory, 'Pairly-Setup.exe')) -Force
Write-Host "Windows installer: $([System.IO.Path]::Combine($OutputDirectory, 'Pairly-Setup.exe'))"
