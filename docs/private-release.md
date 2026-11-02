# Releases and signing

Windows ships through the Microsoft Store, which signs, hosts, and updates the package. Android ships as a signed APK on GitHub Releases and updates itself. Both are built by `.github/workflows/release.yml` when a version tag is pushed.

## Android signing key

The Android key is durable: replacing it prevents installed apps from receiving in-place updates. It lives outside the repository under `%LOCALAPPDATA%\Pairly\Signing`, created once by:

```powershell
.\scripts\Initialize-PrivateSigning.ps1
```

Back up that directory securely, together with the signing password; the `.dpapi` password file only decrypts for the Windows account that created it. Do not distribute the `.p12`, `.pfx`, DPAPI password file, or signing metadata.

Copy the key into the repository secrets once (requires `gh auth login`):

```powershell
.\scripts\Publish-SigningSecrets.ps1
```

This sets `SIGNING_PASSWORD`, `ANDROID_KEYSTORE_B64`, and `ANDROID_KEY_ALIAS`. Values are never printed. GitHub secrets cannot be read back, so the local backup is the only recoverable copy.

## Microsoft Store setup (once)

1. Register an individual developer account at https://storedeveloper.microsoft.com (free).
2. In Partner Center, reserve the app name. From **Product management → Product identity**, store the public identity as repository variables:

   ```powershell
   gh variable set STORE_IDENTITY_NAME --body '<Package/Identity/Name>'
   gh variable set STORE_PUBLISHER --body '<Package/Identity/Publisher, CN=...>'
   gh variable set STORE_PUBLISHER_DISPLAY_NAME --body '<Package/Properties/PublisherDisplayName>'
   gh variable set STORE_DISPLAY_NAME --body '<reserved app name>'
   ```

3. Push a tag (see below). The workflow builds the Store package and attaches it to the run as the `...-store-msix` artifact.
4. Create the first submission by hand: pricing, markets, age rating, privacy policy URL (`https://github.com/TheTobiTiger02/Pairly/blob/main/PRIVACY.md`), listing and screenshots, and the `runFullTrust` justification ("Desktop companion app that hosts a LAN server for paired phones"). Upload the MSIX from the artifact.
5. Store the Store ID so release notes link to the Store page:

   ```powershell
   gh variable set STORE_PRODUCT_ID --body '9NDLWS4D52HM'
   ```

Until `STORE_IDENTITY_NAME` is set, releases contain Android only.

## Releasing

```powershell
git tag v1.2.0
git push origin v1.2.0
```

The tag is the single version source: the MSIX becomes `1.2.0.0` (the Store requires the last part to be 0), Android `versionName` `1.2.0` and `versionCode` `10200` (`major*10000 + minor*100 + patch`). Minor and patch must stay below 100, and every tag must be higher than the previous one.

The workflow creates a GitHub release with `Pairly.apk` and the Windows installer `Pairly-Setup.exe` plus its Velopack update feed (`releases.win.json`, `PairlyDesktop-<version>-full.nupkg`, and a delta package from the previous version), and attaches the Store package to the workflow run as the `Pairly-<version>-store-msix` artifact. Then update the Store by hand:

1. Download the artifact: `gh run download <run id> --repo TheTobiTiger02/Pairly` (or from the run page on GitHub).
2. In Partner Center, open Pairly and choose **Update** on the latest submission.
3. On **Packages**, remove the old MSIX, upload the new one, and **Submit to the Store**. Certification usually takes 1–3 business days.

Partner Center accepts one submission at a time, so do not tag a new version while a submission is still in certification.

### Optional: automatic Store submission

The workflow submits to the Store itself once these secrets exist. They need a Microsoft Entra application with the **Manager** role under **Account settings → User management → Microsoft Entra applications**, which individual accounts can only get with an Entra tenant (for example through a free Azure account).

```powershell
gh secret set STORE_TENANT_ID
gh secret set STORE_SELLER_ID
gh secret set STORE_CLIENT_ID
gh secret set STORE_CLIENT_SECRET
```

## Automatic updates

- **Windows (Store):** the Microsoft Store updates installed copies after certification.
- **Windows (GitHub installer):** Velopack. The app checks the latest GitHub release 20 seconds after start and every 6 hours, downloads newer versions in the background, and installs them the next time Pairly starts, or at once from **Settings → Restart to update**. It installs to `%LOCALAPPDATA%\PairlyDesktop`; settings and pairing data stay in `%LOCALAPPDATA%\Pairly`, so both editions share them. Development builds never update themselves.
- **Android:** the app queries the GitHub latest-release API every 12 hours from the connection service and from **Settings → Check for updates**, then installs `Pairly.apk` through `PackageInstaller`. Android asks for confirmation until the app is its own installer of record; after the first self-update, Android 12+ installs later updates without prompting. Debug builds never self-update.

## Local builds

```powershell
.\scripts\Build-PrivateRelease.ps1 -PackageVersion 1.2.0.0 -AndroidVersionName 1.2.0 -AndroidVersionCode 10200
```

For the GitHub installer, after that run (it builds the ADB server the installer bundles):

```powershell
dotnet tool install -g vpk --version 1.2.161
.\scripts\Build-Velopack.ps1 -Version 1.2.0
```

`-WindowsPackage` selects the Windows output:

- `Sideload` (default): MSIX signed with the self-signed certificate from the signing directory, plus its `.cer`, for test installs. The certificate must be in **Local Computer → Trusted People** on the test PC.
- `Store`: unsigned MSIX with the identity from the `STORE_*` environment variables, for manual upload to Partner Center.
- `None`: Android only.
