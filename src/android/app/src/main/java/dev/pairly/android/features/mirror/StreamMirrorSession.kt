package dev.pairly.android.features.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import dev.pairly.android.network.PairlyConnection
import dev.pairly.android.protocol.ProtocolJson
import dev.pairly.android.features.control.AccessibilityInputSink
import dev.pairly.streamcore.ControlMessage
import dev.pairly.streamcore.ControlMessageCodec
import dev.pairly.streamcore.MirrorProfile
import dev.pairly.streamcore.MirrorQualityPolicy
import dev.pairly.streamcore.ScreenEncoder
import dev.pairly.streamcore.StreamProtocol
import dev.pairly.streamcore.VideoCodec
import org.json.JSONObject

/**
 * One mirroring session in app mode: MediaProjection into the shared encoder, out over the media
 * stream socket.
 *
 * Replaces the WebRTC session. The encoded elementary stream goes to the PC untouched, so quality
 * is bounded by the phone's hardware encoder rather than by what a browser's congestion controller
 * decides to allow, and there is no SDP, no ICE and no signalling round trip before the first
 * frame arrives.
 */
class StreamMirrorSession(
    private val context: Context,
    private val deviceId: String,
    private val sessionId: String,
    private val pcSessionId: String,
    private val projection: MediaProjection,
    private val host: String,
    private val port: Int,
    private val ticketBase64: String,
    private val streamId: Long,
    private val controlTicketBase64: String?,
    private val controlStreamId: Long,
    private val audioTicketBase64: String?,
    private val audioStreamId: Long,
    private val useTls: Boolean,
    private val certificateFingerprint: String?,
    private val profile: MirrorProfile,
    private val maxWidth: Int,
    private val maxHeight: Int,
    private val maxFps: Int,
    private val supportedCodecs: List<String>,
    private val onStopped: (reason: String) -> Unit
) {

    private var socket: StreamSocketClient? = null
    private var controlSocket: StreamSocketClient? = null
    private var audio: PlaybackAudioStreamer? = null
    private val inputSink by lazy { AccessibilityInputSink(context) }
    private var source: ProjectionVideoSource? = null
    private var encoder: ScreenEncoder? = null

    @Volatile
    private var stopped = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Connects on a worker thread, then starts capture on the main thread.
     *
     * Both halves are forced: Android forbids network I/O on the main thread, and
     * MediaProjection delivers its stop callback on the registering thread's Looper, which a bare
     * worker thread does not have.
     */
    fun start() {
        Thread({
            val client = try {
                StreamSocketClient.connect(
                    host = host,
                    port = port,
                    kind = StreamProtocol.KIND_VIDEO,
                    ticketBase64 = ticketBase64,
                    streamId = streamId,
                    useTls = useTls,
                    certificateFingerprint = certificateFingerprint
                )
            } catch (e: Exception) {
                Log.e(TAG, "Mirror stream socket could not connect.", e)
                mainHandler.post { stop("Could not reach the PC's stream port: ${e.message}") }
                return@Thread
            }

            Log.i(TAG, "Stream socket connected to $host:$port (tls=$useTls).")
            mainHandler.post { startCapture(client) }
        }, "mirror-connect").start()
    }

    private fun startCapture(client: StreamSocketClient) {
        Log.i(TAG, "Starting capture (stopped=$stopped).")
        if (stopped) {
            runCatching { client.close() }
            return
        }

        try {
            socket = client

            val (sourceWidth, sourceHeight) = readDisplaySize()
            val config = MirrorQualityPolicy.resolve(
                profile = profile,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                requestedFrameRate = maxFps
            )

            val videoSource = ProjectionVideoSource(
                projection = projection,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                densityDpi = context.resources.displayMetrics.densityDpi,
                onProjectionStopped = { stop("Screen capture was revoked.") }
            )
            source = videoSource

            val screenEncoder = ScreenEncoder(
                source = videoSource,
                output = client.output,
                onFormatChanged = ::publishFormat,
                onStreamClosed = { reason -> mainHandler.post { stop("The PC closed the stream: $reason") } }
            )
            encoder = screenEncoder

            val codec = VideoCodec.fromWireName(
                supportedCodecs.firstOrNull { it.equals("h265", ignoreCase = true) } ?: "h264"
            )
            screenEncoder.start(config, codec)

            Log.i(TAG, "Mirror streaming ${config.width}x${config.height}@${config.frameRate} " +
                "${config.bitrateBps / 1000} kbps ${screenEncoder.currentCodec.wireName}")
            publishState("streaming")
            startControlChannel()
            startAudioChannel()
        } catch (e: Exception) {
            Log.e(TAG, "Mirror session could not start.", e)
            stop("Mirror could not start: ${e.message}")
        }
    }

    /**
     * Opens the input channel. Input rides its own socket so a pointer stream never queues behind
     * video, and failures here are deliberately non-fatal: a mirror you can watch but not touch
     * is still a mirror.
     */
    private fun startControlChannel() {
        val ticket = controlTicketBase64 ?: return

        Thread({
            val client = try {
                StreamSocketClient.connect(
                    host = host,
                    port = port,
                    kind = StreamProtocol.KIND_CONTROL,
                    ticketBase64 = ticket,
                    streamId = controlStreamId,
                    useTls = useTls,
                    certificateFingerprint = certificateFingerprint
                )
            } catch (e: Exception) {
                Log.w(TAG, "Input channel could not connect; the mirror stays view-only.", e)
                return@Thread
            }

            if (stopped) {
                runCatching { client.close() }
                return@Thread
            }

            controlSocket = client
            Log.i(TAG, "Input channel connected.")

            try {
                while (!stopped) {
                    val message = ControlMessageCodec.read(client.input) ?: break
                    mainHandler.post { applyControl(message) }
                }
            } catch (e: Exception) {
                if (!stopped) Log.w(TAG, "Input channel closed.", e)
            } finally {
                runCatching { client.close() }
            }
        }, "mirror-input").start()
    }

    /**
     * Opens the phone-audio channel. Optional like input: without the microphone permission
     * (which Android requires for playback capture) or on Android 9, the mirror is just silent.
     */
    private fun startAudioChannel() {
        val ticket = audioTicketBase64 ?: return
        if (!PlaybackAudioStreamer.canCapture(context)) {
            Log.i(TAG, "Phone audio needs Android 10+ and the microphone permission; mirroring without sound.")
            return
        }

        Thread({
            val client = try {
                StreamSocketClient.connect(
                    host = host,
                    port = port,
                    kind = StreamProtocol.KIND_AUDIO,
                    ticketBase64 = ticket,
                    streamId = audioStreamId,
                    useTls = useTls,
                    certificateFingerprint = certificateFingerprint
                )
            } catch (e: Exception) {
                Log.w(TAG, "Audio channel could not connect; mirroring without sound.", e)
                return@Thread
            }

            mainHandler.post {
                if (stopped || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Thread({ runCatching { client.close() } }, "mirror-audio-close").start()
                    return@post
                }
                try {
                    audio = PlaybackAudioStreamer(projection, client).also { it.start() }
                    Log.i(TAG, "Phone audio streaming.")
                } catch (e: Exception) {
                    Log.w(TAG, "Phone audio capture could not start.", e)
                    Thread({ runCatching { client.close() } }, "mirror-audio-close").start()
                }
            }
        }, "mirror-audio-connect").start()
    }

    private var controlMessagesApplied = 0L

    private fun applyControl(message: ControlMessage) {
        if (stopped) return
        if (controlMessagesApplied++ % 50 == 0L) {
            Log.i(TAG, "Input message #$controlMessagesApplied: ${message::class.simpleName}")
        }
        when (message) {
            is ControlMessage.KeyframeRequest -> encoder?.requestKeyframe()
            else -> ControlMessageCodec.dispatch(message, inputSink)
        }
    }

    /**
     * Applies a new profile or size limit. Resolution changes restart the codec but keep the
     * socket and the projection, so the user is not asked for consent again.
     */
    fun applyQuality(payload: JSONObject) {
        val screenEncoder = encoder ?: return
        val (sourceWidth, sourceHeight) = readDisplaySize()

        val config = MirrorQualityPolicy.resolve(
            profile = MirrorProfile.fromWireName(payload.optString("profile", profile.wireName)),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            maxWidth = payload.optInt("maxWidth", maxWidth),
            maxHeight = payload.optInt("maxHeight", maxHeight),
            requestedFrameRate = payload.optInt("maxFps", maxFps),
            bitrateOverrideBps = payload.optInt("bitrateKbps", 0) * 1000
        )

        screenEncoder.reconfigure(config)
    }

    fun requestKeyframe() {
        encoder?.requestKeyframe()
    }

    /**
     * Re-fits the capture to the new orientation. The encoder restarts because the input surface
     * size is fixed at configure time, but the projection and the socket survive it.
     */
    fun handleOrientationChange() {
        val screenEncoder = encoder ?: return
        val current = screenEncoder.currentConfig ?: return
        val (sourceWidth, sourceHeight) = readDisplaySize()

        val config = MirrorQualityPolicy.resolve(
            profile = current.profile,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            requestedFrameRate = current.frameRate
        )

        if (config.width != current.width || config.height != current.height) {
            screenEncoder.reconfigure(config)
        }
    }

    fun stop(reason: String = "Mirror stopped.") {
        if (stopped) {
            return
        }
        Log.i(TAG, "Stopping mirror session: $reason")
        stopped = true

        runCatching { audio?.stop() }
        audio = null

        runCatching { encoder?.stop() }
        encoder = null

        runCatching { source?.stopProjection() }
        source = null

        // TLS close sends close_notify, which is network I/O and illegal on the main thread.
        val closing = listOfNotNull(socket, controlSocket)
        if (closing.isNotEmpty()) {
            Thread({ closing.forEach { runCatching { it.close() } } }, "mirror-close").start()
        }
        socket = null
        controlSocket = null

        publishState("stopped", reason)
        onStopped(reason)
    }

    private fun publishFormat(width: Int, height: Int, codec: VideoCodec) {
        if (stopped) {
            return
        }

        // The PC builds its decoder from this, so it has to arrive on the control channel before
        // the first byte does - which is why it is not carried in band.
        PairlyConnection.send(
            ProtocolJson.envelope(
                "mirror.video.format",
                deviceId,
                JSONObject()
                    .put("sessionId", sessionId)
                    .put("streamId", streamId)
                    .put("codec", codec.wireName)
                    .put("width", width)
                    .put("height", height)
                    .put("rotation", currentRotationDegrees())
                    .put("displayId", Display.DEFAULT_DISPLAY)
            )
        )

        PairlyConnection.send(
            ProtocolJson.envelope(
                "stream.ready",
                deviceId,
                JSONObject()
                    .put("sessionId", sessionId)
                    .put("streamId", streamId)
                    .put("codec", codec.wireName)
                    .put("width", width)
                    .put("height", height)
                    .put("rotation", currentRotationDegrees())
            )
        )
    }

    private fun publishState(state: String, reason: String? = null) {
        PairlyConnection.send(
            ProtocolJson.mirrorState(
                deviceId = deviceId,
                sessionId = sessionId,
                targetDeviceId = pcSessionId,
                state = state,
                reason = reason
            )
        )
    }

    /** Real display size including any area under system bars; that is what gets mirrored. */
    private fun readDisplaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = context.getSystemService(WindowManager::class.java)
            val bounds = windowManager?.maximumWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }

        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)

        return if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            metrics.widthPixels to metrics.heightPixels
        } else {
            context.resources.displayMetrics.widthPixels to context.resources.displayMetrics.heightPixels
        }
    }

    private fun currentRotationDegrees(): Int {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        return when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private companion object {
        const val TAG = "StreamMirrorSession"
    }
}
