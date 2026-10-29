package dev.pairly.android.features.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import dev.pairly.streamcore.StreamRecordWriter
import java.nio.ByteBuffer

/**
 * Streams what the phone is playing to the PC while the screen is mirrored.
 *
 * Uses Android's playback capture on the mirror's own MediaProjection, so there is no second
 * consent dialog. Audio goes out as raw 48 kHz 16-bit stereo PCM in 10 ms records: about 1.5 Mbit/s,
 * small next to the video, and it keeps the PC side a plain buffer queue with no decoder. Apps that
 * opt out of capture (some streaming and banking apps) are silent by Android's design.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackAudioStreamer(
    private val projection: MediaProjection,
    private val socket: StreamSocketClient
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // Checked by canCapture before construction.
    fun start() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 4))
            .build()

        running = true
        thread = Thread({
            val writer = StreamRecordWriter(socket.output)
            val chunk = ByteArray(CHUNK_BYTES)
            try {
                record.startRecording()
                while (running) {
                    var filled = 0
                    while (filled < CHUNK_BYTES && running) {
                        val read = record.read(chunk, filled, CHUNK_BYTES - filled)
                        if (read < 0) throw IllegalStateException("AudioRecord.read returned $read")
                        filled += read
                    }
                    if (!running) break
                    writer.write(ByteBuffer.wrap(chunk, 0, filled), 0, SystemClock.elapsedRealtimeNanos() / 1_000L)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Phone audio stream ended.", e)
            } finally {
                runCatching { record.stop() }
                record.release()
                runCatching { socket.close() }
            }
        }, "mirror-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        // Closing the socket unblocks a write stuck on a slow network.
        Thread({ runCatching { socket.close() } }, "mirror-audio-close").start()
    }

    companion object {
        private const val TAG = "PlaybackAudio"
        const val SAMPLE_RATE = 48_000
        private const val CHUNK_BYTES = SAMPLE_RATE / 100 * 2 * 2 // 10 ms, stereo, 16-bit

        fun canCapture(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
