package dev.smartphonelink.android.features.mirror

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import dev.smartphonelink.android.network.PhoneLinkConnection
import dev.smartphonelink.android.protocol.ProtocolJson

/**
 * Asks Android for screen capture consent, then hands the grant to [MirrorProjectionService].
 *
 * Every field the PC sent with `stream.open` is carried straight through, because the consent
 * dialog is the only thing that happens here; nothing about the session is decided in this
 * activity.
 */
class MirrorPermissionActivity : Activity() {

    private val captureRequestCode = 19041

    private val audioRequestCode = 19042

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android only lets an app capture other apps' sound with the microphone permission.
        // Ask once, right when mirroring starts, where the reason is obvious; a "no" is remembered
        // and the mirror simply runs without sound.
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean(KEY_AUDIO_ASKED, false)
        ) {
            preferences.edit().putBoolean(KEY_AUDIO_ASKED, true).apply()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioRequestCode)
            return
        }

        requestCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioRequestCode) requestCapture()
    }

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequestCode)
    }

    @Deprecated("Deprecated by platform API; kept for minSdk 26 compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != captureRequestCode) {
            finish()
            return
        }

        if (resultCode != RESULT_OK || data == null) {
            publishDenied()
            finish()
            return
        }

        val service = Intent(this, MirrorProjectionService::class.java)
            .setAction(MirrorProjectionService.ACTION_START)
            .putExtra(MirrorProjectionService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(MirrorProjectionService.EXTRA_RESULT_DATA, data)
            .putExtra(MirrorProjectionService.EXTRA_HOST, intent.getStringExtra(EXTRA_HOST))
            .putExtra(MirrorProjectionService.EXTRA_CERTIFICATE_FINGERPRINT, intent.getStringExtra(EXTRA_CERTIFICATE_FINGERPRINT))
            .putExtra(MirrorProjectionService.EXTRA_DEVICE_ID, intent.getStringExtra(EXTRA_DEVICE_ID))
            .putExtra(MirrorProjectionService.EXTRA_SESSION_ID, intent.getStringExtra(EXTRA_SESSION_ID))
            .putExtra(MirrorProjectionService.EXTRA_PC_SESSION_ID, intent.getStringExtra(EXTRA_PC_SESSION_ID))
            .putExtra(MirrorProjectionService.EXTRA_STREAM_PORT, intent.getIntExtra(EXTRA_STREAM_PORT, 0))
            .putExtra(MirrorProjectionService.EXTRA_STREAM_TICKET, intent.getStringExtra(EXTRA_STREAM_TICKET))
            .putExtra(MirrorProjectionService.EXTRA_STREAM_ID, intent.getLongExtra(EXTRA_STREAM_ID, 0L))
            .putExtra(MirrorProjectionService.EXTRA_CONTROL_TICKET, intent.getStringExtra(EXTRA_CONTROL_TICKET))
            .putExtra(MirrorProjectionService.EXTRA_CONTROL_STREAM_ID, intent.getLongExtra(EXTRA_CONTROL_STREAM_ID, 0L))
            .putExtra(MirrorProjectionService.EXTRA_AUDIO_TICKET, intent.getStringExtra(EXTRA_AUDIO_TICKET))
            .putExtra(MirrorProjectionService.EXTRA_AUDIO_STREAM_ID, intent.getLongExtra(EXTRA_AUDIO_STREAM_ID, 0L))
            .putExtra(MirrorProjectionService.EXTRA_STREAM_USE_TLS, intent.getBooleanExtra(EXTRA_STREAM_USE_TLS, true))
            .putExtra(MirrorProjectionService.EXTRA_SUPPORTED_CODECS, intent.getStringArrayExtra(EXTRA_SUPPORTED_CODECS))
            .putExtra(MirrorProjectionService.EXTRA_MAX_WIDTH, intent.getIntExtra(EXTRA_MAX_WIDTH, 0))
            .putExtra(MirrorProjectionService.EXTRA_MAX_HEIGHT, intent.getIntExtra(EXTRA_MAX_HEIGHT, 0))
            .putExtra(MirrorProjectionService.EXTRA_MAX_FPS, intent.getIntExtra(EXTRA_MAX_FPS, 60))
            .putExtra(MirrorProjectionService.EXTRA_PROFILE, intent.getStringExtra(EXTRA_PROFILE) ?: "auto")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service)
        } else {
            startService(service)
        }

        finish()
    }

    private fun publishDenied() {
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val pcSessionId = intent.getStringExtra(EXTRA_PC_SESSION_ID).orEmpty()
        if (deviceId.isBlank() || sessionId.isBlank()) {
            return
        }

        PhoneLinkConnection.send(
            ProtocolJson.mirrorState(
                deviceId = deviceId,
                sessionId = sessionId,
                targetDeviceId = pcSessionId,
                state = "denied",
                reason = "Screen capture was declined on the phone.",
                needsUserAction = true
            )
        )
    }

    companion object {
        private const val PREFERENCES = "smartphone_link_mirror"
        private const val KEY_AUDIO_ASKED = "audio_permission_asked"
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
    }
}
