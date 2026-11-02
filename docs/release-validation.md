# Release validation

This checklist is the release gate for the private Pairly packages. Automated checks are necessary but do not replace the Android hardware matrix.

## Automated preflight

Run from the repository root:

```powershell
$env:DOTNET_CLI_HOME = (Get-Location).Path
dotnet build Pairly.sln --configuration Release
dotnet run --project tests/Pairly.Tests/Pairly.Tests.csproj --configuration Release

Push-Location src\android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
Pop-Location
```

The signed release build (the same script the Release workflow runs) is:

```powershell
.\scripts\Build-Release.ps1 -Version 1.2.3 -WindowsPackage Sideload
```

Do not publish the output unless the script completes the Android signature check.

## Milestone 1 stability gate

Close `Pairly.Windows.exe` before running the automated preflight. The port-conflict test
binds 7816, 7817 and 7818 exclusively, so it fails while the app itself holds them.

Evidence for this gate is the **Diagnostics** page, not recollection. It shows host uptime, the
reconnect-reason histogram, per-session queue and drop counters, and — via **Ask phone** — the
phone's own transport log merged into the same list.

| Scenario | Pass condition |
| --- | --- |
| 72 h idle, screen off, phone untouched | Zero manual reconnects. Any reconnect has a named reason. |
| Wi-Fi off/on x20 | Every cycle returns to Ready. No `heartbeat.timeout` streak longer than one entry per cycle. |
| PC sleep/wake x10 | Desktop socket reconnects on the backoff ladder; no socket handle growth in Task Manager. |
| Phone reboot | Boot receiver reconnects; the watchdog alarm is re-armed. |
| Android app update | Package-replaced receiver reconnects and preserves trust. |
| One UI battery check | App is on the battery-optimisation allowlist **and** absent from Settings > Battery > Background usage limits > Sleeping/Deep sleeping apps. |
| Slow-phone flood | `DroppedMessages` stays 0 while `DroppedTelemetry` absorbs the burst. |
| Storage browser | A path containing `../` is refused. A file over 8 MiB reports the limit instead of stalling. |

## Durable pairing matrix

Use at least two Android versions and two phone vendors. For every row, record the previously paired `hostId`, device ID, endpoint before/after, elapsed reconnect time, and final typed connection state.

| Scenario | Expected result |
| --- | --- |
| Android process killed | Service reconnects without a pairing code. |
| Android reboot | Boot receiver schedules reconnect to the trusted host. |
| Android app update | Package-replaced receiver schedules reconnect and preserves trust. |
| Windows app restart | Every online trusted phone reconnects without setup UI. |
| Windows sign-in | Startup registration launches the host after pairing. |
| PC sleep/wake | Stale generation closes; a replacement session becomes Ready. |
| Phone Wi-Fi loss/restore | Full-jitter retry returns to Ready without busy looping. |
| DHCP address change | Android rediscovers the same `hostId`, verifies the pin, and updates only the endpoint. |
| Wrong host with same name | Connection is rejected; the app never selects it by display name or discovery order. |
| Explicit Forget PC | Revocation tombstone prevents backup recovery from restoring trust. |

A test fails if setup displays another pairing code, sync begins before authentication, or a stale socket changes the replacement connection's state.

## Three-phone clipboard matrix

Pair phones A, B, and C, then verify:

1. PC text copy reaches A, B, and C exactly once.
2. PC PNG copy reaches A, B, and C exactly once.
3. Phone A quick-send text reaches the PC, B, and C exactly once and is not echoed back to A.
4. Phone A quick-send image reaches the PC, B, and C with the same SHA-256 digest.
5. Repeat while B is offline and C is artificially slow. A and the PC must not wait for either recipient.
6. Reconnect B before the ten-minute expiry. History may show the earlier failure, but ephemeral clipboard contents must not be replayed as a new copy.
7. Send simultaneous items from the PC and A. Compare the authoritative relay sequence and per-device applied/failed/unsupported acknowledgements.

Boundary cases: 1 MiB UTF-8 text, 8 MiB PNG, 4096px long edge, a failed 256 KiB chunk, duplicate item ID, and the 64 MiB in-memory cap.

## Mirroring quality gate

Use a hardware-H.264 Android phone on stable 5 GHz Wi-Fi. Keep Auto selected, audio off, and the phone in portrait before rotating to landscape and back.

- Aspect ratio and rotation remain exact with no stretched frames.
- Decoded video stays between 50 and 60 fps when the device and network can sustain it.
- Median control-to-visible response is at most 150 ms; p95 is at most 250 ms.
- A 30-minute session shows no upward latency trend and no stale session after the viewer closes.
- Induced congestion causes a downshift within five seconds and gradual recovery after loss clears.
- ICE restart recovers transport while the existing projection remains alive.
- A genuinely new projection shows the Android consent prompt.
- Playback-capture failure retains responsive video-only mirroring.

Record RTT, available bitrate, encoded/decoded FPS, packet loss, freezes, jitter-buffer delay, encoder limitation, chosen profile, and every adaptation transition.

## UI and accessibility gate

Windows: test 980Ã—640, 1280Ã—720, and a wide desktop at 100%, 150%, and 200% scaling; light, dark, high contrast, keyboard-only focus, and reduced motion.

Android: test API 26, 29, 33, 34, and 36 on phone and tablet widths; light/dark theme, 200% font size, display scaling, TalkBack traversal, first-run onboarding, permission denial, permanently denied permission, and recovery actions.

Photos and notifications must stay scoped to the selected phone. Verify stable scroll during incremental updates, grouped ordering, loading/empty/error states, multi-select photo actions, reply/dismiss notification actions, offline snapshot reconciliation, and the thumbnail-cache memory cap.
