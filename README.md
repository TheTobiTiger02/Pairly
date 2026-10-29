# Smartphone Link

Smartphone Link is a Windows + Android companion app for connecting multiple trusted Android phones to one PC on the same network. The desktop app uses WPF and an embedded ASP.NET Core host; the Android app uses Jetpack Compose. Screen mirroring remains WebRTC + MediaProjection + WebView2.

## Current product

- Phone Link-inspired Windows shell with a selected-device rail and Messages, Photos, Files, Phone screen, Notifications, Clipboard, and Settings destinations.
- Android first-run onboarding plus Home, Features, and Settings, with typed connection state and actionable permission cards.
- Stable PC identity, pinned WSS, challenge-response authentication, encrypted trust data, endpoint rediscovery, generation-safe sockets, bounded send queues, heartbeats, and reconnect after boot or package update.
- Automatic text and PNG clipboard sync between the Windows tray app and all paired phones, with Shizuku background capture, ordered relay, verified chunks, and per-device acknowledgements.
- Selected-phone photo and notification views with snapshot reconciliation, richer grouping metadata, cursor paging, and bounded caches.
- One responsive mirror at a time with typed configuration, telemetry, leases, retry/ICE recovery, aspect/rotation preservation, H.264 preference, VP8 fallback, and adaptive quality.

Android background clipboard sync requires Shizuku to be installed, running, and allowed for Smartphone Link. Start it once through Wireless debugging; the clipboard helper turns Wireless debugging off by default after a healthy call. Keep Developer options and USB debugging enabled, and follow Shizuku's restart guidance. Normal keyboards and a closed phone activity are supported. Without Shizuku, foreground capture and Share/notification/Quick Settings sending remain available. Sensitive Android clips are skipped.

## Repository layout

- `src/shared/SmartphoneLink.Protocol` — versioned JSON envelopes and payload contracts.
- `src/shared/SmartphoneLink.Core` — trust, clipboard, media, notification, SMS, transfer, and permission services.
- `src/windows/SmartphoneLink.Server` — embedded loopback management API plus pinned LAN WSS device transport.
- `src/windows/SmartphoneLink.Host` — optional server-only developer wrapper.
- `src/windows/SmartphoneLink.Windows` — WPF desktop app; this is the normal Windows entry point.
- `src/android` — Kotlin + Jetpack Compose companion app.
- `tests/SmartphoneLink.Tests` — dependency-free .NET protocol/core/server test harness.
- `packaging/windows` and `scripts` — private MSIX/APK signing and packaging workflow.

## Build and test

```powershell
$env:DOTNET_CLI_HOME = (Get-Location).Path
dotnet build SmartphoneLink.sln --configuration Release
dotnet run --project tests/SmartphoneLink.Tests/SmartphoneLink.Tests.csproj --configuration Release

Push-Location src\android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
Pop-Location
```

The unsigned local outputs are under each project’s `bin` or `build/outputs` directory. Launch only `SmartphoneLink.Windows.exe` for the normal app; it owns the embedded host lifecycle.

## Install

Download the latest assets from [Releases](https://github.com/TheTobiTiger02/Smartphone-Link/releases/latest).

- **Windows:** once per PC, import `SmartphoneLink-private-release.cer` into **Local Computer → Trusted People** from an elevated PowerShell (`Import-Certificate -FilePath .\SmartphoneLink-private-release.cer -CertStoreLocation Cert:\LocalMachine\TrustedPeople`). Then open `SmartphoneLink.appinstaller`. Installing through the `.appinstaller` file (not the `.msix`) makes Windows install new releases automatically when the app starts.
- **Android:** install `SmartphoneLink.apk`. The app checks for new releases every 12 hours and from **Settings → Check for updates**. The first update asks for one confirmation; later updates install without prompting on Android 12+.

## Releases

Pushing a tag like `v1.2.0` runs the Release workflow, which builds, signs and publishes both apps. Signing keys stay outside Git; see `docs/private-release.md` for the one-time setup.

Do not replace the Android signing key between releases: an installed app cannot receive an in-place update signed by a different key.

## Security boundaries

- Management, media-download, and mirror-viewer HTTP endpoints are loopback-only.
- Phones use certificate-pinned WSS and authenticate with a nonce challenge; the trust secret is not sent during reconnect.
- Windows protects trust secrets with DPAPI and redundant checksummed snapshots. Android stores the trusted host encrypted with Android Keystore.
- Clipboard contents are ephemeral, memory-bounded, and removed after expiry; history stores delivery metadata rather than durable clipboard content.
- Release Android builds disable cleartext transport. The debug-only manifest override permits local development when needed.
