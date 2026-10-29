# ADB mode (wireless debugging)

When a phone is reachable over wireless debugging, the phone screen and app windows run through a
small server started with shell rights instead of the installed app. Compared with app mode:

| | ADB mode | App mode |
|---|---|---|
| Screen capture | No prompt, no cast icon, works with the screen off | MediaProjection prompt each session |
| Touch | Real injected events, multi-touch, exact paths | Accessibility strokes |
| Keyboard | Real key events; non-ASCII text via clipboard + paste | Accessibility text editing or the Pairly keyboard |
| App windows | Each app on its own virtual display, one PC window per app | Not available |
| Phone sound | Not yet (app mode only) | Playback capture |

The mirror page's "Use wireless debugging when the phone has it on" is on by default. If the
phone is not connected over ADB, or anything in the ADB path fails, the app falls back to app mode
and says so. App windows ("Open in window" on the Apps page) need ADB mode.

## How it works

1. `adb devices -l` finds the phone by model (as reported at pairing). The mDNS serial is preferred
   over ip:port, because Samsung changes the port whenever wireless debugging is toggled.
2. The server (`src/android/server`, an APK used only as a dex container) is pushed to
   `/data/local/tmp/pairly-server-<sha256 prefix>.jar`, once per build.
3. `adb reverse tcp:7819 tcp:7819` points the phone's loopback at the PC's stream listener.
4. The PC issues single-use video and control tickets and starts
   `SLK_ARGS='...' CLASSPATH=<jar> app_process / dev.pairly.server.Server`.
5. The server dials back with the tickets, streams H.264 records, and prints
   `SLK_FORMAT codec width height rotation displayId` on stdout, which the PC turns into the same
   format announcement app mode sends over the control socket. stderr lines go to Diagnostics.
6. The server exits when the PC closes the video socket, so closing the window ends it.

Streams are paired with their format by stream id, so the phone screen and several app windows
can run at once.

## Samsung workarounds (found on the S21, One UI / Android 15)

- **Encoder abort "stack corruption detected"** in `CCodec::customConfigVideoEncoder`, two causes:
  - The process has no application info. `Platform.prepareProcess` installs a shell
    `AppBindData` and `Application` (the fix scrcpy uses).
  - A long command line overflows a fixed buffer in the same function. Options are therefore
    passed in the `SLK_ARGS` environment variable, never in argv. This also keeps the tickets out
    of `ps`.
- **NPE in `CompatSandbox.applyDisplaySandboxingIfNeeded`**: Samsung asks the current ActivityThread
  for its configuration on every display lookup. `prepareProcess` installs a
  `ConfigurationController` with a default configuration before anything touches the system
  context.

## App windows

The server creates a trusted virtual display (1080x1920, 420 dpi) and runs
`am start --display <id>`. An app that is already running on the phone moves to the new display.
The display is created without `DESTROY_CONTENT_ON_REMOVAL`, so closing the PC window moves the app
back to the phone screen instead of closing it.

## Verified on the S21 (2026-09-23)

- Phone screen 864x1920 H.264, zero dropped frames, no prompt.
- Back key through the mirror closes the foreground app.
- Taps land exactly: three taps on Calculator's "1" gave "111".
- Calculator in its own window on display 26 next to the phone-screen window. After closing, it is
  back on the phone screen.
- Server processes exit when their windows close.

Not yet verified: input inside an app window, rotation in ADB mode, S25.
