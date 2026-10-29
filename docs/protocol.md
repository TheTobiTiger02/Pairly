# Protocol

All WebSocket messages use the shared JSON envelope. Version 2 is current; optional v2 fields keep v1 payload defaults valid during migration.

```json
{
  "v": 2,
  "id": "uuid",
  "type": "clipboard.item",
  "deviceId": "android-device-id",
  "seq": 42,
  "timestamp": "2026-07-15T12:00:00Z",
  "payload": {}
}
```

## Discovery and session

- Discovery v2: `hostId`, `pcName`, `protocolVersion`, `wssPort`, `certificateFingerprint`; legacy `port` remains optional.
- Setup: `pair.hello`, `pair.confirm`.
- Reconnect: `session.challenge`, `session.authenticate`, `session.ready`.
- Lifecycle: `heartbeat.ping`, `heartbeat.pong`, `device.capabilities`, `device.state`, `device.sync.request`, `command.result`, `error`.

- Endpoints: `host.endpoints` (PC to phone) lists every address the PC answers on, as `{host, port, kind, preference}` with `kind` `lan` or `tailscale`. The PC sends it right after `session.ready` and again when its network addresses change. The discovery reply carries the same list as an optional `endpoints` field.

When the saved address stops answering, the phone runs UDP discovery and a staggered race (250 ms apart) across the remembered addresses at the same time. A race attempt only wins after a TLS handshake verifies the pinned certificate, so an address is only a hint where to look. The race is what finds the PC over Tailscale off the home network.

The host does not accept feature messages before authentication. A session challenge is bound to the device ID, host ID, nonce, and challenge ID.

## Feature messages

- Clipboard: `clipboard.item`, `clipboard.ack`, `content.transfer.start`, `content.transfer.chunk`, `content.transfer.complete`. Existing payloads are preserved. The source receives its accepted `clipboard.item` as publication confirmation; retries retain item/transfer IDs and expiry and confirm the original sequence without fan-out. `POST /v1/clipboard` accepts optional `itemId` and `expiresAt` for desktop retries.
- Device permission reporting may include `clipboardBackground` (`granted`, `disabled`, or `needs_user_action`); older peers can ignore this optional key.
- Media: `media.index.request`, `media.index.page`, `media.content.request`, `media.content.response`.
- Notifications: `notification.snapshot.begin`, `notification.upsert`, `notification.remove`, `notification.snapshot.end`, `notification.action`.
- SMS: `sms.thread.page`, `sms.message.page`, `sms.send`.
- Mirroring: `mirror.start`, `mirror.configure`, `mirror.telemetry`, `mirror.state`, `mirror.signal`, `mirror.input`, `mirror.stop`.
- Media sessions: `media.session.state` (phone to PC: active sessions with title, artist, position, capabilities and a small JPEG cover), `media.session.command` (PC to phone: `play`, `pause`, `toggle`, `next`, `previous`, `stop`, `seek` with `positionMs`). Needs the notification-access grant the phone already uses for notification sync.
- Phone audio while mirroring: `stream.open` carries `audioTicket` and `audioStreamId`; the phone dials stream kind 2 and sends 10 ms records of 48 kHz 16-bit stereo PCM captured from playback (Android 10+, needs the microphone permission, asked once when mirroring starts).
- File push: `file.push.start` (PC to phone) offers a file with a single-use ticket; the phone dials the stream port as kind 5 and the PC writes exactly `byteCount` raw bytes. The phone saves to Downloads/Pairly and answers `file.push.result` (`saved`, `failed`, `rejected`).
- Apps: `apps.list.request` / `apps.list.response` (launcher apps with 64 px PNG icons), `apps.launch` / `apps.launch.result`. Android blocks background activity starts, so launching needs the accessibility service or the "Display over other apps" grant; without either the phone reports `failed` instead of pretending.
- Calls/files: `call.ringing`, `call.active`, `call.idle`, `call.action` (`answer`, `reject`, `hangup`, `silence`; see docs/calls.md), `storage.list.request`, `storage.list.response`, `storage.file.request`, `storage.file.response`, `storage.delete.request`.

## Local Windows API

All HTTP management and content endpoints are loopback-only. Principal routes include:

- `POST /v1/pairing-ticket`
- `GET /v1/devices` and `DELETE /v1/devices/{deviceId}`
- `GET /v1/clipboard`, `GET /v1/clipboard/deliveries`, `POST /v1/clipboard`
- `GET /v1/clipboard/{itemId}/content`, `POST /v1/clipboard/{itemId}/ack`
- `GET /v1/media/{deviceId}` and media thumbnail/content routes
- notification, SMS, call, and storage action routes scoped by device ID
- mirror prepare/start/stop/state routes and `/mirror/viewer.html`

The only LAN HTTP upgrade allowed is HTTPS `/ws/v1/session`; phones connect to it through certificate-pinned WSS.
