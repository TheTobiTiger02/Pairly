# Background clipboard validation

Status: implementation, automated checks, and physical clipboard acceptance passed. Text and still images pasted in WhatsApp between Windows, the S25, and the S21 with Pairly backgrounded, normal keyboards selected, Wireless debugging off, and no mirror running. The final isolated copies each produced one relay publication and successful application on both recipients, without recipient republication or new capture errors.

## Environment

Validation dates: 12–13 October 2026, Europe/Berlin.

| Participant | Environment | Pairing identity |
| --- | --- | --- |
| Windows | Release desktop app, `--background`, embedded host on port 7816 | Existing host identity |
| Galaxy S25 | SM-S931B, Android 17 / API 37, Samsung Keyboard | `android-7e668257f73246be8f5da766d38b93ae` |
| Galaxy S21 | SM-G991B, Android 15 / API 35, Samsung Keyboard | `android-5b6d5d6b9f9045e9bef61bcea14b60bc` |

The final debug APK was installed in place on both phones at approximately 01:18 on 13 October. Its SHA-256 is `c32b0a460c7723b6c486be3cdb51219526e240612d735a991e16ef4967f26fae`. Both helpers reported `wireless debugging setting=0` afterward; ADB listed no devices while the authenticated app sessions remained connected. Samsung's Continue apps on other devices was temporarily disabled for the isolated tests.

## Automated checks

| Check | Result |
| --- | --- |
| .NET harness | 60 passed |
| Release solution build | Passed, 0 warnings, 0 errors |
| Android debug unit tests | 43 passed, 0 failures, 0 errors |
| Android debug assembly | Passed; installed on both phones |
| Android minified release assembly | Passed; release mapping generated |

The Android tests and both APK builds were rerun after the final capture-race fix. The passing .NET checks remain applicable: no .NET source changed afterward. The release APK was built; physical tests used the installed debug APK.

Coverage includes helper lifecycle and stale callbacks, startup baselines, sensitive suppression, text/image limits, origin tracking and echo prevention, newest-only retries with stable identity and expiry, superseding text/image work and failures, session replacement, active image retention, Windows clipboard notifications and STA application, and authenticated relay fan-out.

Commands:

```powershell
dotnet build Pairly.sln --configuration Release --nologo
dotnet tests/Pairly.Tests/bin/Release/net9.0-windows/Pairly.Tests.dll
# From src/android, with Android Studio JBR and the Android SDK configured:
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain
```

The .NET harness changes and restores the native Windows clipboard and starts test listeners, so the desktop app must be exited before running it.

## Physical evidence

| Scenario | Evidence | Result |
| --- | --- | --- |
| S25 text to Windows and S21, final build | Item `d41e3dbcb54b418cac39df6e55a7b8b7`, sequence 31, 23 bytes. Both recipients acknowledged application. User confirmed WhatsApp pasting on both | Passed |
| S25 Gallery photo to Windows and S21, final build | Item `4a1cb5490b8b4136b4fae8d91c11ee40`, sequence 32, 266,377-byte PNG. Both recipients acknowledged application. User confirmed WhatsApp pasting on both | Passed |
| S21 text to Windows and S25, final build | Item `cc43c4f76d3f4e12af5bf942453fde79`, sequence 33, 13 bytes. Both recipients acknowledged application. User confirmed WhatsApp pasting on both | Passed |
| S21 Gallery photo to Windows and S25, final build | Item `8e71254e3189478a9a31dfed47f08a32`, sequence 34, 723,737-byte PNG. Both recipients acknowledged application. User confirmed WhatsApp pasting on both. Windows decoded 1200 × 2666 pixels with that origin ID | Passed |
| Windows text and image to both phones with Wireless debugging off | Native STA copies with Windows hidden in the tray produced sequences 11 and 12. Both phones acknowledged application without republication. User confirmed native WhatsApp image pasting on both | Passed |
| Image pasting after ten-minute transfer expiry | After 00:43, user confirmed pasting the retained image into WhatsApp on all three devices. The transfer expired at 00:41:12; the host content endpoint returned 404, while the native Windows clipboard still decoded the image | Passed |
| Windows close-to-tray | User opened from the tray, closed with X, and confirmed a new Windows text pasted on both phones. Sequence 19 received both acknowledgements at 00:59:57 | Passed |
| S21 reboot recovery | User reported recovery without opening the app or performing an action. The existing pairing reconnected at 00:57:38.315; helper readiness at 00:57:42.643 reported Wireless debugging off | Passed on this device configuration |
| Windows startup and network recovery | After the user chose tray Exit, the exact registered `--background` command started the Release app at 01:23:32. Both phones reconnected automatically. Windows stayed hidden, and relay history remained empty | Passed; actual Windows sign-in was not repeated |
| Ordering reset after host replacement | A native Windows recopy of the retained image produced item `23dc254de4fb4ea2b4f151462b56a934`, new sequence 1. Both phones accepted it despite previously receiving sequence 34. No phone republication or capture error occurred | Passed |
| Shizuku revocation/regrant and restart | Earlier physical runs observed permission changes and automatic helper recovery. Helper lifecycle code is unchanged by the final capture-race fix; lifecycle regressions pass | Passed in earlier physical runs |
| Android process recovery | Earlier physical run recovered connection and monitoring without replacing pairing identities. Final in-place APK replacement also recovered monitoring automatically on both phones | Passed |
| Newest-only offline retry | Earlier physical run retained the latest automatic copy and retried its original identity. Stable-ID, expiry, session, and delayed-clear regression tests pass | Passed in earlier physical run and automated checks |

The final phone-origin matrix ran at 01:19–01:20 with Samsung sharing disabled and Wireless debugging off. Both phones remained connected after Windows restarted at 01:23. Neither phone logged a capture failure after the final installation.

Windows startup approval is enabled: the `StartupApproved/Run` entry begins with `02`, and the registered command targets the tested Release executable with `--background`. Testing the command and reconnection did not require signing the user out of Windows.

Shizuku availability after reboot depends on its startup mode and device support. The S21 configuration recovered automatically in this run; devices without automatic Shizuku startup must use the app's restart guidance.

## Failures reproduced and fixed

The S25 clock was ahead of the host, causing an image transfer to be rejected as exceeding the ten-minute expiration window. A real authenticated WebSocket regression now verifies host-normalized expiry, peer fan-out, and unchanged retry item ID, relay sequence, and deadline. Desktop heartbeats likewise now use the desktop session identity assigned by the server.

Intermittent image-read failures were traced to revoked shell URI access when the clipboard changed during reading. On the S21, a controlled native probe read a 226-byte PNG successfully, replaced the clipboard, and then obtained zero bytes plus a FileProvider permission denial when reopening the old URI. Earlier helper errors had matching native permission-denial logs. Capture now checks both the native clipboard signature and content version before accepting either a result or a failure. A superseded read is discarded; a failure for the current clip remains visible. This guard covers both Shizuku and focused capture. The new regression exercises native replacement, remote application during a read, helper replacement, and current-item failures. Existing tests still verify that edited content carrying stale origin extras is captured.

Earlier Samsung sharing runs independently published identical image pixels on the other phone before our relay delivery arrived, obscuring peer-path validation. The controlled 01:09 S25 Gallery test established the S21's native clipboard source as Pairly, with both recipients applying the same item. The final debugging-off matrix then confirmed all four phone-origin copies with one publication each.

Earlier Windows image activity included pairs of identical publications without a controlled native-copy count. A controlled single Windows copy produced one native sequence and one publication. A separate six-image copy sequence produced six image publications followed by the expected text publication, with no continuing loop. These controlled checks did not reproduce duplicate capture for one Windows copy.

## State preservation

Compared again after Windows restarted with `C:/Temp/pairly-clipboard-before-20261012/runtime-state`:

- Host identity JSON is byte-identical.
- The underlying host certificate is unchanged and retains its private key. PFX container bytes can differ because the existing startup path exports it again with new encryption randomness.
- All three existing Android pairing identities, pairing timestamps, and decrypted trust keys are unchanged, including the older offline S25 entry.
- No pairing or certificate was regenerated, and no push, tag, publication, or release was performed.

The checkout contains extensive changes predating this task. The saved-source comparison found only expected clipboard implementation, integration, diagnostics, documentation, and regression-test edits; no saved source file was removed by this task. The final follow-up changed only capture consistency, its regression test, and this validation record.
