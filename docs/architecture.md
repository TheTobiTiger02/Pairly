# Architecture

Smartphone Link retains the existing WPF, Compose, embedded-host, and WebRTC architecture. The release-readiness pass hardens boundaries and separates UI state from transport state without introducing a framework rewrite.

## Identity, trust, and pairing

The Windows host persists a stable `hostId`, certificate, and certificate fingerprint. Its trust registry uses serialized writes, monotonically increasing revisions, checksums, redundant snapshots, DPAPI-protected secrets, and revocation tombstones. A forgotten phone cannot return from an older backup.

Android stores a `TrustedHost` containing host ID, display name, pinned fingerprint, last endpoint, stable device ID, protocol version, and encrypted trust secret. New secrets require Android Keystore; legacy v1 preferences are read only for in-place migration.

Pairing uses a short-lived setup code. Normal reconnect uses `session.challenge`, an HMAC proof in `session.authenticate`, and `session.ready`; the trust secret is never transmitted. Feature synchronization begins only in Ready. When an endpoint changes, Android rediscovers the saved `hostId`, verifies the certificate pin and challenge, then updates the endpoint without pairing again.

## Transport and lifecycle

- Loopback HTTP (`127.0.0.1:7816`) serves only the Windows app’s management, media, and viewer surfaces.
- LAN devices use pinned WSS (`7818`) on `/ws/v1/session`.
- UDP discovery (`7817`) returns `hostId`, PC name, protocol version, secure port, and certificate fingerprint.
- Every connection has a generation. Callbacks from a replaced socket cannot change the current connection state.
- Each authenticated device has a bounded outbound queue and one writer loop, isolating slow recipients.
- Heartbeats run every 20 seconds and a connection is stale after 60 seconds.
- Android reconnects with full-jitter backoff after network changes, boot, or package replacement. Windows registers login startup after the first trusted pairing.

## Clipboard

The PC host is the authoritative relay. It assigns a relay sequence while preserving the original item ID, fans out independently to every Ready phone and the PC clipboard, and records `applied`, `failed`, or `unsupported` acknowledgements per recipient.

`ClipboardRelay` owns publication and fan-out for every capture path. Retrying an immutable copy receives its original sequence and expiry without another fan-out. Bounded receipts outlive the 50-item history window. Reconnecting recipients receive new publications; clipboard history is never replayed.

`App` owns `WindowsClipboardSync`, a message-only HWND listening to `WM_CLIPBOARDUPDATE` on the STA dispatcher. Realtime clipboard events apply directly; UI refresh is independent. Android's `PhoneLinkConnectionService` owns capture and the newest pending copy across network reconnects. Explicit disconnect, unpairing, or disabling automatic sync stops capture and clears pending content.

A versioned, non-daemon Shizuku UserService reads clipboard descriptions once per second off the app's main thread. It reads payloads only after a change, establishes a startup baseline, skips sensitive clips, prefers images, and opens image URIs as shell. Its narrow AIDL API exposes metadata and bounded read-only `ParcelFileDescriptor` streams. Binding generations reject late callbacks; permission loss, binder death, and bind timeout trigger actionable states and recovery. Wireless debugging turns off by default after a healthy call. Foreground and explicit send paths remain usable without Shizuku.

Text is limited to 1 MiB UTF-8. PNG images are limited to 8 MiB and a 4096px long edge. Images use 256 KiB chunks with SHA-256 verification, a ten-minute lifetime, and a 64 MiB aggregate transfer reservation. Remote-origin markers prevent text/image echoes. Session, relay-sequence, and local-copy guards prevent delayed preparation or delivery from overwriting newer content. Pending retries keep the original identity and deadline. Android retains the currently applied image file until the clipboard changes, including beyond transfer expiry.

## Selected-device features

Photos, notifications, messages, files, and mirroring remain scoped to the selected phone. Clipboard alone is global.

- Media pages carry snapshot IDs, stable cursors, album/bucket metadata, orientation, and date fallbacks. The PC retains a capped thumbnail LRU; originals are streamed rather than Base64-expanded into the UI.
- Notification snapshots reconcile removals missed while offline. Payloads include group, summary, ongoing, channel, conversation, text lines, and action metadata; PC mute preferences remain local.
- WPF collections reconcile incrementally so polling or realtime events do not recreate selections, reply drafts, expansion, or scroll state.

## Mirroring

One global mirror lease is active at a time. The Windows mirror window owns fit/fullscreen, mute, navigation, retry/permission overlays, quality state, placement, and advanced diagnostics. The WebView2 receiver uses typed `mirror.configure`, `mirror.telemetry`, and `mirror.state` messages while retaining v1 signal compatibility.

Auto mode preserves aspect ratio and rotation, prefers hardware H.264 with VP8 fallback, targets up to a 1080px short edge, 60 fps, and about 8 Mbps, and marks the source as a screencast. Sustained loss, RTT, bitrate, FPS, freeze, jitter-buffer, and encoder-limitation signals drive gradual adaptation. Signaling reconnect and ICE restart reuse an existing projection when possible; a genuinely new MediaProjection still requires Android confirmation. Audio is off by default and failure falls back to video-only.
