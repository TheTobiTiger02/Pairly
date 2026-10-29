package dev.smartphonelink.android.features.clipboard

import java.security.MessageDigest

object ClipboardTransferPolicy {
    const val MAX_TEXT_BYTES = 1024 * 1024
    const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
    const val CHUNK_BYTES = 256 * 1024
    const val MAX_LONG_EDGE = 4096
    const val MAX_CACHE_BYTES = 64L * 1024L * 1024L

    data class CacheFile(val path: String, val bytes: Long, val modified: Long)
    fun cacheEvictions(files: List<CacheFile>, active: String?, pending: String?, now: Long): Set<String> {
        val removable = files.filter { it.path != active && it.path != pending }.sortedBy { it.modified }
        val evicted = removable.filter { expiredCache(it.path, active, it.modified, now) }.mapTo(linkedSetOf()) { it.path }
        var retainedBytes = files.filter { it.path !in evicted }.sumOf { it.bytes }
        for (file in removable) {
            if (retainedBytes <= MAX_CACHE_BYTES) break
            if (evicted.add(file.path)) retainedBytes -= file.bytes
        }
        return evicted
    }

    fun contentType(mimeTypes: List<String>): String = when {
        mimeTypes.any { it.startsWith("image/", true) } -> "image/png"
        mimeTypes.any { it.equals("text/plain", true) || it.equals("text/html", true) } -> "text/plain"
        else -> "unsupported"
    }
    fun mayCapture(sensitive: Boolean, contentType: String): Boolean =
        !sensitive && contentType in setOf("text/plain", "image/png")
    fun expiredCache(path: String, activeImage: String?, lastModified: Long, now: Long): Boolean =
        path != activeImage && now - lastModified >= 600_000L

    fun acceptsText(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES

    fun acceptsImage(byteCount: Long, width: Int, height: Int): Boolean =
        byteCount in 1..MAX_IMAGE_BYTES && width > 0 && height > 0 && maxOf(width, height) <= MAX_LONG_EDGE

    fun chunkCount(byteCount: Int): Int =
        if (byteCount <= 0) 0 else (byteCount + CHUNK_BYTES - 1) / CHUNK_BYTES

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
