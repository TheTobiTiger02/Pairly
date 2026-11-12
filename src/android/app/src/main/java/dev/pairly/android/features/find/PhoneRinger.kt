package dev.pairly.android.features.find

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import dev.pairly.android.R
import dev.pairly.android.network.PairlyConnectionService
import dev.pairly.android.ui.LocaleText

/**
 * "Ring my phone" from the PC: plays the alarm sound at full alarm volume, so it is heard with the
 * ringer muted, until someone taps Stop on the phone, the PC stops it, or a minute has passed.
 * The alarm volume the user had is put back afterwards.
 */
object PhoneRinger {
    private const val CHANNEL_ID = "find_phone"
    private const val NOTIFICATION_ID = 4107
    private const val MAX_RING_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var volumeBefore: Int? = null
    private var timeout: Runnable? = null

    val isRinging: Boolean get() = player != null

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (player != null) return
        val audio = app.getSystemService(AudioManager::class.java)
        runCatching {
            volumeBefore = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return restoreVolume(app)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(app, sound)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        if (player == null) return restoreVolume(app)

        showNotification(app)
        timeout = Runnable { stop(app) }.also { handler.postDelayed(it, MAX_RING_MS) }
    }

    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        timeout?.let(handler::removeCallbacks)
        timeout = null
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        restoreVolume(app)
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun restoreVolume(app: Context) {
        val before = volumeBefore ?: return
        volumeBefore = null
        runCatching { app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, before, 0) }
    }

    private fun showNotification(app: Context) {
        val text = LocaleText.of(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, text.getString(R.string.find_phone_channel_name), NotificationManager.IMPORTANCE_HIGH)
                    // The alarm is the sound; the notification itself stays quiet.
                    .apply { setSound(null, null) }
            )
        }
        val stop = PendingIntent.getService(
            app, 0,
            Intent(app, PairlyConnectionService::class.java).setAction(PairlyConnectionService.ACTION_STOP_RING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text.getString(R.string.find_phone_notification_title))
                .setContentText(text.getString(R.string.find_phone_notification_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(stop)
                .addAction(0, text.getString(R.string.find_phone_stop), stop)
                .build()
        )
    }
}
