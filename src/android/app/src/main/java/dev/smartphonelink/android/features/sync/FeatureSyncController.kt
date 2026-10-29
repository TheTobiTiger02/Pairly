package dev.smartphonelink.android.features.sync

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Telephony
import dev.smartphonelink.android.features.media.PhotoRepository
import dev.smartphonelink.android.features.notifications.LastNotificationRelay
import dev.smartphonelink.android.features.sms.SmsRepository
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.smartphonelink.android.features.media.PhotoPage
import dev.smartphonelink.android.features.media.PhotoPageRequest
import dev.smartphonelink.android.network.ConnectionSettings
import dev.smartphonelink.android.network.PhoneLinkConnection
import dev.smartphonelink.android.protocol.ProtocolJson
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object FeatureSyncController {
    const val FEATURE_ALL = "all"
    const val FEATURE_DEVICE = "device"
    const val FEATURE_PHOTOS = "photos"
    const val FEATURE_NOTIFICATIONS = "notifications"
    const val FEATURE_SMS = "sms"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var observersRegistered = false
    private var mediaObserver: ContentObserver? = null
    private var smsObserver: ContentObserver? = null
    private val syncMediaRunnable = Runnable { syncFeatures(lastContext ?: return@Runnable, setOf(FEATURE_PHOTOS)) }
    private val syncSmsRunnable = Runnable { syncFeatures(lastContext ?: return@Runnable, setOf(FEATURE_SMS)) }
    private var lastContext: Context? = null
    private val mediaExecutor = ThreadPoolExecutor(
        1,
        2,
        15L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(8),
        { runnable -> Thread(runnable, "SmartphoneLink-MediaIndex").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    ).apply { allowCoreThreadTimeOut(true) }

    fun registerObservers(context: Context) {
        if (observersRegistered) {
            return
        }

        val appContext = context.applicationContext
        lastContext = appContext
        mediaObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                scheduleMediaSync(appContext)
            }
        }
        smsObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                scheduleSmsSync(appContext)
            }
        }

        runCatching {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver!!
            )
        }
        runCatching {
            appContext.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                smsObserver!!
            )
        }
        observersRegistered = true
    }

    fun unregisterObservers(context: Context) {
        val appContext = context.applicationContext
        mediaObserver?.let { runCatching { appContext.contentResolver.unregisterContentObserver(it) } }
        smsObserver?.let { runCatching { appContext.contentResolver.unregisterContentObserver(it) } }
        mediaObserver = null
        smsObserver = null
        observersRegistered = false
    }

    fun syncAll(context: Context) {
        syncFeatures(context, setOf(FEATURE_ALL))
    }

    fun syncFeatures(context: Context, requestedFeatures: Set<String>) {
        val appContext = context.applicationContext
        lastContext = appContext
        if (!PhoneLinkConnection.isReady) {
            return
        }

        val features = if (requestedFeatures.isEmpty() || requestedFeatures.contains(FEATURE_ALL)) {
            setOf(FEATURE_DEVICE, FEATURE_PHOTOS, FEATURE_NOTIFICATIONS, FEATURE_SMS)
        } else {
            requestedFeatures
        }

        if (features.contains(FEATURE_DEVICE)) {
            sendDeviceState(appContext)
        }
        if (features.contains(FEATURE_PHOTOS)) {
            sendPhotoIndex(appContext)
        }
        if (features.contains(FEATURE_NOTIFICATIONS)) {
            sendActiveNotifications()
        }
        if (features.contains(FEATURE_SMS)) {
            sendSmsSnapshot(appContext)
        }
    }

    fun sendDeviceState(context: Context, mirroringActive: Boolean = false) {
        if (!PhoneLinkConnection.isReady) {
            return
        }

        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else null

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = if (status >= 0) {
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } else null

        PhoneLinkConnection.send(
            ProtocolJson.deviceState(
                deviceId = deviceId(context),
                displayName = Build.MODEL ?: "Android device",
                connected = true,
                permissions = FeaturePermissionState.permissions(context, mirroringActive),
                batteryLevel = batteryPct,
                isCharging = isCharging
            )
        )
    }

    fun sendPhotoIndex(context: Context) {
        requestPhotoPage(
            context = context,
            cursor = null,
            pageSize = 100,
            albumId = null,
            search = null,
            snapshotId = null
        )
    }

    /**
     * Queries one keyset page on a bounded worker. The host supplies the returned cursor when the
     * gallery needs another page, keeping scrolling incremental instead of rebuilding the index.
     */
    fun requestPhotoPage(
        context: Context,
        cursor: String?,
        pageSize: Int,
        albumId: String?,
        search: String?,
        snapshotId: String?,
        mimeTypes: Set<String> = emptySet()
    ) {
        val appContext = context.applicationContext
        lastContext = appContext
        if (!PhoneLinkConnection.isReady) return
        executeMediaIo mediaTask@{
            val page = if (FeaturePermissionState.hasPhotoAccess(appContext)) {
                PhotoRepository(appContext).page(
                    PhotoPageRequest(
                        cursor = cursor,
                        pageSize = pageSize,
                        albumId = albumId,
                        search = search,
                        snapshotId = snapshotId,
                        mimeTypes = mimeTypes
                    )
                )
            } else {
                PhotoPage(
                    items = emptyList(),
                    cursor = null,
                    snapshotId = snapshotId?.takeIf(String::isNotBlank)
                        ?: UUID.randomUUID().toString().replace("-", ""),
                    snapshotStart = true,
                    snapshotComplete = true
                )
            }
            if (!PhoneLinkConnection.isReady) return@mediaTask
            val id = deviceId(appContext)
            if (id.isBlank()) return@mediaTask
            PhoneLinkConnection.send(
                ProtocolJson.mediaIndexPage(
                    deviceId = id,
                    items = page.items,
                    nextPageToken = page.cursor,
                    cursor = page.cursor,
                    snapshotId = page.snapshotId,
                    snapshotStart = page.snapshotStart,
                    snapshotComplete = page.snapshotComplete
                )
            )
        }
    }

    /** Runs media reads/compression on the same bounded pool used by index paging. */
    fun executeMediaIo(block: () -> Unit): Boolean {
        if (mediaExecutor.isShutdown) return false
        return runCatching { mediaExecutor.execute(block) }.isSuccess
    }

    fun sendSmsSnapshot(context: Context) {
        val repository = SmsRepository(context)
        val threads = if (FeaturePermissionState.hasSmsAccess(context)) repository.recentThreads(100) else emptyList()
        PhoneLinkConnection.send(ProtocolJson.smsThreadPage(deviceId(context), threads))
        if (FeaturePermissionState.hasSmsAccess(context)) {
            repository.groupRecentMessagesByThread(200).forEach { (threadId, messages) ->
                PhoneLinkConnection.send(ProtocolJson.smsMessagePage(deviceId(context), threadId, messages))
            }
        }
    }

    fun sendActiveNotifications() {
        val deviceId = PhoneLinkConnection.deviceId
        if (deviceId.isBlank() || !PhoneLinkConnection.isReady) {
            return
        }
        LastNotificationRelay.requestActiveSnapshot(deviceId)
    }

    fun scheduleMediaSync(context: Context) {
        val appContext = context.applicationContext
        lastContext = appContext
        mainHandler.removeCallbacks(syncMediaRunnable)
        mainHandler.postDelayed(syncMediaRunnable, 1_000L)
    }

    fun scheduleSmsSync(context: Context) {
        val appContext = context.applicationContext
        lastContext = appContext
        mainHandler.removeCallbacks(syncSmsRunnable)
        mainHandler.postDelayed(syncSmsRunnable, 1_000L)
    }

    private fun deviceId(context: Context): String =
        PhoneLinkConnection.deviceId.ifBlank { ConnectionSettings(context).deviceId }
}
