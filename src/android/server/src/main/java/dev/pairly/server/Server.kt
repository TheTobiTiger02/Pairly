package dev.pairly.server

import android.os.Handler
import android.os.Looper
import android.util.Base64
import dev.pairly.streamcore.ControlMessage
import dev.pairly.streamcore.ControlMessageCodec
import dev.pairly.streamcore.MirrorProfile
import dev.pairly.streamcore.MirrorQualityPolicy
import dev.pairly.streamcore.ScreenEncoder
import dev.pairly.streamcore.StreamProtocol
import dev.pairly.streamcore.VideoCodec
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.exitProcess

/**
 * ADB-mode mirror server, started by the PC with
 * `SLK_ARGS="key=value ..." CLASSPATH=/data/local/tmp/pairly-server.jar app_process / dev.pairly.server.Server`.
 *
 * It dials the PC's stream port through `adb reverse` (loopback, so no TLS - the tunnel is inside
 * the wireless-debugging trust boundary) with tickets the PC issued, exactly like the app does.
 * stdout carries one line per event for the PC (`SLK_FORMAT`, `SLK_DISPLAY`); stderr is a log.
 * The process lives exactly as long as the video socket: when the PC closes it, the server exits.
 *
 * Arguments: port, videoTicket, videoStreamId, controlTicket, controlStreamId, maxSize, maxFps,
 * profile, codec, and for an app window: newDisplay=WIDTHxHEIGHT/DPI and launch=package.name.
 */
object Server {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        Platform.prepareProcess()
        // Options come in the SLK_ARGS environment variable rather than argv. Samsung's encoder
        // setup copies the process command line into a fixed-size buffer and aborts ("stack
        // corruption detected") when it is long, and argv would also show the tickets in `ps`.
        val options = (args.toList() + System.getenv("SLK_ARGS").orEmpty().split(' '))
            .mapNotNull { it.split('=', limit = 2).takeIf { parts -> parts.size == 2 } }
            .associate { (key, value) -> key to value }

        try {
            run(options)
        } catch (e: Throwable) {
            Log.w("Server failed to start", e)
            exitProcess(1)
        }
        Looper.loop()
    }

    private fun run(options: Map<String, String>) {
        val port = options["port"]?.toInt() ?: 7819
        val source = options["newDisplay"]?.let(::parseNewDisplay) ?: DisplayVideoSource.Mirror(0)

        val video = connect(port, StreamProtocol.KIND_VIDEO, options.getValue("videoTicket"), options.getValue("videoStreamId").toLong())
        val encoder = ScreenEncoder(
            source = source,
            output = BufferedOutputStream(video.getOutputStream(), 256 * 1024),
            onFormatChanged = { width, height, codec ->
                emit("SLK_FORMAT ${codec.wireName} $width $height 0 ${source.displayId}")
            },
            onStreamClosed = { reason ->
                Log.i("PC closed the stream: $reason")
                exitProcess(0)
            }
        )

        val maxSize = options["maxSize"]?.toIntOrNull() ?: 0
        val profile = MirrorProfile.fromWireName(options["profile"])
        val maxFps = options["maxFps"]?.toIntOrNull() ?: 60
        fun resolve() = MirrorQualityPolicy.resolve(
            profile = profile,
            sourceWidth = source.sourceWidth,
            sourceHeight = source.sourceHeight,
            maxWidth = maxSize,
            maxHeight = maxSize,
            requestedFrameRate = maxFps
        )

        val config = resolve()
        encoder.start(config, VideoCodec.fromWireName(options["codec"]))
        Log.i("Streaming display ${source.displayId} ${config.width}x${config.height}@${config.frameRate}")
        emit("SLK_DISPLAY ${source.displayId}")

        options["launch"]?.let { launch(it, source.displayId) }

        val sink = InjectorInputSink(
            displayId = { source.displayId },
            displaySize = { source.sourceWidth to source.sourceHeight }
        )
        options["controlTicket"]?.let { ticket ->
            startControl(port, ticket, options.getValue("controlStreamId").toLong(), sink, encoder, source)
        }

        if (source is DisplayVideoSource.Mirror) {
            watchRotation(source, encoder, ::resolve)
        }
    }

    private fun parseNewDisplay(value: String): DisplayVideoSource.NewDisplay {
        // WIDTHxHEIGHT/DPI, e.g. 1080x1920/420
        val (size, dpi) = value.split('/').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 420) }
        val (width, height) = size.split('x').map(String::toInt)
        return DisplayVideoSource.NewDisplay(width, height, dpi)
    }

    private fun startControl(
        port: Int,
        ticket: String,
        streamId: Long,
        sink: InjectorInputSink,
        encoder: ScreenEncoder,
        source: DisplayVideoSource
    ) {
        Thread({
            try {
                val control = connect(port, StreamProtocol.KIND_CONTROL, ticket, streamId)
                val input = control.getInputStream()
                while (true) {
                    val message = ControlMessageCodec.read(input) ?: break
                    mainHandler.post {
                        runCatching {
                            when (message) {
                                is ControlMessage.KeyframeRequest -> encoder.requestKeyframe()
                                is ControlMessage.AppStart -> launch(message.packageName, source.displayId)
                                else -> ControlMessageCodec.dispatch(message, sink)
                            }
                        }.onFailure { Log.w("Input message failed", it) }
                    }
                }
            } catch (e: Exception) {
                Log.w("Input channel closed", e)
            }
        }, "server-input").start()
    }

    /** The phone rotating changes the mirrored display's size; restart the encoder to match. */
    private fun watchRotation(
        source: DisplayVideoSource.Mirror,
        encoder: ScreenEncoder,
        resolve: () -> dev.pairly.streamcore.EncoderConfig
    ) {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (runCatching { source.refresh() }.getOrDefault(false)) {
                    Log.i("Display is now ${source.sourceWidth}x${source.sourceHeight}")
                    encoder.reconfigure(resolve())
                }
                mainHandler.postDelayed(this, ROTATION_POLL_MS)
            }
        }, ROTATION_POLL_MS)
    }

    /** Opens [packageName]'s launcher activity on [displayId]. */
    private fun launch(packageName: String, displayId: Int) {
        val component = Shell.run("cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", packageName)
            .lines().lastOrNull { it.contains('/') }?.trim()
        if (component == null) {
            Log.w("$packageName has no launcher activity")
            return
        }
        val result = Shell.run("am", "start", "--display", displayId.toString(), "-n", component, "-f", "0x10000000")
        Log.i("Launched $component on display $displayId: ${result.trim().lines().lastOrNull()}")
    }

    private fun connect(port: Int, kind: Int, ticket: String, streamId: Long): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
        socket.getOutputStream().apply {
            write(StreamProtocol.encodeHandshake(kind, Base64.decode(ticket, Base64.DEFAULT), streamId))
            flush()
        }
        return socket
    }

    private fun emit(line: String) {
        println(line)
        System.out.flush()
    }

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val ROTATION_POLL_MS = 500L
}
