[CmdletBinding()]
param(
    [string] $Repository = 'TheTobiTiger02/Pairly'
)

# One-time setup for automatic Microsoft Store submissions. Asks for the Partner Center identity and
# the Microsoft Entra app credentials (see docs/releasing.md, "Microsoft Store") and stores them as
# GitHub repository variables and secrets. Secret values are typed hidden and piped to `gh`; they are
# never printed. Run it again any time to change a value; press Enter to keep a value as it is.

$ErrorActionPreference = 'Stop'

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'The GitHub CLI (gh) is required. Install it from https://cli.github.com and run `gh auth login` first.'
}

function Save-RepoVariable {
    param([string] $Name, [string] $Prompt)

    $value = Read-Host "$Prompt`n  $Name (Enter to keep)"
    if ($value) {
        & gh variable set $Name --repo $Repository --body $value.Trim()
        if ($LASTEXITCODE -ne 0) { throw "gh variable set $Name failed." }
    }
}

function Save-RepoSecret {
    param([string] $Name, [string] $Prompt)

    $secure = Read-Host "$Prompt`n  $Name (hidden, Enter to keep)" -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $value = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        if ($value) {
            $value.Trim() | & gh secret set $Name --repo $Repository
            if ($LASTEXITCODE -ne 0) { throw "gh secret set $Name failed." }
        }
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

Write-Host "`nPartner Center > Apps and games > Pairly > Product management > Product identity`n" -ForegroundColor Cyan
Save-RepoVariable 'STORE_IDENTITY_NAME' 'Package/Identity/Name'
Save-RepoVariable 'STORE_PUBLISHER' 'Package/Identity/Publisher (starts with CN=)'
Save-RepoVariable 'STORE_PUBLISHER_DISPLAY_NAME' 'Package/Properties/PublisherDisplayName'
Save-RepoVariable 'STORE_DISPLAY_NAME' 'The reserved app name (Pairly)'
Save-RepoVariable 'STORE_PRODUCT_ID' 'Store ID (Product identity, e.g. 9NDLWS4D52HM)'

Write-Host "`nMicrosoft Entra app (entra.microsoft.com) and Partner Center account settings`n" -ForegroundColor Cyan
Save-RepoSecret 'STORE_TENANT_ID' 'Entra > Overview > Tenant ID'
Save-RepoSecret 'STORE_CLIENT_ID' 'Entra > App registrations > your app > Application (client) ID'
Save-RepoSecret 'STORE_CLIENT_SECRET' 'Entra > your app > Certificates & secrets > client secret Value'
Save-RepoSecret 'STORE_SELLER_ID' 'Partner Center > Account settings > Legal info > Developer > Seller ID'

Write-Host "`nDone. Start a release from GitHub: Actions > Release > Run workflow." -ForegroundColor Green
