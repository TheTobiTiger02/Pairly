package dev.smartphonelink.android.features.clipboard

import java.util.UUID

/** One immutable copy keeps its identity and deadline across network retries. */
data class PendingClipboardCopy(
    val itemId: String = UUID.randomUUID().toString().replace("-", ""),
    val transferId: String = UUID.randomUUID().toString().replace("-", ""),
    val text: String? = null,
    val filePath: String? = null,
    val fileName: String = "clipboard.png",
    val automatic: Boolean = false,
    val revision: Long,
    val expiresAt: Long = System.currentTimeMillis() + 600_000L
) {
    fun current(now: Long, revision: Long): Boolean = expiresAt > now && (!automatic || this.revision == revision)
    fun clearedBy(revision: Long): Boolean = automatic && this.revision <= revision
}
