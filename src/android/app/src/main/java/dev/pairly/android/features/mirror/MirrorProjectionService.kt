package dev.pairly.android.features.mirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.pairly.android.R
import dev.pairly.android.ui.LocaleText
import dev.pairly.android.features.sync.FeatureSyncController
import dev.pairly.android.network.PairlyConnection
import dev.pairly.android.protocol.ProtocolJson
import dev.pairly.streamcore.MirrorProfile
import org.json.JSONObject

class MirrorProjectionService : Service() {

    private var mirrorSession: StreamMirrorSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mirrorSession?.handleOrientationChange()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession("The PC ended the mirror.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_QUALITY -> {
                runCatching {
                    mirrorSession?.applyQuality(JSONObject(intent.getStringExtra(EXTRA_QUALITY_JSON).orEmpty()))
                }
                return START_NOT_STICKY
            }

            ACTION_KEYFRAME -> {
                mirrorSession?.requestKeyframe()
                return START_NOT_STICKY
            }

            ACTION_START -> startSession(intent)
        }

        // START_NOT_STICKY throughout: a restarted service has no MediaProjection token, so a
        // sticky restart could only ever produce a service that cannot capture anything. The PC
        // re-requests a session instead, which is a path that actually works.
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        startMirrorForeground()

        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_OK)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val pcSessionId = intent.getStringExtra(EXTRA_PC_SESSION_ID).orEmpty()
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_STREAM_PORT, 0)
        val ticket = intent.getStringExtra(EXTRA_STREAM_TICKET).orEmpty()
        val streamId = intent.getLongExtra(EXTRA_STREAM_ID, 0L)

        if (resultData == null || deviceId.isBlank() || sessionId.isBlank() || host.isBlank() || port <= 0 || ticket.isBlank()) {
            publishFailure(deviceId, sessionId, pcSessionId, "Mirror could not start because session data was incomplete.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Order matters: an app holds one MediaProjection at a time, so stopping the previous
        // session after acquiring the new projection stops the new one. Measured on a Galaxy S21.
        stopSession("Replaced by a new mirror session.")

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { projectionManager?.getMediaProjection(resultCode, resultData) }.getOrNull()
        if (projection == null) {
            publishFailure(deviceId, sessionId, pcSessionId, "Android did not grant screen capture.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        lateinit var session: StreamMirrorSession
        session = StreamMirrorSession(
            context = this,
            deviceId = deviceId,
            sessionId = sessionId,
            pcSessionId = pcSessionId,
            projection = projection,
            host = host,
            port = port,
            ticketBase64 = ticket,
            streamId = streamId,
            controlTicketBase64 = intent.getStringExtra(EXTRA_CONTROL_TICKET)?.takeIf { it.isNotBlank() },
            controlStreamId = intent.getLongExtra(EXTRA_CONTROL_STREAM_ID, 0L),
            audioTicketBase64 = intent.getStringExtra(EXTRA_AUDIO_TICKET)?.takeIf { it.isNotBlank() },
            audioStreamId = intent.getLongExtra(EXTRA_AUDIO_STREAM_ID, 0L),
            useTls = intent.getBooleanExtra(EXTRA_STREAM_USE_TLS, true),
            certificateFingerprint = intent.getStringExtra(EXTRA_CERTIFICATE_FINGERPRINT),
            profile = MirrorProfile.fromWireName(intent.getStringExtra(EXTRA_PROFILE)),
            maxWidth = intent.getIntExtra(EXTRA_MAX_WIDTH, 0),
            maxHeight = intent.getIntExtra(EXTRA_MAX_HEIGHT, 0),
            maxFps = intent.getIntExtra(EXTRA_MAX_FPS, 60),
            supportedCodecs = intent.getStringArrayExtra(EXTRA_SUPPORTED_CODECS)?.toList() ?: listOf("h264"),
            onStopped = {
                // A replaced session also reports stopping; only the current one may end the
                // service, or it tears down the session that replaced it.
                if (mirrorSession === session) {
                    mirrorSession = null
                    FeatureSyncController.sendDeviceState(this, mirroringActive = false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        )
        mirrorSession = session
        session.start()

        FeatureSyncController.sendDeviceState(this, mirroringActive = true)
    }

    private fun stopSession(reason: String) {
        mirrorSession?.stop(reason)
        mirrorSession = null
        FeatureSyncController.sendDeviceState(this, mirroringActive = false)
    }

    override fun onDestroy() {
        // An unexpected destroy leaves the PC waiting, so it is told the session needs the user
        // rather than being left to time out.
        mirrorSession?.let {
            it.stop("The mirror service was stopped by the system.")
            mirrorSession = null
            FeatureSyncController.sendDeviceState(this, mirroringActive = false)
        }
        super.onDestroy()
    }

    private fun startMirrorForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(42, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(42, notification())
        }
    }

    private fun publishFailure(deviceId: String, sessionId: String, pcSessionId: String, message: String) {
        if (deviceId.isBlank() || sessionId.isBlank()) {
            return
        }

        PairlyConnection.send(
            ProtocolJson.mirrorState(
                deviceId = deviceId,
                sessionId = sessionId,
                targetDeviceId = pcSessionId,
                state = "failed",
                reason = message,
                needsUserAction = true
            )
        )
    }

    private fun notification() =
        NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(LocaleText.of(this).getString(R.string.mirror_active_notification_title))
            .setContentText(LocaleText.of(this).getString(R.string.mirror_active_notification_text))
            .setOngoing(true)
            .build()

    private fun ensureChannel(): String {
        val channelId = "mirror"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                LocaleText.of(this).getString(R.string.mirror_active_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return channelId
    }

    companion object {
        const val ACTION_START = "dev.pairly.android.START_MIRROR"
        const val ACTION_STOP = "dev.pairly.android.STOP_MIRROR"
        const val ACTION_QUALITY = "dev.pairly.android.MIRROR_QUALITY"
        const val ACTION_KEYFRAME = "dev.pairly.android.MIRROR_KEYFRAME"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HOST = "host"
        const val EXTRA_CERTIFICATE_FINGERPRINT = "certificateFingerprint"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_PC_SESSION_ID = "pcSessionId"
        const val EXTRA_STREAM_PORT = "streamPort"
        const val EXTRA_STREAM_TICKET = "streamTicket"
        const val EXTRA_STREAM_ID = "streamId"
        const val EXTRA_CONTROL_TICKET = "controlTicket"
        const val EXTRA_AUDIO_TICKET = "audioTicket"
        const val EXTRA_AUDIO_STREAM_ID = "audioStreamId"
        const val EXTRA_CONTROL_STREAM_ID = "controlStreamId"
        const val EXTRA_STREAM_USE_TLS = "streamUseTls"
        const val EXTRA_SUPPORTED_CODECS = "supportedCodecs"
        const val EXTRA_MAX_WIDTH = "maxWidth"
        const val EXTRA_MAX_HEIGHT = "maxHeight"
        const val EXTRA_MAX_FPS = "maxFps"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_QUALITY_JSON = "qualityJson"
    }
}
