package dev.smartphonelink.android.features.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import dev.smartphonelink.android.features.media.MediaSessionRelay
import dev.smartphonelink.android.features.sync.FeatureSyncController
import dev.smartphonelink.android.network.ConnectionSettings
import dev.smartphonelink.android.network.PhoneLinkConnection
import dev.smartphonelink.android.protocol.ProtocolJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object NotificationMetadataPolicy {
    fun actionId(index: Int, allowsReply: Boolean): String =
        if (allowsReply) "reply-$index" else "action-$index"

    fun textLines(values: Array<out CharSequence>?): List<String> =
        values.orEmpty().map(CharSequence::toString).map(String::trim).filter(String::isNotEmpty)

    fun looksDestructive(title: String): Boolean {
        val normalized = title.trim().lowercase(Locale.ROOT)
        return listOf("delete", "remove", "block", "stop", "discard").any(normalized::contains)
    }
}

class NotificationRelayService : NotificationListenerService() {
    private val worker = ThreadPoolExecutor(
        1,
        1,
        15L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "SmartphoneLink-Notifications").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    ).apply { allowCoreThreadTimeOut(true) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        submit { relayNotification(sbn, snapshotId = null) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        submit {
            LastNotificationRelay.latestRemovedKey = sbn.key
            LastNotificationRelay.forget(sbn.key)
            currentDeviceId().takeIf(String::isNotBlank)?.let { deviceId ->
                if (PhoneLinkConnection.isReady) {
                    PhoneLinkConnection.send(ProtocolJson.notificationRemove(deviceId, sbn.key))
                }
            }
        }
    }

    override fun onListenerConnected() {
        LastNotificationRelay.bind(this)
        MediaSessionRelay.start(this)
        requestActiveSnapshot(currentDeviceId())
        FeatureSyncController.sendDeviceState(this)
    }

    override fun onListenerDisconnected() {
        LastNotificationRelay.unbind(this)
        MediaSessionRelay.stop()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        LastNotificationRelay.unbind(this)
        worker.shutdownNow()
        super.onDestroy()
    }

    fun requestActiveSnapshot(requestedDeviceId: String): Boolean {
        val active = runCatching { activeNotifications?.copyOf().orEmpty() }.getOrElse { return false }
        return submit {
            val snapshotId = UUID.randomUUID().toString().replace("-", "")
            val deviceId = requestedDeviceId.ifBlank(::currentDeviceId)
            val canPublish = deviceId.isNotBlank() && PhoneLinkConnection.isReady
            if (canPublish) {
                PhoneLinkConnection.send(ProtocolJson.notificationSnapshotBegin(deviceId, snapshotId))
            }

            val activeKeys = HashSet<String>(active.size)
            active.sortedBy(StatusBarNotification::getPostTime).forEach { notification ->
                activeKeys += notification.key
                relayNotification(notification, snapshotId, deviceId, canPublish)
            }
            LastNotificationRelay.notificationIds()
                .filterNot(activeKeys::contains)
                .forEach(LastNotificationRelay::forget)

            if (canPublish && PhoneLinkConnection.isReady) {
                PhoneLinkConnection.send(ProtocolJson.notificationSnapshotEnd(deviceId, snapshotId))
            }
        }
    }

    private fun relayNotification(
        sbn: StatusBarNotification,
        snapshotId: String?,
        requestedDeviceId: String = currentDeviceId(),
        publish: Boolean = PhoneLinkConnection.isReady
    ) {
        val payload = buildPayload(sbn, snapshotId)
        LastNotificationRelay.latestPayload = payload.toString()
        LastNotificationRelay.remember(sbn, payload)
        if (publish && requestedDeviceId.isNotBlank() && PhoneLinkConnection.isReady) {
            PhoneLinkConnection.send(ProtocolJson.notificationUpsert(requestedDeviceId, payload))
        }
    }

    @Suppress("DEPRECATION")
    private fun buildPayload(sbn: StatusBarNotification, snapshotId: String?): JSONObject {
        val notification = sbn.notification
        val extras = notification.extras ?: Bundle.EMPTY
        val appName = runCatching {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(sbn.packageName)

        val appIconBase64 = runCatching {
            encodeDrawable(packageManager.getApplicationIcon(sbn.packageName), 48, Bitmap.CompressFormat.PNG, 100)
        }.getOrNull()
        val largeIconBase64 = runCatching {
            notification.getLargeIcon()?.loadDrawable(this)?.let {
                encodeDrawable(it, 72, Bitmap.CompressFormat.PNG, 100)
            }
        }.getOrNull()
        val pictureBase64 = runCatching {
            val picture = extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
                ?: extras.get(Notification.EXTRA_PICTURE) as? Bitmap
            picture?.let(::encodePicture)
        }.getOrNull()

        val textLines = NotificationMetadataPolicy.textLines(
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        )
        val actionMetadata = JSONArray()
        notification.actions.orEmpty().forEachIndexed { index, action ->
            val allowsReply = action.remoteInputs?.isNotEmpty() == true
            val title = action.title?.toString().orEmpty()
            val semanticDelete = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                action.semanticAction == Notification.Action.SEMANTIC_ACTION_DELETE
            actionMetadata.put(
                JSONObject()
                    .put("actionId", NotificationMetadataPolicy.actionId(index, allowsReply))
                    .put("title", title)
                    .put("allowsReply", allowsReply)
                    .put("isDestructive", semanticDelete || NotificationMetadataPolicy.looksDestructive(title))
            )
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            .orEmpty()
            .ifBlank { extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty() }

        return JSONObject()
            .put("notificationId", sbn.key)
            .put("packageName", sbn.packageName)
            .put("appName", appName)
            .put("title", title)
            .put("text", text)
            .put("textLines", JSONArray(textLines))
            .put("canReply", notification.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true)
            .put("postedAt", Instant.ofEpochMilli(sbn.postTime).toString())
            .put("appIconBase64", appIconBase64 ?: JSONObject.NULL)
            .put("largeIconBase64", largeIconBase64 ?: JSONObject.NULL)
            .put("pictureBase64", pictureBase64 ?: JSONObject.NULL)
            .put("clearable", sbn.isClearable)
            .put("category", notification.category ?: JSONObject.NULL)
            .put("groupKey", sbn.groupKey ?: JSONObject.NULL)
            .put("isGroupSummary", notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            .put("isOngoing", sbn.isOngoing)
            .put(
                "channelId",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.channelId ?: JSONObject.NULL
                else JSONObject.NULL
            )
            .put(
                "conversationTitle",
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: JSONObject.NULL
            )
            .put("actions", actionMetadata)
            .apply {
                if (!snapshotId.isNullOrBlank()) put("snapshotId", snapshotId)
            }
    }

    private fun encodeDrawable(
        drawable: Drawable,
        width: Int,
        format: Bitmap.CompressFormat,
        quality: Int
    ): String {
        val bitmap = Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, width)
            drawable.draw(canvas)
            encodeBitmap(bitmap, format, quality)
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodePicture(picture: Bitmap): String {
        val maxDimension = 512
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(picture.width, picture.height).coerceAtLeast(1))
        val width = (picture.width * scale).toInt().coerceAtLeast(1)
        val height = (picture.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(picture, width, height, true)
        return try {
            encodeBitmap(scaled, Bitmap.CompressFormat.JPEG, 80)
        } finally {
            if (scaled !== picture) scaled.recycle()
        }
    }

    private fun encodeBitmap(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): String =
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(format, quality, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }

    private fun submit(block: () -> Unit): Boolean {
        if (worker.isShutdown) return false
        return runCatching { worker.execute(block) }.isSuccess
    }

    private fun currentDeviceId(): String =
        PhoneLinkConnection.deviceId.ifBlank { ConnectionSettings(this).deviceId }
}

object LastNotificationRelay {
    @Volatile
    var latestPayload: String? = null

    @Volatile
    var latestRemovedKey: String? = null

    private val actionsByNotificationId = ConcurrentHashMap<String, Map<String, Notification.Action>>()
    private val payloadsByNotificationId = ConcurrentHashMap<String, String>()
    private val clearableByNotificationId = ConcurrentHashMap<String, Boolean>()
    @Volatile
    private var listener: NotificationRelayService? = null

    fun bind(service: NotificationRelayService) {
        listener = service
    }

    fun unbind(service: NotificationRelayService) {
        if (listener === service) listener = null
    }

    fun requestActiveSnapshot(deviceId: String): Boolean =
        listener?.requestActiveSnapshot(deviceId) == true

    fun remember(sbn: StatusBarNotification, payload: JSONObject) {
        val actions = LinkedHashMap<String, Notification.Action>()
        sbn.notification.actions.orEmpty().forEachIndexed { index, action ->
            actions[NotificationMetadataPolicy.actionId(index, action.remoteInputs?.isNotEmpty() == true)] = action
        }
        actionsByNotificationId[sbn.key] = actions
        payloadsByNotificationId[sbn.key] = payload.toString()
        clearableByNotificationId[sbn.key] = sbn.isClearable
    }

    fun forget(notificationId: String) {
        actionsByNotificationId.remove(notificationId)
        payloadsByNotificationId.remove(notificationId)
        clearableByNotificationId.remove(notificationId)
    }

    fun notificationIds(): Set<String> = payloadsByNotificationId.keys.toSet()

    /**
     * The caller shown by the dialer's ongoing call notification. On Android 12+ the telephony
     * callback no longer carries the number, but the dialer's own notification names the caller.
     */
    fun activeCallTitle(): String? = runCatching {
        listener?.activeNotifications.orEmpty()
            .firstOrNull { it.notification.category == Notification.CATEGORY_CALL }
            ?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }.getOrNull()

    fun activePayloads(): List<JSONObject> = payloadsByNotificationId.values
        .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        .sortedBy { it.optString("postedAt") }

    fun reply(context: Context, notificationId: String, replyText: String): Boolean {
        val action = actionsByNotificationId[notificationId]
            ?.values
            ?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
            ?: return false
        return sendAction(context, action, replyText)
    }

    fun performAction(context: Context, notificationId: String, actionId: String, replyText: String?): Boolean {
        val action = actionsByNotificationId[notificationId]?.get(actionId) ?: return false
        return sendAction(context, action, replyText)
    }

    private fun sendAction(context: Context, action: Notification.Action, replyText: String?): Boolean {
        val remoteInputs = action.remoteInputs.orEmpty()
        val intent = Intent()
        if (remoteInputs.isNotEmpty()) {
            val text = replyText?.takeIf(String::isNotBlank) ?: return false
            val results = Bundle()
            remoteInputs.forEach { input -> results.putCharSequence(input.resultKey, text) }
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        }
        return try {
            action.actionIntent.send(context, 0, intent)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    fun dismiss(notificationId: String): Boolean {
        if (clearableByNotificationId[notificationId] != true) return false
        val service = listener ?: return false
        return try {
            service.cancelNotification(notificationId)
            forget(notificationId)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
