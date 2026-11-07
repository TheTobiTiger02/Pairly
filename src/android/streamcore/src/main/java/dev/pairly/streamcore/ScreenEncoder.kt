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

    private val codecLock = Any()

    @Volatile
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

    private fun startCodec(requested: EncoderConfig) {
        val (created, config) = createConfigured(requested)

        val surface = created.createInputSurface()
        created.start()

        codec = created
        activeConfig = config
        source.start(surface, config.width, config.height)
        onFormatChanged(config.width, config.height, activeCodec)
    }

    /**
     * Creates and configures an encoder for [requested], shrunk to a size the encoder supports (a
     * software encoder, or a tall screen, can be smaller than the screen), and with a plain format
     * if the encoder rejects the realtime tuning.
     */
    private fun createConfigured(requested: EncoderConfig): Pair<MediaCodec, EncoderConfig> {
        val created = MediaCodec.createEncoderByType(activeCodec.mimeType)
        val config = fitToEncoder(requested, created)
        try {
            created.configure(buildFormat(config, activeCodec, tuned = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (tunedFailure: Exception) {
            Log.w(TAG, "Encoder ${created.name} refused the tuned format; using a plain one.", tunedFailure)
            runCatching { created.reset() }
            try {
                created.configure(buildFormat(config, activeCodec, tuned = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (plainFailure: Exception) {
                releaseQuietly(created)
                throw plainFailure
            }
        }
        return created to config
    }

    private fun fitToEncoder(config: EncoderConfig, encoder: MediaCodec): EncoderConfig {
        val video = runCatching { encoder.codecInfo.getCapabilitiesForType(activeCodec.mimeType).videoCapabilities }.getOrNull()
            ?: return config
        if (video.isSizeSupported(config.width, config.height)) return config
        var scale = 1.0
        repeat(40) {
            scale *= 0.9
            val width = (config.width * scale).toInt() / 16 * 16
            val height = (config.height * scale).toInt() / 16 * 16
            if (width >= 16 && height >= 16 && video.isSizeSupported(width, height)) {
                Log.i(TAG, "Encoder ${encoder.name} cannot do ${config.width}x${config.height}; using ${width}x$height.")
                return config.copy(width = width, height = height)
            }
        }
        return config
    }

    private fun restartCodec(requested: EncoderConfig) {
        val (created, config) = try {
            createConfigured(requested)
        } catch (e: Exception) {
            // Keep streaming at the old size rather than taking the whole session down.
            Log.w(TAG, "Encoder refused ${requested.width}x${requested.height}; keeping the current size.", e)
            return
        }

        val surface = created.createInputSurface()
        created.start()

        // Point the source at the new surface before the old codec goes away, so the display
        // never draws into a released surface.
        source.start(surface, config.width, config.height)

        val previous = synchronized(codecLock) {
            val old = codec
            codec = created
            activeConfig = config
            old
        }
        previous?.let(::releaseQuietly)
        onFormatChanged(config.width, config.height, activeCodec)
    }

    private fun releaseCodec() {
        val target = synchronized(codecLock) {
            val old = codec
            codec = null
            old
        } ?: return
        releaseQuietly(target)
    }

    private fun releaseQuietly(target: MediaCodec) {
        // Called only after the codec left [codec] under [codecLock], so the output thread can no
        // longer be inside one of its calls.
        runCatching { target.stop() }
        runCatching { target.release() }
    }

    private fun buildFormat(config: EncoderConfig, videoCodec: VideoCodec, tuned: Boolean): MediaFormat =
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
            if (tuned) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            }

            // Without this a static screen emits nothing at all and the PC cannot distinguish a
            // frozen stream from an idle one.
            setLong(
                MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
                MirrorQualityPolicy.REPEAT_PREVIOUS_FRAME_AFTER_US
            )

            // A plain format leaves the rest to the encoder's defaults.
            if (!tuned) return@apply

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
            // Codec calls run under the lock, so a restart or stop swaps the codec out between
            // steps, never while one of its buffers is being read; that delays a restart by at
            // most DEQUEUE_TIMEOUT_US. The socket write happens outside it: a slow link used to
            // hold the lock for seconds and stall the rotation on the main thread.
            val record = synchronized(codecLock) {
                val target = codec ?: return
                try {
                    drainStep(target, bufferInfo)
                } catch (e: IllegalStateException) {
                    // Includes CodecException. A codec in a bad state is replaced or stopped by
                    // its owner; an uncaught throw here would kill the process.
                    Log.w(TAG, "Encoder output failed; waiting for the codec to be replaced.", e)
                    null
                }
            }
            record?.let(::writeRecord)
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                break
            }
        }
    }

    private class OutputRecord(val payload: java.nio.ByteBuffer, val codecFlags: Int, val presentationTimeUs: Long)

    /** Handles one output buffer or event; returns a copy of the encoded data, if any. */
    private fun drainStep(target: MediaCodec, bufferInfo: MediaCodec.BufferInfo): OutputRecord? {
        val index = target.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
        when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> return null

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
                val record = if (buffer != null && bufferInfo.size > 0) {
                    buffer.position(bufferInfo.offset)
                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                    // Codec buffers are direct, so the writer copied them anyway; copying here
                    // lets the buffer go back to the codec before the socket write.
                    val copy = ByteArray(bufferInfo.size)
                    buffer.get(copy)
                    OutputRecord(java.nio.ByteBuffer.wrap(copy), bufferInfo.flags, bufferInfo.presentationTimeUs)
                } else {
                    null
                }
                runCatching { target.releaseOutputBuffer(index, false) }
                return record
            }
        }
        return null
    }

    private fun writeRecord(record: OutputRecord) {
        var flags = 0
        if (record.codecFlags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            flags = flags or StreamProtocol.FLAG_CODEC_CONFIG
        }
        if (record.codecFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
            flags = flags or StreamProtocol.FLAG_KEYFRAME
            keyframesEmitted++
            keyframeRequested.set(false)
        }

        try {
            val size = record.payload.remaining()
            writer.write(record.payload, flags, record.presentationTimeUs)
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
