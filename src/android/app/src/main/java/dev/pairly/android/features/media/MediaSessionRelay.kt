package dev.pairly.android.features.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import dev.pairly.android.features.notifications.NotificationRelayService
import dev.pairly.android.network.ConnectionSettings
import dev.pairly.android.network.PairlyConnection
import dev.pairly.android.protocol.ProtocolJson
import java.io.ByteArrayOutputStream
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Relays the phone's media sessions (music, podcasts, video) to the PC and carries its
 * play / pause / skip / seek commands back.
 *
 * `getActiveSessions` requires the caller to be an enabled notification listener, which
 * Pairly already is for notification sync - so this needs no new permission. Everything
 * runs on the main looper: MediaController callbacks arrive there, and the work per update is small.
 */
object MediaSessionRelay {
    private const val TAG = "MediaSessionRelay"
    private const val SEND_DEBOUNCE_MS = 250L
    private const val ARTWORK_SIZE_PX = 192
    private const val MAX_SESSIONS = 4

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private val controllers = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    private val artworkCache = mutableMapOf<String, Pair<Any?, String?>>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { active ->
        bind(active.orEmpty())
    }
    private val sendRunnable = Runnable { sendNow() }

    /** Called when the notification listener connects; only then are active sessions readable. */
    fun start(context: Context) = handler.post {
        if (manager != null) return@post
        val app = context.applicationContext
        val sessionManager = app.getSystemService(MediaSessionManager::class.java) ?: return@post
        val listener = ComponentName(app, NotificationRelayService::class.java)
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
            appContext = app
            manager = sessionManager
            bind(sessionManager.getActiveSessions(listener))
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access is not granted; media sessions are unavailable.", e)
        }
    }

    fun stop() = handler.post {
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        manager = null
        unbindAll()
        artworkCache.clear()
    }

    /** Sends the current state; used when a session to the PC becomes ready. */
    fun sendSnapshot() = handler.post { scheduleSend(immediate = true) }

    /** Applies a `media.session.command` payload from the PC. */
    fun handleCommand(payload: JSONObject): String {
        val packageName = payload.optString("packageName")
        val action = payload.optString("action")
        val position = payload.optLong("positionMs", -1L)
        handler.post {
            val controller = controllers.firstOrNull { it.first.packageName == packageName }?.first
            if (controller == null) {
                Log.d(TAG, "No media session for $packageName.")
                return@post
            }
            val controls = controller.transportControls
            when (action) {
                "play" -> controls.play()
                "pause" -> controls.pause()
                "toggle" -> if (controller.playbackState?.isPlaying() == true) controls.pause() else controls.play()
                "next" -> controls.skipToNext()
                "previous" -> controls.skipToPrevious()
                "stop" -> controls.stop()
                "seek" -> if (position >= 0) controls.seekTo(position)
                else -> Log.d(TAG, "Unknown media action $action.")
            }
        }
        return "Media command $action sent to $packageName."
    }

    private fun bind(active: List<MediaController>) {
        unbindAll()
        active.take(MAX_SESSIONS).forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleSend()
                override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleSend()
                override fun onSessionDestroyed() = scheduleSend()
            }
            controller.registerCallback(callback, handler)
            controllers += controller to callback
        }
        artworkCache.keys.retainAll(controllers.map { it.first.packageName }.toSet())
        scheduleSend()
    }

    private fun unbindAll() {
        controllers.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        controllers.clear()
    }

    private fun scheduleSend(immediate: Boolean = false) {
        handler.removeCallbacks(sendRunnable)
        handler.postDelayed(sendRunnable, if (immediate) 0L else SEND_DEBOUNCE_MS)
    }

    private fun sendNow() {
        val context = appContext ?: return
        if (!PairlyConnection.isReady) return
        val deviceId = ConnectionSettings(context).deviceId

        // Playing sessions first, then the rest in the system's own order (most recent first).
        val sessions = controllers.map { it.first }
            .filter { it.metadata != null || it.playbackState != null }
            .sortedByDescending { it.playbackState?.isPlaying() == true }
            .map { describe(context, it) }

        val payload = JSONObject()
            .put("sessions", JSONArray(sessions))
            .put("updatedAt", Instant.now().toString())
        PairlyConnection.send(ProtocolJson.envelope("media.session.state", deviceId, payload))
    }

    private fun describe(context: Context, controller: MediaController): JSONObject {
        val metadata = controller.metadata
        val state = controller.playbackState
        val actions = state?.actions ?: 0L
        val updatedAt = state?.lastPositionUpdateTime?.takeIf { it > 0 }?.let {
            // lastPositionUpdateTime is on the elapsedRealtime clock; convert to wall time.
            System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - it)
        } ?: System.currentTimeMillis()

        return JSONObject()
            .put("packageName", controller.packageName)
            .put("appName", appName(context, controller.packageName))
            .put("state", stateName(state?.state))
            .put("title", metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            .put("artist", metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
            .put("album", metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM))
            .put("durationMs", metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L)
            .put("positionMs", state?.position?.coerceAtLeast(0L) ?: 0L)
            .put("positionUpdatedAt", Instant.ofEpochMilli(updatedAt).toString())
            .put("playbackSpeed", (state?.playbackSpeed ?: 0f).toDouble())
            .put("canPlayPause", actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L)
            .put("canNext", actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
            .put("canPrevious", actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
            .put("canSeek", actions and PlaybackState.ACTION_SEEK_TO != 0L)
            .put("artworkJpegBase64", artwork(controller.packageName, metadata) ?: JSONObject.NULL)
    }

    /** Small JPEG of the cover, re-encoded only when the bitmap changes. */
    private fun artwork(packageName: String, metadata: MediaMetadata?): String? {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        artworkCache[packageName]?.let { (source, encoded) -> if (source === bitmap) return encoded }

        val encoded = bitmap?.let {
            runCatching {
                val scaled = Bitmap.createScaledBitmap(it, ARTWORK_SIZE_PX, ARTWORK_SIZE_PX, true)
                val bytes = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    out.toByteArray()
                }
                if (scaled !== it) scaled.recycle()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.getOrNull()
        }
        artworkCache[packageName] = bitmap to encoded
        return encoded
    }

    private fun appName(context: Context, packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "buffering"
        PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> "stopped"
        else -> "none"
    }

    private fun PlaybackState.isPlaying(): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
}
