# Private release signing

Smartphone Link uses one durable private signing identity per platform. The keys are deliberately stored outside the repository because replacing the Android signer prevents an installed app from receiving in-place updates.

## One-time setup

Run this from the repository root in PowerShell:

```powershell
.\scripts\Initialize-PrivateSigning.ps1
```

The script creates the Android PKCS#12 keystore, a Windows code-signing certificate, and a DPAPI-protected password under `%LOCALAPPDATA%\SmartphoneLink\Signing`. It does not modify Windows certificate trust. Back up the signing directory securely; it is not sufficient to back up the repository.

The public Windows certificate is safe to distribute. Before the first build, use an elevated PowerShell window to import that `.cer` into **Local Computer → Trusted People** so Windows can validate the private package signature. Do not import it into Trusted Root Certification Authorities. Do not distribute the `.pfx`, Android `.p12`, DPAPI password file, or signing metadata.

## Build and verify both installers

```powershell
.\scripts\Build-PrivateRelease.ps1
```

The build is self-contained for Windows, signs both packages, verifies both signatures, and writes:

- `artifacts/release/windows/SmartphoneLink.msix`
- `artifacts/release/windows/SmartphoneLink-private-release.cer`
- `artifacts/release/android/SmartphoneLink.apk`

For the first install on another Windows PC, an administrator must import the public `.cer` into **Local Computer → Trusted People**, then install the MSIX. Do not import the private `.pfx` on another machine.

Version overrides are explicit and do not require source edits:

```powershell
.\scripts\Build-PrivateRelease.ps1 `
  -PackageVersion 1.1.0.0 `
  -AndroidVersionName 1.1.0 `
  -AndroidVersionCode 10100
```

Only rotate the signing directory after deciding to require a clean reinstall on every paired phone.

## Publishing through GitHub Actions

Releases are normally built by `.github/workflows/release.yml` instead of locally.

1. Once, copy the signing identity into the repository secrets (requires `gh auth login`):

   ```powershell
   .\scripts\Publish-SigningSecrets.ps1
   ```

   This sets `SIGNING_PASSWORD`, `ANDROID_KEYSTORE_B64`, `ANDROID_KEY_ALIAS`, `WINDOWS_PFX_B64`, `WINDOWS_CER_B64`, and `WINDOWS_PUBLISHER`. Values are never printed.

2. For each release, push a tag:

   ```powershell
   git tag v1.2.0
   git push origin v1.2.0
   ```

   The tag is the single version source: the MSIX becomes `1.2.0.0`, Android `versionName` `1.2.0` and `versionCode` `10200` (`major*10000 + minor*100 + patch`). Minor and patch must stay below 100.

The release contains `SmartphoneLink.msix`, `SmartphoneLink.appinstaller`, `SmartphoneLink-private-release.cer`, and `SmartphoneLink.apk`.

## Automatic updates

- **Windows** installs from `SmartphoneLink.appinstaller`, which points at `releases/latest/download/SmartphoneLink.appinstaller`. Windows checks it on every launch and in the background and installs newer packages silently. A PC that installed the raw `.msix` must open the `.appinstaller` once to opt in.
- **Android** queries the GitHub latest-release API every 12 hours from the connection service and from **Settings → Check for updates**, then installs `SmartphoneLink.apk` through `PackageInstaller`. Android requires confirmation until the app is its own installer of record; after the first self-update, Android 12+ installs later updates without prompting. Debug builds never self-update.

To build a release locally with update metadata, pass `-ReleaseBaseUrl` and `-LatestBaseUrl` to `Build-PrivateRelease.ps1`.
