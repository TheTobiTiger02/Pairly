# Releasing Pairly

A release is one click: **Actions → Release → Run workflow → pick `patch`, `minor` or `major` → Run**.

The workflow then:

1. works out the next version from the latest `vX.Y.Z` tag (or uses the exact version you typed);
2. checks that the Microsoft Store has no submission still in certification (and stops before anything is tagged if it does);
3. builds, from that one version:
   - `Pairly.apk`: Android, signed with the release key;
   - `Pairly-Setup.exe` and its Velopack update feed: Windows installer that updates itself;
   - `Pairly.msix`: Microsoft Store package;
4. publishes the GitHub release `vX.Y.Z` with all of those files and automatic release notes (one line per commit since the last release, plus download links);
5. uploads `Pairly.msix` to Partner Center, fills in the Store's **What's new** text from the same notes, and submits it for certification.

Installed apps pick the release up on their own: the Android app and the `.exe` install update themselves from GitHub, and the Store updates its copies after certification (usually 1–3 business days). The README's download buttons always point at the latest release.

If the Store step fails, the GitHub release is still published, and the run summary explains how to retry. **Re-run failed jobs** on the run page repeats only the Store upload.

> Releases are made from `main`. Pushing a tag by hand (`git tag v1.2.3 && git push origin v1.2.3`) still works and does the same thing.

### Versions

The tag is the only version source. For `v1.4.2`:

| Output | Version |
| --- | --- |
| Android `versionName` / `versionCode` | `1.4.2` / `10402` (`major*10000 + minor*100 + patch`) |
| Windows app and installer | `1.4.2` |
| MSIX | `1.4.2.0` (the Store requires the last part to be 0) |

Minor and patch must stay at 99 or lower. Local builds are `0.0.0-dev`, so they never look newer than a release.

### Release options

| Field | Use |
| --- | --- |
| Which part of the version goes up | `patch` for fixes, `minor` for features, `major` for big changes. |
| Or an exact version instead | Optional, e.g. `2.0.0`. Must be higher than the latest release. |
| Optional highlights | One line shown above the automatic change list, e.g. "New design and German translation". |
| Submit to the Microsoft Store | Untick to release only on GitHub, for example while a Store submission is still in certification. |

---

## One-time setup

You need to do this only once. After it, every release is the single click above.

### 1. Android signing key

The Android key must never change: installed apps refuse an update signed with a different key. On Windows, create it once:

```powershell
.\scripts\Initialize-PrivateSigning.ps1
```

It lives under `%LOCALAPPDATA%\Pairly\Signing`. Back that folder up securely together with the signing password; the `.dpapi` password file only decrypts for the Windows account that created it. Then copy the key into the GitHub secrets (requires the [GitHub CLI](https://cli.github.com) and `gh auth login`):

```powershell
.\scripts\Publish-SigningSecrets.ps1
```

This sets `SIGNING_PASSWORD`, `ANDROID_KEYSTORE_B64` and `ANDROID_KEY_ALIAS`. GitHub secrets cannot be read back, so your local backup is the only recoverable copy.

### 2. Microsoft Store

Pairly is already listed as `9NDLWS4D52HM`. To let the workflow submit updates by itself, it needs a Microsoft Entra app with access to your Partner Center account. Individual developer accounts get a free Entra tenant for this.

1. **Create or connect the Entra tenant.** Partner Center → ⚙ **Account settings** → **Organization profile** → **Tenants** → **Create** (pick a name such as `pairly`, and sign up with a new admin user that Partner Center creates) or **Associate** an existing one.
2. **Register the app.** Sign in to <https://entra.microsoft.com> with that tenant's admin → **Identity** → **Applications** → **App registrations** → **New registration**. Name it `Pairly release`, keep "Accounts in this organizational directory only", and select **Register**.
   - On the app's **Overview**, note the **Application (client) ID** and the **Directory (tenant) ID**.
   - **Certificates & secrets** → **New client secret** → description `GitHub`, expiry 24 months → **Add**. Copy the **Value** now; it is shown only once.
3. **Give it access in Partner Center.** ⚙ **Account settings** → **User management** → **Microsoft Entra applications** → **Add Microsoft Entra application** → select `Pairly release` → role **Manager** → **Save**.
4. **Find your Seller ID.** ⚙ **Account settings** → **Legal info** → **Developer** tab → **Seller ID**.
5. **Find the package identity.** **Apps and games** → Pairly → **Product management** → **Product identity**.
6. **Store everything in GitHub:**

   ```powershell
   .\scripts\Set-StoreSecrets.ps1
   ```

   It asks for each value: the package identity and Store ID become repository variables, and the four Entra values become secrets (typed hidden, never printed). Press Enter to skip a value that is already set.

That's it. The next **Run workflow** submits to the Store. When the client secret expires (the expiry date is shown in Entra), create a new one and run `Set-StoreSecrets.ps1` again, entering only `STORE_CLIENT_SECRET`.

The listing text, including a German translation, is in [store-listing.md](store-listing.md). Listing changes (text, screenshots, new languages) are made in Partner Center; the workflow only replaces the package and the **What's new** text.

### What the workflow reads

| Kind | Name | Used for |
| --- | --- | --- |
| Secret | `SIGNING_PASSWORD`, `ANDROID_KEYSTORE_B64`, `ANDROID_KEY_ALIAS` | Signing `Pairly.apk` |
| Variable | `STORE_IDENTITY_NAME`, `STORE_PUBLISHER`, `STORE_PUBLISHER_DISPLAY_NAME`, `STORE_DISPLAY_NAME` | The MSIX package identity |
| Variable | `STORE_PRODUCT_ID` | Store submissions and download links |
| Secret | `STORE_TENANT_ID`, `STORE_CLIENT_ID`, `STORE_CLIENT_SECRET`, `STORE_SELLER_ID` | Uploading to Partner Center |

Without the Store values, releases still publish the APK and the installer on GitHub, and the run summary says the Store was skipped.

---

## Automatic updates

- **Windows (Store):** the Microsoft Store updates installed copies after certification.
- **Windows (`Pairly-Setup.exe`):** Velopack. The app checks the latest GitHub release 20 seconds after start and every 6 hours, downloads newer versions in the background, and installs them on the next start, or immediately from **Settings → Restart to update**. It installs to `%LOCALAPPDATA%\PairlyDesktop`; settings and pairing data stay in `%LOCALAPPDATA%\Pairly`, so both editions share them. Development builds never update themselves.
- **Android:** the app checks the GitHub latest release every 12 hours and from **Settings → Check for updates**, then installs `Pairly.apk` through `PackageInstaller`. The first self-update asks for confirmation; later ones install silently on Android 12+. Debug builds never self-update.

After an update, both apps show the release's **What's new** section once.

## Local builds

On Windows, with the signing key from step 1, the [Android SDK](https://developer.android.com/studio) and the Windows SDK installed:

```powershell
dotnet tool install -g vpk --version 1.2.161   # see VelopackVersion in Directory.Build.props
.\scripts\Build-Release.ps1 -Version 1.2.3 -WindowsPackage Sideload
```

Outputs land in `artifacts\release\{android,windows,velopack}`.

`-WindowsPackage` selects the MSIX:

- `Store` (default): unsigned, with the identity from the `STORE_*` environment variables, for Partner Center.
- `Sideload`: signed with the self-signed certificate from the signing folder, plus its `.cer`, for test installs. The certificate must be in **Local Computer → Trusted People** on the test PC.
- `None`: no MSIX.

`-NoInstaller` skips `Pairly-Setup.exe`. `-Unsigned` builds without the Android key, for testing the pipeline; CI runs it that way in the **Release dry run** workflow whenever the build scripts change.
