# Mirror pipeline

Replaces the WebRTC-in-WebView2 mirror with a scrcpy-shaped raw elementary stream decoded by
Media Foundation on Direct3D 11.

## Spike results

Measured on an NVIDIA GeForce RTX 3070, Windows 11, with a 1080x1920 H.264 Annex-B stream captured
from a real device (`adb exec-out screenrecord --output-format=h264`). Harness:
`tools/MirrorDecodeSpike`.

```
MirrorDecodeSpike.exe <stream.h264> 1080 1920 --loops 5 --exit
```

| Measurement | Result |
| --- | --- |
| Decoder chosen | Microsoft H264 Video Decoder MFT |
| Output samples | Provided by the transform, backed by `IMFDXGIBuffer` |
| Zero copy | Yes - 0 system-memory frames over 1045 frames |
| Decode | 0.29 ms average, 33.9 ms peak (first frame, DXVA initialisation) |
| Present | 1.27 ms average |
| Throughput | 628 fps at 1080p, fed as fast as the decoder accepts |
| Colour | BT.709 limited to full range, verified against a captured frame |

628 fps is roughly ten times the 60 fps the mirror needs, so at the target rate decode and present
together consume under 3% of a frame budget. The pipeline is not the constraint; the network and
the encoder on the phone are.

### What the spike settled

**Hardware decode does not need a separate hardware MFT.** `MFTEnumEx` returns only the Microsoft
H264 decoder on this machine, and that decoder performs DXVA decode on the GPU once
`MFT_MESSAGE_SET_D3D_MANAGER` hands it the D3D11 device manager. The evidence is the output: it
sets `MFT_OUTPUT_STREAM_PROVIDES_SAMPLES` and every sample carries an `IMFDXGIBuffer`, which only
exists for GPU-resident frames.

Note that `MFT_ENUM_HARDWARE_URL_Attribute` is present on this decoder, so the "hardware" label
reported by enumeration is not trustworthy on its own. `ProvidesSamples` plus a successful
`IMFDXGIBuffer` query-interface is the reliable signal, and that is what the code checks.

**The decoder here is synchronous, not asynchronous.** The plan assumed the async MFT event
protocol would be required. On this machine it is not. The async path is still implemented and
selected from the MFT's own `MF_TRANSFORM_ASYNC` attribute, because Intel and AMD systems, and
HEVC on any vendor, may expose an asynchronous hardware transform.

**Output bind flags must be requested explicitly.** DXVA output textures are created with the
decoder bind flag only. `MF_SA_D3D11_BINDFLAGS` is set to `SHADER_RESOURCE | DECODER` on the output
stream attributes so the presenter can sample the decoded texture in place. `NV12Presenter` falls
back to a GPU-side copy when an allocator refuses, and reports that through
`PresenterStatistics.ZeroCopy` rather than hiding it.

**HwndHost with a flip-model swap chain, not `D3DImage`.** The swap chain presents straight to a
child window at 1.27 ms per frame with no intermediate surface. `D3DImage` cannot beat that: it
requires a second D3D9Ex device, a shared-surface copy, and a WPF composition pass, each of which
only adds latency. This was decided on the architecture rather than a head-to-head measurement,
because the direct path met the target with an order of magnitude to spare. The cost is the
airspace limitation - WPF content cannot be drawn over the video - which suits a mirror pane that
is a full rectangle with chrome around it.

**Access unit boundaries come from `first_mb_in_slice`, not from delimiters.** Android's MediaCodec
emits long runs of bare slice NALs with no access unit delimiter, SEI or parameter sets between
frames. An initial implementation that split on delimiters found 3 access units in a 209-frame
stream. `AnnexBReader` now splits on the first slice of each coded picture, and non-VCL NALs that
follow a picture are attributed to the access unit they precede. Both rules are pinned by tests.

## Components

| File | Responsibility |
| --- | --- |
| `src/windows/SmartphoneLink.Media/AnnexBReader.cs` | Splits an elementary stream into access units; extracts SPS/PPS for the input media type. |
| `src/windows/SmartphoneLink.Media/D3D11Context.cs` | The one D3D11 device shared by decoder and presenter, with multithread protection and an `IMFDXGIDeviceManager`. |
| `src/windows/SmartphoneLink.Media/VideoDecoder.cs` | MFT selection, D3D11 binding, type negotiation, and both the synchronous and asynchronous calling contracts. |
| `src/windows/SmartphoneLink.Media/NV12Presenter.cs` | Two shader resource views over one NV12 texture, BT.709 conversion in the pixel shader, flip-model present. |
| `tools/MirrorDecodeSpike/` | The harness above. Not shipped. |

## Shared encoder module

`src/android/streamcore` is an Android library shared by the installed app and, later, by the
ADB-side server process. It holds everything both capture modes have in common and nothing either
one owns exclusively.

| File | Responsibility |
| --- | --- |
| `StreamProtocol.kt` | Handshake and record framing for the media socket; `StreamRecordWriter`. |
| `EncoderConfig.kt` | `MirrorProfile`, and the policy that turns a profile plus size limits into concrete encoder settings. |
| `ScreenEncoder.kt` | `MediaCodec` ownership, the input surface, the output drain thread, runtime bitrate and sync-frame control. |
| `VideoSource.kt` | The `VideoSource`, `AudioSource` and `InputSink` interfaces both modes implement. |
| `ControlMessageCodec.kt` | The binary control protocol and its dispatch onto an `InputSink`. |

### Module isolation is enforced, not documented

`:streamcore` may only use public platform APIs that the shell UID can also load. `MediaProjection`
lives in `:app`; `SurfaceControl` reflection will live in `:server`. Breaking that rule compiles
fine and fails at runtime on the ADB path only, so the `verifyStreamCoreIsolation` Gradle task
fails the build on any `android.app.*`, `android.content.*`, `androidx.*` or `dalvik.system.*`
import. It runs as part of `:streamcore:check`.

### Encoder settings and why

| Setting | Value | Reason |
| --- | --- | --- |
| Bitrate mode | CBR | Predictable bandwidth matters more here than the file-size win VBR would buy. |
| `KEY_I_FRAME_INTERVAL` | 10 s | Sync frames are requested on demand instead, so a static screen does not burn bitrate on keyframes nobody needs. |
| `KEY_REPEAT_PREVIOUS_FRAME_AFTER` | 100 ms | Without it a static screen emits nothing and the desktop cannot tell a frozen link from an idle one. |
| `KEY_MAX_B_FRAMES` | 0 | B-frames reorder output, costing latency for compression nobody asked for. |
| `KEY_PRIORITY` | 0 (realtime) | |
| `KEY_LATENCY` | 1 | |
| Bitrate changes | `PARAMETER_KEY_VIDEO_BITRATE` | Applied live; no encoder restart for a quality change. |
| Resolution changes | codec restart | The input surface size is fixed at configure time; there is no way around it. |
| HEVC | Only with a hardware encoder | A software HEVC encoder is slower than hardware H.264, so it is never the right trade. |

Bitrate is derived from bits-per-pixel-per-frame rather than a flat constant, so 720p30 and 1440p60
both land somewhere sensible instead of one being starved and the other wasted:

| Profile | bpp | Ceiling | Degradation |
| --- | --- | --- | --- |
| Sharpness | 0.14 | 24 Mbps | maintain resolution |
| Auto | 0.10 | 16 Mbps | maintain framerate |
| Balanced | 0.08 | 10 Mbps | maintain framerate |
| Low latency | 0.06 | 6 Mbps | maintain framerate |

Floor is 1.5 Mbps for every profile. The Tailscale path will need a lower ceiling; that number
comes from measurement, not from this table.

### Wire format parity

`StreamProtocol.kt` / `StreamFraming.cs` and `ControlMessageCodec.kt` / `ControlMessages.cs` must
stay byte-identical, as must the two quality policies. Rather than trusting review, both sides
assert the same golden hex vectors and the same resolved settings, so editing one side alone breaks
that side's build instead of producing a stream the other end silently misreads.

Pointer positions are absolute device pixels **paired with the screen size they were computed
against**. That pairing is what makes a coordinate self-describing: after a rotation, a position
computed for the old orientation arrives with the old screen size and is discarded rather than
tapping somewhere arbitrary. The previous normalised 0..1 scheme could express neither that nor a
second pointer.

## End-to-end path

```
MediaProjection -> VirtualDisplay -> MediaCodec surface        (phone, :app + :streamcore)
   -> Annex-B records over TLS TCP 7819                        (StreamSocketClient)
   -> StreamListener admits the ticket                         (PC)
   -> MirrorStreamHub pairs socket with format
   -> VideoDecoder (DXVA, D3D11)
   -> NV12Presenter -> flip swap chain -> MirrorSurfaceHost     (MirrorWindow)
```

Control still travels on the pinned WebSocket: `stream.open` carries the address, the ticket and
the stream id; `mirror.quality` carries the encoder envelope; `mirror.video.format` and
`stream.ready` come back. There is no SDP, no ICE and no signalling round trip before the first
frame.

### Admission

The stream socket carries video, not protocol envelopes, so it cannot redo the pairing handshake.
Admission is delegated to the control channel, which is already authenticated and certificate
pinned: the host mints a 32-byte ticket there, the phone presents it as the socket's first bytes,
and the ticket dies on first use. It is bound to the device, the stream kind and the stream id, so
a ticket minted for video cannot open a control socket, and a late socket from a previous session
cannot be mistaken for the current one. Lifetime is 30 seconds - long enough to dial on a slow
network, short enough that a leaked ticket is worthless.

TLS applies to everything arriving over the network. A loopback connection is accepted in the clear
because that shape only occurs over an `adb reverse` tunnel, which is already inside the USB or
wireless-debugging trust boundary and has no certificate to pin against.

### The format race

The format arrives on the control channel and the bytes arrive on the socket, and either can be
first. `MirrorStreamHub` holds whichever lands first and publishes the pair, so the renderer can
build its decoder whichever way the race goes rather than the two halves having to arrive in order.

### Removed with this change

`WebRtcMirrorSession.kt` (648 lines), `MirrorViewerPage.cs` (869 lines), the `/mirror/viewer.html`
endpoint, the `mirror.signal` message type and its Android helper, the `io.github.webrtc-sdk`
dependency, the `Microsoft.Web.WebView2` package reference, and the duplicated quality table in the
WPF settings handler.

## Device test: Galaxy S21 (SM-G991B, Exynos, Android 15)

End to end on real hardware over Wi-Fi to the RTX 3070 host:

| Measurement | Result |
| --- | --- |
| Encoder | Exynos hardware H.264, 1080x2400 native, 60 fps, 15.5 Mbps CBR target |
| Frame rate under motion | 58.6 fps decoded on the PC |
| Decode / present | 0.3 ms / 0.2 ms average, zero copy, 0 dropped |
| Session length tested | 5 min 41 s, past the 300 s start lease |
| Stop | Projection released and the phone's cast indicator cleared |
| PC restart | Both paired phones reconnected on their own in 4-8 s |

Defects that only the device exposed, all fixed:

1. The stream socket connected on the service's main thread (`NetworkOnMainThreadException`).
2. The Microsoft H.264 decoder produced no frames from the sequence header alone; MediaCodec sends
   SPS/PPS out of band, so the config record is now also fed in band.
3. The 30 s admission ticket expired while a person tapped through the consent dialog; it is now
   5 minutes, matching the start lease.
4. Nothing renewed the 90 s mirror lease after the WebRTC viewer was deleted, so every mirror would
   have been cut at 90 s. The viewer window now renews it every 30 s.
5. A dead socket stopped the encoder but not the session, leaving the phone casting to nobody.
6. Starting a new session acquired the new projection before stopping the old one, and an app holds
   one projection at a time, so the new one died. The replaced session's stop callback also tore
   down the service hosting its replacement.
7. On a fresh install the consent request went to a notification that Android silently dropped
   (`POST_NOTIFICATIONS` not yet granted), so a mirror could never start. The request now opens the
   dialog directly whenever the app is visible, and is parked for the next app open otherwise.
8. An accepted stream arriving with no viewer window open was dropped. The main window now owns the
   one subscription and opens a viewer for any ticket-admitted stream.

## Still open

- HEVC has not been exercised. The decoder takes the codec subtype as a parameter, but no HEVC
  stream has been run through it, and some systems need the free HEVC extension installed.
- Aspect handling is not implemented in the presenter; it stretches to the swap chain. The real
  mirror window preserves aspect via its existing `FitToVideo`.
- Mid-stream resolution change calls `RenegotiateOutputType` on `MF_E_TRANSFORM_STREAM_CHANGE`,
  but rotation has not been exercised end to end yet.
- **Touch and scroll input are temporarily gone.** They lived in the WebRTC viewer page's
  JavaScript, which is deleted. Back, Home and Recents still work over the control channel. Full
  pointer, keyboard and clipboard input arrive with the control stream socket and
  `MirrorInputRouter`, which is the next piece of work.
- **Screen audio is temporarily gone.** The old path pushed raw PCM over a WebRTC data channel. The
  audio stream kind is reserved in the framing but not implemented, and the checkbox was removed
  rather than left in place doing nothing.
- Mid-stream rotation restarts the encoder and renegotiates the decoder output type, but has not
  been exercised end to end.
- On a static screen the Exynos encoder repeats frames at roughly 1-3 fps rather than the 10 fps
  `KEY_REPEAT_PREVIOUS_FRAME_AFTER` asks for. Enough to prove liveness; not yet a freeze detector.
- The Back / Home / Recents buttons need the accessibility service enabled on the phone, which has
  not been tested.
