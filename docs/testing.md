# Testing

## Automated verification

Run from the repository root:

```powershell
$env:DOTNET_CLI_HOME = (Get-Location).Path
dotnet build Pairly.sln --configuration Release
dotnet run --project tests/Pairly.Tests/Pairly.Tests.csproj --configuration Release

Push-Location src\android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
Pop-Location
```

The .NET harness covers protocol compatibility, pairing and trusted reconnect, checksummed concurrent trust writes, redundant backup recovery, revocation tombstones, host identity persistence, challenge authentication, socket generations, bounded send queues, clipboard fan-out/order/acknowledgements/PNG/chunk limits, media cursor snapshots, notification reconciliation, mirror leases/adaptation markup, brokers, and embedded-host lifecycle.

Android unit tests cover pinned discovery identity, endpoint formatting, v1 trust migration, challenge authentication, reconnect backoff, clipboard limits, helper generations/revocation/restart, startup baselines, sensitive capture policy, text/image ordering, retry identity/expiry, active image retention, media paging/cursors, and notification metadata. The .NET harness also exercises Windows clipboard notifications and STA application, text/image echoes, stable retries, delayed image delivery, and session replacement. These tests briefly replace and restore the Windows clipboard; run them with the tray app stopped. Physical background behavior still requires the matrix below.

## Background clipboard matrix

Use one Windows PC and two physical Android devices with Pairly backgrounded, Samsung or another normal keyboard selected, Wireless debugging off, and no mirror session. Record actual evidence and unrun cases in `docs/clipboard-validation.md`.

- Copy text and an image from each phone, and from Windows. Verify the other two native clipboards and exactly one relay publication per copy.
- Keep a received Android image on the clipboard for more than ten minutes, then paste it into another app.
- Copy several items while disconnected, reconnect, and verify only the newest unexpired unsent copy is retried with its original item ID. Received history must not replay.
- Delay image preparation/application and follow it with text or another image; verify the newer copy remains current.
- Stop/restart Shizuku, revoke/regrant permission, close/reopen the activity, recover the phone process, and reboot. Verify automatic rebind or the displayed Shizuku restart action.
- Start Windows with `--background`, close its main window to the tray, restart it, and verify clipboard sync and the existing startup registration.

Builds and simulated lifecycle tests cannot satisfy the physical background completion gate.

## Release gates

Use `docs/release-validation.md` as the evidence sheet. A private release is not complete until it records:

- reconnect after process kill, both reboots, package update, DHCP change, sleep/wake, and Wi-Fi interruption without another pairing code;
- three-phone PC → A/B/C and A → PC/B/C clipboard delivery exactly once, including slow/offline recipients and simultaneous publications;
- a 30-minute hardware-H.264 5 GHz mirror run with rotation, congestion recovery, and measured median/p95 control latency;
- Windows at 980×640 and wider, 100/150/200% scaling, light/dark/high contrast, keyboard focus, and reduced motion, plus the custom title bar (drag, double-click maximize, Snap Layouts on the maximize button) and the solid background that replaces Mica before Windows 11 22H2;
- Android API 26, 29, 33, 34, and 36 on phone/tablet widths, 200% fonts, TalkBack, permission denial, and recovery;
- successful Windows MSIX and Android APK signature verification.

Do not convert an unrun device scenario into a pass. Preserve the observed build output, device model/API, network, test time, and failure details.

## Manual feature checks

Pair at least three phones, select each phone in Windows, and verify that photos, notifications, messages, files, and mirroring never show data from another selection. Clipboard history should show global relay metadata without retaining expired content. Confirm that phone notification dismissal and local Windows clearing are visually distinct, photo selection and lightbox preserve scroll, mirror unload releases its lease, and an unavailable Android permission produces an actionable state rather than a raw exception.
