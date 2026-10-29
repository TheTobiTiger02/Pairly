# Releases and signing

Windows ships through the Microsoft Store, which signs, hosts, and updates the package. Android ships as a signed APK on GitHub Releases and updates itself. Both are built by `.github/workflows/release.yml` when a version tag is pushed.

## Android signing key

The Android key is durable: replacing it prevents installed apps from receiving in-place updates. It lives outside the repository under `%LOCALAPPDATA%\SmartphoneLink\Signing`, created once by:

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
4. Create the first submission by hand: pricing, markets, age rating, privacy policy URL (`https://github.com/TheTobiTiger02/Smartphone-Link/blob/main/PRIVACY.md`), listing and screenshots, and the `runFullTrust` justification ("Desktop companion app that hosts a LAN server for paired phones"). Upload the MSIX from the artifact.
5. For automated submissions, go to **Account settings → User management → Microsoft Entra applications**, add an application with the **Manager** role, and create a client key. Then:

   ```powershell
   gh variable set STORE_PRODUCT_ID --body '<Store ID, e.g. 9NXXXXXXXXXX>'
   gh secret set STORE_TENANT_ID
   gh secret set STORE_SELLER_ID
   gh secret set STORE_CLIENT_ID
   gh secret set STORE_CLIENT_SECRET
   ```

Until `STORE_IDENTITY_NAME` is set, releases contain Android only. Until `STORE_PRODUCT_ID` is set, the Store package is built but not submitted.

## Releasing

```powershell
git tag v1.2.0
git push origin v1.2.0
```

The tag is the single version source: the MSIX becomes `1.2.0.0` (the Store requires the last part to be 0), Android `versionName` `1.2.0` and `versionCode` `10200` (`major*10000 + minor*100 + patch`). Minor and patch must stay below 100, and every tag must be higher than the previous one.

The workflow submits the MSIX to the Store, where it appears after certification (usually 1–3 business days), and creates a GitHub release with `SmartphoneLink.apk`.

## Automatic updates

- **Windows:** the Microsoft Store updates installed copies.
- **Android:** the app queries the GitHub latest-release API every 12 hours from the connection service and from **Settings → Check for updates**, then installs `SmartphoneLink.apk` through `PackageInstaller`. Android asks for confirmation until the app is its own installer of record; after the first self-update, Android 12+ installs later updates without prompting. Debug builds never self-update.

## Local builds

```powershell
.\scripts\Build-PrivateRelease.ps1 -PackageVersion 1.2.0.0 -AndroidVersionName 1.2.0 -AndroidVersionCode 10200
```

`-WindowsPackage` selects the Windows output:

- `Sideload` (default): MSIX signed with the self-signed certificate from the signing directory, plus its `.cer`, for test installs. The certificate must be in **Local Computer → Trusted People** on the test PC.
- `Store`: unsigned MSIX with the identity from the `STORE_*` environment variables, for manual upload to Partner Center.
- `None`: Android only.
