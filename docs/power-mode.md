# Power mode (Shizuku)

Power mode is Pairly's optional shell-user helper on the phone. [Shizuku](https://shizuku.rikka.app/)
starts a server with the rights of an ADB shell; Pairly binds a small UserService in it
(`features/shizuku/ShizukuShellService`, process `dev.pairly.android:shell`). The PC never needs ADB
for any of this.

| Without Power mode | With Power mode |
|---|---|
| Clipboard syncs only while Pairly is open | Clipboard syncs in the background (shell reads `IClipboard`) |
| Android asks before every mirroring session | `appops set <pkg> PROJECT_MEDIA allow`, so the capture prompt returns at once; kept across restarts |
| Mirror input through the accessibility service (gestures) | Real touch, scroll and key events (`ShellInputSink` in streamcore, the same injector the ADB server uses) |
| Each permission through its own Settings screen | One tap: `pm grant`, `appops set`, `cmd notification allow_listener`, the accessibility setting, `cmd deviceidle whitelist` |

## Pieces

- `ShizukuHelper` (app process): Shizuku's state as `status` (installed, running, allowed, has run
  before, starts on boot, Wireless debugging on), and the helper binding. Features hold the helper by
  name while they need it: `clipboard`, `screen-capture`, `input` (while mirroring), `setup` (grants).
- `ShizukuShellService` (shell process): the AIDL `IShizukuShell`. Every method acts on Pairly's own
  package only, and there is no general "run a command" call. Grants are limited to the steps listed
  in `ShizukuGrants`.
- `ShizukuInputSink`: mirror input goes to the helper while it is connected, else to the
  accessibility sink. Input calls are `oneway`, so they arrive in order and never stall the stream.

## Wireless debugging

Shizuku needs Wireless debugging only to start; its server keeps running without it. Whenever the
helper connects, it "settles" the phone: the capture prompt as the user chose, and
`settings put global adb_wifi_enabled 0` (setting `wirelessDebuggingOffWithShizuku`, on by default,
switch under Settings → Power mode). The app also connects by itself when it sees Shizuku running
with Wireless debugging on, so it is switched off even if no feature holds the helper.

## Status everywhere

- Phone: the Power mode card on Home and in Settings, and the Power mode step in onboarding.
- PC: the device state carries `permissions.powerMode` (`ready`, `notRunning`, `notAllowed`,
  `notInstalled`); the phone card shows a "Power mode" chip, or "paused" after a restart.

## Testing on an emulator

Install the Shizuku APK from its GitHub releases and start the server over the emulator's adb:

```bash
adb shell "$(adb shell pm path moe.shizuku.privileged.api | sed 's/package://;s/base.apk//')lib/x86_64/libshizuku.so"
```

Then allow Pairly in the guide (Settings → Power mode → Set up). The emulator turns Wireless
debugging off by itself within milliseconds, so the automatic switch-off can only be verified on a
real phone.
