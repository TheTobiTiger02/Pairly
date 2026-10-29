package dev.smartphonelink.android.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

object MessageTypes {
    const val SessionChallenge = "session.challenge"
    const val SessionAuthenticate = "session.authenticate"
    const val SessionReady = "session.ready"
    const val PairHello = "pair.hello"
    const val DeviceState = "device.state"
    const val DeviceSyncRequest = "device.sync.request"
    const val DeviceForget = "device.forget"
    const val DeviceForgetConfirm = "device.forget.confirm"
    const val ClipboardItem = "clipboard.item"
    const val ClipboardAck = "clipboard.ack"
    const val ContentTransferStart = "content.transfer.start"
    const val ContentTransferChunk = "content.transfer.chunk"
    const val ContentTransferComplete = "content.transfer.complete"
    const val MediaIndexRequest = "media.index.request"
    const val MediaIndexPage = "media.index.page"
    const val MediaContentRequest = "media.content.request"
    const val MediaContentResponse = "media.content.response"
    const val NotificationUpsert = "notification.upsert"
    const val NotificationRemove = "notification.remove"
    const val NotificationSnapshotBegin = "notification.snapshot.begin"
    const val NotificationSnapshotEnd = "notification.snapshot.end"
    const val SmsThreadPage = "sms.thread.page"
    const val SmsMessagePage = "sms.message.page"
    const val MirrorStart = "mirror.start"
    const val MirrorSignal = "mirror.signal"
    const val MirrorStop = "mirror.stop"
    const val MirrorConfigure = "mirror.configure"
    const val MirrorTelemetry = "mirror.telemetry"
    const val MirrorState = "mirror.state"
    const val HeartbeatPing = "heartbeat.ping"
    const val HeartbeatPong = "heartbeat.pong"
    const val CommandResult = "command.result"
}

data class DeviceCapabilities(
    val clipboard: Boolean,
    val photos: Boolean,
    val notifications: Boolean,
    val sms: Boolean,
    val mirroring: Boolean
) {
    fun toJson() = JSONObject()
        .put("clipboard", clipboard)
        .put("photos", photos)
        .put("notifications", notifications)
        .put("sms", sms)
        .put("mirroring", mirroring)
}

object ProtocolJson {
    private var sequence = 0L

    @Synchronized
    fun envelope(type: String, deviceId: String, payload: JSONObject): String {
        sequence += 1
        return JSONObject()
            .put("v", 1)
            .put("id", UUID.randomUUID().toString().replace("-", ""))
            .put("type", type)
            .put("deviceId", deviceId)
            .put("seq", sequence)
            .put("timestamp", Instant.now().toString())
            .put("payload", payload)
            .toString()
    }

    fun pairHello(
        pairingCode: String,
        deviceId: String,
        displayName: String,
        capabilities: DeviceCapabilities,
        trustKey: String? = null,
        hostId: String? = null,
        protocolVersion: Int? = null
    ): String {
        val payload = JSONObject()
            .put("pairingCode", pairingCode)
            .put("deviceId", deviceId)
            .put("displayName", displayName)
            .put("platform", "android")
            .put("capabilities", capabilities.toJson())
        if (!trustKey.isNullOrBlank()) {
            payload.put("trustKey", trustKey)
        }
        if (!hostId.isNullOrBlank()) payload.put("hostId", hostId)
        protocolVersion?.let { payload.put("protocolVersion", it) }
        return envelope(MessageTypes.PairHello, deviceId, payload)
    }

    fun sessionAuthenticate(
        deviceId: String,
        challengeId: String,
        proof: String,
        protocolVersion: Int = 2
    ): String = envelope(
        MessageTypes.SessionAuthenticate,
        deviceId,
        JSONObject()
            .put("challengeId", challengeId)
            .put("deviceId", deviceId)
            .put("proof", proof)
            .put("protocolVersion", protocolVersion)
    )

    fun clipboardText(deviceId: String, text: String, itemId: String = UUID.randomUUID().toString().replace("-", ""), expiresAt: String? = null): String {
        val payload = JSONObject()
            .put("itemId", itemId)
            .put("sourceDeviceId", deviceId)
            .put("contentType", "text/plain")
            .put("text", text)
            .put("fileName", JSONObject.NULL)
            .put("byteCount", text.toByteArray(Charsets.UTF_8).size)
            .put("ephemeral", true)
        expiresAt?.let { payload.put("expiresAt", it) }
        return envelope(MessageTypes.ClipboardItem, deviceId, payload)
    }

    fun clipboardImageMetadata(
        deviceId: String,
        itemId: String,
        transferId: String,
        fileName: String,
        byteCount: Long,
        contentHash: String,
        expiresAt: String
    ): String {
        val payload = JSONObject()
            .put("itemId", itemId)
            .put("sourceDeviceId", deviceId)
            .put("contentType", "image/png")
            .put("fileName", fileName)
            .put("byteCount", byteCount)
            .put("ephemeral", true)
            .put("transferId", transferId)
            .put("contentHash", contentHash)
            .put("expiresAt", expiresAt)
        return envelope(MessageTypes.ClipboardItem, deviceId, payload)
    }

    fun contentTransferStart(
        deviceId: String,
        transferId: String,
        itemId: String,
        fileName: String,
        byteCount: Long,
        chunkSize: Int,
        chunkCount: Int,
        contentHash: String,
        expiresAt: String,
        contentType: String = "image/png",
        purpose: String = "clipboard"
    ): String = envelope(
        MessageTypes.ContentTransferStart,
        deviceId,
        JSONObject()
            .put("transferId", transferId)
            .put("itemId", itemId)
            .put("sourceDeviceId", deviceId)
            .put("contentType", contentType)
            .put("fileName", fileName)
            .put("byteCount", byteCount)
            .put("chunkSize", chunkSize)
            .put("chunkCount", chunkCount)
            .put("contentHash", contentHash)
            .put("expiresAt", expiresAt)
            .put("purpose", purpose)
    )

    fun contentTransferChunk(
        deviceId: String,
        transferId: String,
        chunkIndex: Int,
        base64: String,
        chunkHash: String
    ): String = envelope(
        MessageTypes.ContentTransferChunk,
        deviceId,
        JSONObject()
            .put("transferId", transferId)
            .put("chunkIndex", chunkIndex)
            .put("base64", base64)
            .put("chunkHash", chunkHash)
    )

    fun contentTransferComplete(
        deviceId: String,
        transferId: String,
        contentHash: String
    ): String = envelope(
        MessageTypes.ContentTransferComplete,
        deviceId,
        JSONObject()
            .put("transferId", transferId)
            .put("contentHash", contentHash)
    )

    fun clipboardAck(
        deviceId: String,
        itemId: String,
        relaySequence: Long?,
        status: String,
        message: String
    ): String {
        val payload = JSONObject()
            .put("itemId", itemId)
            .put("status", status)
            .put("message", message)
            .put("appliedAt", Instant.now().toString())
        relaySequence?.let { payload.put("relaySequence", it) }
        return envelope(MessageTypes.ClipboardAck, deviceId, payload)
    }

    fun deviceState(
        deviceId: String,
        displayName: String,
        connected: Boolean,
        permissions: Map<String, String> = defaultPermissions(),
        batteryLevel: Int? = null,
        isCharging: Boolean? = null
    ): String {
        val permissionsJson = JSONObject()
        permissions.forEach { (key, value) -> permissionsJson.put(key, value) }
        val payload = JSONObject()
            .put("displayName", displayName)
            .put("connected", connected)
            .put("permissions", permissionsJson)
        if (batteryLevel != null && batteryLevel >= 0) {
            payload.put("batteryLevel", batteryLevel)
        }
        if (isCharging != null) {
            payload.put("isCharging", isCharging)
        }
        return envelope(MessageTypes.DeviceState, deviceId, payload)
    }

    fun mediaIndexPage(
        deviceId: String,
        items: List<JSONObject>,
        nextPageToken: String? = null,
        cursor: String? = null,
        snapshotId: String? = null,
        snapshotStart: Boolean = false,
        snapshotComplete: Boolean = false
    ): String {
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("nextPageToken", nextPageToken ?: JSONObject.NULL)
            .put("items", JSONArray(items))
            .put("cursor", cursor ?: JSONObject.NULL)
            .put("snapshotId", snapshotId ?: JSONObject.NULL)
            .put("snapshotStart", snapshotStart)
            .put("snapshotComplete", snapshotComplete)
        return envelope(MessageTypes.MediaIndexPage, deviceId, payload)
    }

    fun mediaContentResponse(
        deviceId: String,
        requestId: String,
        mediaId: String,
        kind: String,
        contentType: String?,
        fileName: String?,
        base64: String?,
        byteCount: Long,
        error: String?,
        transferId: String? = null,
        contentHash: String? = null
    ): String {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("mediaId", mediaId)
            .put("kind", kind)
            .put("contentType", contentType ?: JSONObject.NULL)
            .put("fileName", fileName ?: JSONObject.NULL)
            .put("base64", base64 ?: JSONObject.NULL)
            .put("byteCount", byteCount)
            .put("error", error ?: JSONObject.NULL)
            .put("transferId", transferId ?: JSONObject.NULL)
            .put("contentHash", contentHash ?: JSONObject.NULL)
        return envelope(MessageTypes.MediaContentResponse, deviceId, payload)
    }

    fun notificationUpsert(deviceId: String, payload: JSONObject): String =
        envelope(MessageTypes.NotificationUpsert, deviceId, payload)

    fun notificationRemove(deviceId: String, notificationId: String): String =
        envelope(MessageTypes.NotificationRemove, deviceId, JSONObject().put("notificationId", notificationId))

    fun notificationSnapshotBegin(deviceId: String, snapshotId: String): String =
        notificationSnapshot(MessageTypes.NotificationSnapshotBegin, deviceId, snapshotId)

    fun notificationSnapshotEnd(deviceId: String, snapshotId: String): String =
        notificationSnapshot(MessageTypes.NotificationSnapshotEnd, deviceId, snapshotId)

    private fun notificationSnapshot(type: String, deviceId: String, snapshotId: String): String =
        envelope(
            type,
            deviceId,
            JSONObject()
                .put("snapshotId", snapshotId)
                .put("capturedAt", Instant.now().toString())
        )

    fun smsMessagePage(deviceId: String, threadId: String, messages: List<JSONObject>): String {
        val payload = JSONObject()
            .put("threadId", threadId)
            .put("nextPageToken", JSONObject.NULL)
            .put("messages", JSONArray(messages))
        return envelope(MessageTypes.SmsMessagePage, deviceId, payload)
    }

    fun smsThreadPage(deviceId: String, threads: List<JSONObject>): String {
        val payload = JSONObject()
            .put("nextPageToken", JSONObject.NULL)
            .put("threads", JSONArray(threads))
        return envelope(MessageTypes.SmsThreadPage, deviceId, payload)
    }

    fun mirrorState(
        deviceId: String,
        sessionId: String,
        targetDeviceId: String,
        state: String,
        reason: String? = null,
        needsUserAction: Boolean = false
    ): String {
        val payload = JSONObject()
            .put("sessionId", sessionId)
            .put("targetDeviceId", targetDeviceId)
            .put("state", state)
            .put("needsUserAction", needsUserAction)
            .put("changedAt", Instant.now().toString())
        if (!reason.isNullOrBlank()) payload.put("reason", reason)
        return envelope(MessageTypes.MirrorState, deviceId, payload)
    }

    fun mirrorTelemetry(
        deviceId: String,
        sessionId: String,
        targetDeviceId: String,
        roundTripTimeMs: Double?,
        packetLossPercent: Double?,
        availableBitrateBps: Long?,
        encodedFps: Double?,
        qualityLimitationReason: String?,
        frameWidth: Int?,
        frameHeight: Int?
    ): String {
        val payload = JSONObject()
            .put("sessionId", sessionId)
            .put("targetDeviceId", targetDeviceId)
            .put("sampledAt", Instant.now().toString())
        roundTripTimeMs?.let { payload.put("roundTripTimeMs", it) }
        packetLossPercent?.let { payload.put("packetLossPercent", it) }
        availableBitrateBps?.let { payload.put("availableBitrateBps", it) }
        encodedFps?.let { payload.put("encodedFps", it) }
        qualityLimitationReason?.let { payload.put("qualityLimitationReason", it) }
        frameWidth?.let { payload.put("frameWidth", it) }
        frameHeight?.let { payload.put("frameHeight", it) }
        return envelope(MessageTypes.MirrorTelemetry, deviceId, payload)
    }

    fun commandResult(
        deviceId: String,
        requestId: String,
        feature: String,
        success: Boolean,
        message: String
    ): String {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("feature", feature)
            .put("success", success)
            .put("message", message)
        return envelope(MessageTypes.CommandResult, deviceId, payload)
    }

    fun heartbeatPing(deviceId: String): String {
        val payload = JSONObject()
            .put("sessionId", deviceId)
            .put("sentAt", Instant.now().toString())
        return envelope(MessageTypes.HeartbeatPing, deviceId, payload)
    }

    fun deviceForget(deviceId: String, requestId: String): String =
        envelope(
            MessageTypes.DeviceForget,
            deviceId,
            JSONObject().put("requestId", requestId)
        )

    private fun defaultPermissions() = mapOf(
        "clipboard" to "granted",
        "photos" to "needs_user_action",
        "notifications" to "needs_user_action",
        "sms" to "needs_user_action",
        "mirroring" to "needs_user_action"
    )
}
