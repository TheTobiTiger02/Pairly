package dev.smartphonelink.android.features.files

import java.io.File
import java.io.IOException

/**
 * Decides which paths the PC is allowed to reach through the storage browser.
 *
 * The PC is trusted, but "trusted" is not "unbounded": a malformed or malicious path must not be
 * able to walk out of shared storage with `../`. Canonicalising both sides and requiring
 * containment is the only check that survives symlinks and redundant separators.
 */
object StoragePathPolicy {

    /**
     * Largest single file the request/response path will carry. Inbound host messages are capped
     * at 12 MiB, and the response embeds the file as Base64, which inflates by 4/3 - so the raw
     * ceiling has to sit well under that.
     */
    const val MAX_INLINE_FILE_BYTES: Long = 8L * 1024L * 1024L

    /** Returns the resolved file, or null when [relativePath] escapes [root]. */
    fun resolve(root: File, relativePath: String?): File? {
        val canonicalRoot = try {
            root.canonicalFile
        } catch (e: IOException) {
            return null
        }

        if (relativePath.isNullOrBlank() || relativePath == "/" || relativePath == ".") {
            return canonicalRoot
        }

        val candidate = try {
            File(canonicalRoot, relativePath.removePrefix("/")).canonicalFile
        } catch (e: IOException) {
            return null
        }

        return if (isContained(canonicalRoot, candidate)) candidate else null
    }

    fun isContained(canonicalRoot: File, canonicalCandidate: File): Boolean {
        val rootPath = canonicalRoot.path
        val candidatePath = canonicalCandidate.path
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    fun exceedsInlineLimit(sizeBytes: Long): Boolean = sizeBytes > MAX_INLINE_FILE_BYTES

    fun tooLargeMessage(sizeBytes: Long): String {
        val limitMib = MAX_INLINE_FILE_BYTES / (1024 * 1024)
        val actualMib = sizeBytes.toDouble() / (1024.0 * 1024.0)
        return "File is %.1f MiB. The direct download limit is %d MiB.".format(actualMib, limitMib)
    }
}
