# Calls

## What works now (app mode, no Bluetooth)

- Incoming call card on the PC with **Answer**, **Decline** and **Silence**. After the call is
  answered (on the PC or the phone), or when a call is dialled from the phone, the card switches to
  **Hang up** and closes when the phone reports the call over (`call.ringing`, `call.active`,
  `call.idle`, `call.action`).
- Answer and hang up use `TelecomManager.acceptRingingCall` / `endCall`, which need the phone's
  "Phone" permission (`ANSWER_PHONE_CALLS`, `READ_PHONE_STATE`).
- Caller: on Android 12+ telephony no longer reports the number, so the caller is read from the
  dialer's call notification (needs notification access). Numbers are resolved to contact names
  with `READ_CONTACTS`.
- Silence mutes the ring stream until the call ends. The ringer mode is never changed. (The old
  "mute" set the ringer mode to silent and left the phone silent afterwards.)
- Call **audio stays on the phone** (or its Bluetooth headset). The card says so.

## Call audio on the PC: Bluetooth HFP spike

Windows only terminates hands-free (HFP) call audio for an app registered through
`Windows.ApplicationModel.Calls.PhoneLineTransportDevice`. Probe run on 2026-09-23 (read-only, no
registration or connection):

| Phone | HFP transport found | Registered to a caller app | `RequestAccessAsync` (unpackaged) |
|---|---|---|---|
| S21, S24, S25, Tab S9, OnePlus 9 | yes | no | `DeniedBySystem` |

`PhoneCallManager.RequestStoreAsync` works unpackaged. Transport access needs package identity.

Second run, same day, as a Developer Mode package declaring `phoneLineTransportManagement`
(tools/HfpProbe):

- `RequestAccessAsync` returns `Allowed` for all five phones.
- `RegisterApp()` returns without error, but `IsRegistered()` stays `False` right after it.
- `ConnectAsync()` returns `False`; `AudioRoutingStatus` is `CannotRouteToLocalDevice`.
- Phone side (S21): the service-level connection comes up, and the PC closes it about 80 ms
  later.

This matches a known Windows 11 22H2+ change: registration silently fails for apps other than
Microsoft Phone Link (BestOwl/MyPhone issue #26, open since March 2023, no workaround). Windows
does not expose Bluetooth SCO call audio to user-mode apps any other way, and Android blocks
`VOICE_CALL` capture for normal apps.

**Decision:** call control on the PC (answer, decline, silence, hang up), call audio on the phone
or its headset. Re-run tools/HfpProbe after major Windows updates.
