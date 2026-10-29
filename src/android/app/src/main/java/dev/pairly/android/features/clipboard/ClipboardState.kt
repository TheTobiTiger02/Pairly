package dev.pairly.android.features.clipboard

/** Ordering belongs to the connection, not an activity or a five-second timer. */
class ClipboardState {
    private var signature: String? = null
    private var session = 0L
    private var sequence = 0L
    private var localRevision = 0L
    private var contentVersion = 0L
    private var remoteItemId = ""
    private var remoteHash = ""
    private var identity: Identity? = null

    data class Identity(val type: String, val fingerprint: String)
    data class Delivery(val session: Long, val sequence: Long, val localRevision: Long)

    private fun sameContent(originId: String, originHash: String, content: Identity?): Boolean =
        if (content != null && identity != null) content == identity
        else originId.isNotEmpty() && originId == remoteItemId && originHash == remoteHash

    @Synchronized fun observed(signature: String): Boolean = this.signature == signature
    @Synchronized fun version(): Long = contentVersion
    /** Native image grants can be revoked during a read when another copy replaces the clip. */
    fun <T> readCurrent(expectedVersion: Long, nativeCurrent: () -> Boolean, read: () -> T): T? {
        val result = runCatching(read)
        if (version() != expectedVersion || !nativeCurrent()) return null
        return result.getOrThrow()
    }
    @Synchronized fun currentRemoteItem(): String = remoteItemId
    @Synchronized fun resetBaseline() { signature = null; contentVersion++ }
    @Synchronized fun observe(signature: String, originId: String = "", originHash: String = "", content: Identity? = null, expectedVersion: Long = contentVersion): Boolean {
        if (expectedVersion != contentVersion) return false
        val baseline = this.signature == null
        if (this.signature == signature) return false
        this.signature = signature
        contentVersion++
        if (sameContent(originId, originHash, content)) return false
        val previousContentKnown = identity != null
        remoteItemId = ""; remoteHash = ""; identity = content
        if (baseline) {
            // Resuming access must not replay history, but a different baseline
            // still supersedes an older copy that was waiting for the network.
            if (previousContentKnown) localRevision++
            return false
        }
        localRevision++
        return true
    }

    @Synchronized fun changed(): Long { contentVersion++; identity = null; remoteItemId = ""; remoteHash = ""; return ++localRevision }
    @Synchronized fun unobservedCopy(signature: String, originId: String, originHash: String, content: Identity? = null, expectedVersion: Long = contentVersion): Boolean {
        if (expectedVersion != contentVersion) return false
        if (this.signature == null) {
            observe(signature, originId, originHash, content, expectedVersion)
            return false
        }
        if (sameContent(originId, originHash, content)) {
            this.signature = signature
            contentVersion++
            return false
        }
        if (this.signature == signature) return false
        // Leave the signature for capture to consume and publish on its worker.
        contentVersion++; identity = null; remoteItemId = ""; remoteHash = ""
        localRevision++
        return true
    }
    @Synchronized fun revision(): Long = localRevision
    @Synchronized fun resetSession() { session++; sequence = 0L }
    @Synchronized fun clear() { signature = null; identity = null; remoteItemId = ""; remoteHash = ""; contentVersion++; localRevision++; resetSession() }
    @Synchronized fun published(sequence: Long) { this.sequence = maxOf(this.sequence, sequence) }
    @Synchronized fun receive(sequence: Long): Delivery? {
        if (sequence <= this.sequence) return null
        this.sequence = sequence
        return Delivery(session, sequence, localRevision)
    }
    @Synchronized fun canApply(delivery: Delivery): Boolean =
        delivery.session == session && delivery.sequence == sequence && delivery.localRevision == localRevision
    @Synchronized fun inSession(delivery: Delivery): Boolean = delivery.session == session
    @Synchronized fun apply(delivery: Delivery, itemId: String, hash: String, content: Identity? = null, action: () -> Unit): Boolean {
        if (!canApply(delivery)) return false
        action()
        localRevision++ // Invalidate a local capture that was still preparing when this item committed.
        rememberRemote(itemId, hash, content)
        return true
    }
    @Synchronized fun rememberRemote(itemId: String, hash: String, content: Identity? = null) {
        remoteItemId = itemId; remoteHash = hash; identity = content; contentVersion++
    }
}
