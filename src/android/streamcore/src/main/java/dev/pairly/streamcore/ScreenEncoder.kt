package dev.pairly.streamcore

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class VideoCodec(val wireName: String, val mimeType: String) {
    H264("h264", MediaFormat.MIMETYPE_VIDEO_AVC),
    H265("h265", MediaFormat.MIMETYPE_VIDEO_HEVC);

    companion object {
        fun fromWireName(value: String?): VideoCodec =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: H264
    }
}

data class EncoderStatistics(
    val framesEncoded: Long,
    val keyframesEmitted: Long,
    val bytesWritten: Long
)

/**
 * Encodes the screen to an H.264 or H.265 elementary stream and writes it to a stream socket.
 *
 * Shared by both capture modes: the caller supplies a [VideoSource] and this class owns the codec,
 * the input surface and the output thread. Output is Annex-B with in-band parameter sets, which is
 * what Media Foundation on the PC consumes directly.
 */
class ScreenEncoder(
    private val source: VideoSource,
    private val output: OutputStream,
    private val onFormatChanged: (width: Int, height: Int, codec: VideoCodec) -> Unit = { _, _, _ -> },
    /**
     * Called once, from the output thread, when the stream socket fails. The owner must end the
     * session: without this the encoder stops but the projection and its cast indicator stay up,
     * casting to nobody.
     */
    private val onStreamClosed: (reason: String) -> Unit = {}
) {

    private val writer = StreamRecordWriter(output)
    private val running = AtomicBoolean(false)

    // Separate from [running]: a socket failure clears running from the output thread, and stop()
    // must still release the codec and the source afterwards.
    private val released = AtomicBoolean(false)
    private val keyframeRequested = AtomicBoolean(false)

    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null
    private var activeConfig: EncoderConfig? = null
    private var activeCodec: VideoCodec = VideoCodec.H264

    @Volatile
    private var framesEncoded = 0L

    @Volatile
    private var keyframesEmitted = 0L

    @Volatile
    private var bytesWritten = 0L

    val statistics: EncoderStatistics
        get() = EncoderStatistics(framesEncoded, keyframesEmitted, bytesWritten)

    val currentConfig: EncoderConfig? get() = activeConfig

    val currentCodec: VideoCodec get() = activeCodec

    /**
     * Starts encoding. [preferredCodec] is honoured only when a hardware encoder for it exists;
     * a software HEVC encoder would be slower than hardware H.264 and is never the right trade.
     */
    fun start(config: EncoderConfig, preferredCodec: VideoCodec) {
        check(running.compareAndSet(false, true)) { "Encoder is already running." }

        activeCodec = selectCodec(preferredCodec)
        startCodec(config)

        outputThread = Thread(::drainOutput, "screen-encoder-output").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Applies new settings. A bitrate or frame rate change is applied in place; a resolution change
     * needs a codec restart because the input surface size is fixed at configure time.
     */
    fun reconfigure(config: EncoderConfig) {
        val previous = activeConfig ?: return
        if (!running.get()) {
            return
        }

        if (previous.width != config.width || previous.height != config.height) {
            restartCodec(config)
            return
        }

        if (previous.bitrateBps != config.bitrateBps) {
            setBitrate(config.bitrateBps)
        }

        activeConfig = config
    }

    /** Applies a new bitrate without restarting; MediaCodec supports this at runtime. */
    fun setBitrate(bitrateBps: Int) {
        val target = codec ?: return
        runCatching {
            target.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps)
            })
        }.onFailure { Log.w(TAG, "Encoder refused a bitrate change.", it) }

        activeConfig = activeConfig?.copy(bitrateBps = bitrateBps)
    }

    /**
     * Asks for a sync frame on the next encoded picture. Used when a viewer attaches, after packet
     * loss, and after a window resize - which is why the keyframe interval can stay long.
     */
    fun requestKeyframe() {
        val target = codec ?: return
        keyframeRequested.set(true)
        runCatching {
            target.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }.onFailure { Log.w(TAG, "Encoder refused a sync frame request.", it) }
    }

    fun stop() {
        if (released.getAndSet(true)) {
            return
        }

        running.set(false)

        outputThread?.interrupt()
        outputThread = null
        releaseCodec()
        source.release()
    }

    private fun startCodec(config: EncoderConfig) {
        val format = buildFormat(config, activeCodec)
        val created = MediaCodec.createEncoderByType(activeCodec.mimeType)
        created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val surface = created.createInputSurface()
        created.start()

        codec = created
        activeConfig = config
        source.start(surface, config.width, config.height)
        onFormatChanged(config.width, config.height, activeCodec)
    }

    private fun restartCodec(config: EncoderConfig) {
        releaseCodec()

        val format = buildFormat(config, activeCodec)
        val created = MediaCodec.createEncoderByType(activeCodec.mimeType)
        created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val surface = created.createInputSurface()
        created.start()

        codec = created
        activeConfig = config
        source.resize(config.width, config.height)
        source.start(surface, config.width, config.height)
        onFormatChanged(config.width, config.height, activeCodec)
    }

    private fun releaseCodec() {
        val target = codec ?: return
        codec = null
        runCatching { target.stop() }
        runCatching { target.release() }
    }

    private fun buildFormat(config: EncoderConfig, videoCodec: VideoCodec): MediaFormat =
        MediaFormat.createVideoFormat(videoCodec.mimeType, config.width, config.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSeconds)

            // Constant bitrate keeps the link's bandwidth predictable, which matters far more here
            // than the file-size efficiency VBR would buy.
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )

            // Without this a static screen emits nothing at all and the PC cannot distinguish a
            // frozen stream from an idle one.
            setLong(
                MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                MirrorQualityPolicy.REPEAT_PREVIOUS_FRAME_AFTER_US
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // B-frames reorder output, which costs latency for a compression win nobody here
                // asked for.
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }

    private fun drainOutput() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (running.get() && !Thread.currentThread().isInterrupted) {
            val target = codec ?: break

            val index = try {
                target.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                // The codec was restarted underneath us by reconfigure(); pick up the new one.
                continue
            }

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = target.outputFormat
                    onFormatChanged(
                        format.getInteger(MediaFormat.KEY_WIDTH),
                        format.getInteger(MediaFormat.KEY_HEIGHT),
                        activeCodec
                    )
                }

                index >= 0 -> {
                    val buffer = target.getOutputBuffer(index)
                    if (buffer != null && bufferInfo.size > 0) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        writeRecord(buffer, bufferInfo)
                    }
                    runCatching { target.releaseOutputBuffer(index, false) }
                }
            }

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                break
            }
        }
    }

    private fun writeRecord(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        var flags = 0
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            flags = flags or StreamProtocol.FLAG_CODEC_CONFIG
        }
        if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
            flags = flags or StreamProtocol.FLAG_KEYFRAME
            keyframesEmitted++
            keyframeRequested.set(false)
        }

        try {
            val size = info.size
            writer.write(buffer, flags, info.presentationTimeUs)
            bytesWritten += size
            if (flags and StreamProtocol.FLAG_CODEC_CONFIG == 0) {
                framesEncoded++
            }
        } catch (e: Exception) {
            // The viewer went away. Stopping here is what ends the session rather than spinning
            // against a dead socket.
            Log.i(TAG, "Stream socket closed; stopping the encoder.", e)
            if (running.getAndSet(false)) {
                onStreamClosed(e.message ?: "The stream socket closed.")
            }
        }
    }

    private fun selectCodec(preferred: VideoCodec): VideoCodec {
        if (preferred == VideoCodec.H264) {
            return VideoCodec.H264
        }

        return if (hasHardwareEncoder(preferred.mimeType)) {
            preferred
        } else {
            Log.i(TAG, "No hardware ${preferred.wireName} encoder; falling back to H.264.")
            VideoCodec.H264
        }
    }

    private fun hasHardwareEncoder(mimeType: String): Boolean {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return codecs.codecInfos.any { info ->
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                return@any false
            }

            // isHardwareAccelerated only exists from Q; before that the name prefix is the
            // convention every vendor follows for software codecs.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !info.name.startsWith("OMX.google.", ignoreCase = true) &&
                    !info.name.startsWith("c2.android.", ignoreCase = true)
            }
        }
    }

    private companion object {
        const val TAG = "ScreenEncoder"
        const val DEQUEUE_TIMEOUT_US = 100_000L
    }
}
