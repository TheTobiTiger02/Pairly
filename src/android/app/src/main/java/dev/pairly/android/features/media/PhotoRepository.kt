package dev.pairly.android.features.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID

data class MediaBytes(
    val mediaId: String,
    val contentType: String,
    val fileName: String,
    val bytes: ByteArray
)

class MediaStream(
    val mediaId: String,
    val contentType: String,
    val fileName: String,
    val byteCount: Long,
    val inputStream: InputStream
) : Closeable {
    override fun close() = inputStream.close()
}

data class PhotoPageRequest(
    val cursor: String? = null,
    val pageSize: Int = PhotoPagingPolicy.DEFAULT_PAGE_SIZE,
    val albumId: String? = null,
    val search: String? = null,
    val snapshotId: String? = null,
    val mimeTypes: Set<String> = emptySet()
)

data class PhotoPage(
    val items: List<JSONObject>,
    val cursor: String?,
    val snapshotId: String,
    val snapshotStart: Boolean,
    val snapshotComplete: Boolean
)

data class PhotoCursor(
    val snapshotId: String,
    val capturedAtSeconds: Long,
    val lastAddedAtSeconds: Long,
    val lastMediaId: Long,
    val filterKey: String
)

data class PhotoDate(
    val epochMillis: Long,
    val source: String
)

/** Pure paging rules kept separate so cursor behavior can be verified in JVM tests. */
object PhotoPagingPolicy {
    const val DEFAULT_PAGE_SIZE = 100
    const val MAX_PAGE_SIZE = 200
    private const val TOKEN_VERSION = "v1"

    fun pageSize(requested: Int): Int = requested.coerceIn(1, MAX_PAGE_SIZE)

    fun filterKey(albumId: String?, search: String?, mimeTypes: Set<String>): String {
        val canonical = buildString {
            append(albumId.orEmpty().trim())
            append('\u0000')
            append(search.orEmpty().trim().lowercase(Locale.ROOT))
            append('\u0000')
            append(mimeTypes.map { it.lowercase(Locale.ROOT) }.sorted().joinToString(","))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    fun encodeCursor(cursor: PhotoCursor): String {
        val raw = listOf(
            TOKEN_VERSION,
            cursor.snapshotId,
            cursor.capturedAtSeconds,
            cursor.lastAddedAtSeconds,
            cursor.lastMediaId,
            cursor.filterKey
        ).joinToString("|")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    fun decodeCursor(value: String?): PhotoCursor? {
        if (value.isNullOrBlank() || value.length > 1024) return null
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8).split('|')
            if (decoded.size != 6 || decoded[0] != TOKEN_VERSION) return null
            val snapshotId = decoded[1].takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
            val capturedAt = decoded[2].toLong().takeIf { it > 0 } ?: return null
            val addedAt = decoded[3].toLong().takeIf { it >= 0 } ?: return null
            val mediaId = decoded[4].toLong().takeIf { it > 0 } ?: return null
            val filterKey = decoded[5].takeIf { it.length == 24 } ?: return null
            PhotoCursor(snapshotId, capturedAt, addedAt, mediaId, filterKey)
        }.getOrNull()
    }

    fun chooseDate(dateTakenMillis: Long, dateAddedSeconds: Long, dateModifiedSeconds: Long): PhotoDate {
        return when {
            dateTakenMillis > 0 -> PhotoDate(dateTakenMillis, "taken")
            dateAddedSeconds > 0 -> PhotoDate(secondsToMillis(dateAddedSeconds), "added")
            dateModifiedSeconds > 0 -> PhotoDate(secondsToMillis(dateModifiedSeconds), "modified")
            else -> PhotoDate(0, "unknown")
        }
    }

    fun normalizeOrientation(value: Int): Int {
        val normalized = ((value % 360) + 360) % 360
        return listOf(0, 90, 180, 270).minBy { candidate ->
            val difference = kotlin.math.abs(candidate - normalized)
            minOf(difference, 360 - difference)
        }
    }

    private fun secondsToMillis(seconds: Long): Long =
        if (seconds > Long.MAX_VALUE / 1000L) Long.MAX_VALUE else seconds * 1000L
}

class PhotoRepository(private val context: Context) {
    fun recentImages(limit: Int = 50): List<JSONObject> =
        page(PhotoPageRequest(pageSize = limit)).items

    fun page(request: PhotoPageRequest): PhotoPage {
        val pageSize = PhotoPagingPolicy.pageSize(request.pageSize)
        val expectedFilterKey = PhotoPagingPolicy.filterKey(request.albumId, request.search, request.mimeTypes)
        val decodedCursor = PhotoPagingPolicy.decodeCursor(request.cursor)?.takeIf { cursor ->
            cursor.filterKey == expectedFilterKey &&
                (request.snapshotId.isNullOrBlank() || request.snapshotId == cursor.snapshotId)
        }
        val snapshotStart = decodedCursor == null
        val requestedSnapshotId = request.snapshotId?.takeIf {
            request.cursor.isNullOrBlank() && it.matches(Regex("[A-Za-z0-9_-]{1,128}"))
        }
        val snapshotId = decodedCursor?.snapshotId
            ?: requestedSnapshotId
            ?: UUID.randomUUID().toString().replace("-", "")
        val capturedAtSeconds = decodedCursor?.capturedAtSeconds ?: Instant.now().epochSecond

        val rows = queryRows(
            pageSize = pageSize + 1,
            capturedAtSeconds = capturedAtSeconds,
            cursor = decodedCursor,
            albumId = request.albumId,
            search = request.search,
            mimeTypes = request.mimeTypes
        )
        val hasMore = rows.size > pageSize
        val visibleRows = if (hasMore) rows.take(pageSize) else rows
        val nextCursor = if (hasMore) {
            visibleRows.lastOrNull()?.let { row ->
                PhotoPagingPolicy.encodeCursor(
                    PhotoCursor(
                        snapshotId = snapshotId,
                        capturedAtSeconds = capturedAtSeconds,
                        lastAddedAtSeconds = row.addedAtSeconds,
                        lastMediaId = row.mediaId,
                        filterKey = expectedFilterKey
                    )
                )
            }
        } else {
            null
        }

        return PhotoPage(
            items = visibleRows.map(PhotoRow::toJson),
            cursor = nextCursor,
            snapshotId = snapshotId,
            snapshotStart = snapshotStart,
            snapshotComplete = !hasMore
        )
    }

    fun readMediaBytes(mediaId: String, kind: String): MediaBytes? {
        val uri = mediaId.toLongOrNull()?.let {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
        } ?: return null

        val metadata = queryMetadata(mediaId)
        val bytes = try {
            if (kind == "thumb") {
                readThumbnailBytes(uri) ?: readOriginalBytesBounded(uri, metadata?.byteCount)
            } else {
                readOriginalBytesBounded(uri, metadata?.byteCount)
            }
        } catch (_: RuntimeException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return null

        return MediaBytes(
            mediaId = mediaId,
            contentType = if (kind == "thumb") {
                "image/jpeg"
            } else {
                metadata?.contentType.orEmpty().ifBlank { "application/octet-stream" }
            },
            fileName = metadata?.fileName.orEmpty().ifBlank { "image-$mediaId.jpg" },
            bytes = bytes
        )
    }

    /** Opens an original without buffering it so v2 callers can emit verified transfer chunks. */
    fun openMediaStream(mediaId: String): MediaStream? {
        val uri = mediaId.toLongOrNull()?.let {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
        } ?: return null
        val metadata = queryMetadata(mediaId) ?: return null
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: RuntimeException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return null
        return MediaStream(
            mediaId = mediaId,
            contentType = metadata.contentType.ifBlank { "application/octet-stream" },
            fileName = metadata.fileName.ifBlank { "image-$mediaId" },
            byteCount = metadata.byteCount,
            inputStream = input
        )
    }

    private fun queryRows(
        pageSize: Int,
        capturedAtSeconds: Long,
        cursor: PhotoCursor?,
        albumId: String?,
        search: String?,
        mimeTypes: Set<String>
    ): List<PhotoRow> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.ORIENTATION
        )
        val clauses = mutableListOf("${MediaStore.Images.Media.DATE_ADDED} <= ?")
        val args = mutableListOf(capturedAtSeconds.toString())
        cursor?.let {
            clauses += "(${MediaStore.Images.Media.DATE_ADDED} < ? OR " +
                "(${MediaStore.Images.Media.DATE_ADDED} = ? AND ${MediaStore.Images.Media._ID} < ?))"
            args += it.lastAddedAtSeconds.toString()
            args += it.lastAddedAtSeconds.toString()
            args += it.lastMediaId.toString()
        }
        albumId?.trim()?.takeIf(String::isNotEmpty)?.let {
            clauses += "${MediaStore.Images.Media.BUCKET_ID} = ?"
            args += it
        }
        search?.trim()?.takeIf(String::isNotEmpty)?.let {
            clauses += "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            args += "%$it%"
        }
        val normalizedMimes = mimeTypes
            .map { it.lowercase(Locale.ROOT) }
            .filter(String::isNotBlank)
            .distinct()
        if (normalizedMimes.isNotEmpty()) {
            clauses += "${MediaStore.Images.Media.MIME_TYPE} IN (${normalizedMimes.joinToString(",") { "?" }})"
            args += normalizedMimes
        }

        val selection = clauses.joinToString(" AND ")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        val resolver = context.contentResolver
        val cursorResult = try {
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
            }
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, queryArgs, null)
        } catch (_: RuntimeException) {
            runCatching {
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args.toTypedArray(),
                    sortOrder
                )
            }.getOrNull()
        } catch (_: SecurityException) {
            null
        }

        cursorResult?.use { cursorValue ->
            val idColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val takenColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val albumIdColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val albumNameColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val orientationColumn = cursorValue.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val rows = ArrayList<PhotoRow>(pageSize)
            while (cursorValue.moveToNext() && rows.size < pageSize) {
                val takenAtMillis = cursorValue.getLong(takenColumn)
                val addedAtSeconds = cursorValue.getLong(addedColumn)
                val modifiedAtSeconds = cursorValue.getLong(modifiedColumn)
                val chosenDate = PhotoPagingPolicy.chooseDate(
                    takenAtMillis,
                    addedAtSeconds,
                    modifiedAtSeconds
                )
                rows += PhotoRow(
                    mediaId = cursorValue.getLong(idColumn),
                    fileName = cursorValue.getString(nameColumn).orEmpty(),
                    mimeType = cursorValue.getString(mimeColumn).orEmpty(),
                    byteCount = cursorValue.getLong(sizeColumn),
                    width = cursorValue.getInt(widthColumn),
                    height = cursorValue.getInt(heightColumn),
                    takenAtMillis = chosenDate.epochMillis,
                    dateSource = chosenDate.source,
                    addedAtSeconds = addedAtSeconds,
                    modifiedAtSeconds = modifiedAtSeconds,
                    albumId = cursorValue.getString(albumIdColumn).orEmpty(),
                    albumName = cursorValue.getString(albumNameColumn).orEmpty(),
                    orientationDegrees = PhotoPagingPolicy.normalizeOrientation(cursorValue.getInt(orientationColumn))
                )
            }
            return rows
        }
        return emptyList()
    }

    private fun queryMetadata(mediaId: String): MediaMetadata? {
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE
        )
        val selection = "${MediaStore.Images.Media._ID} = ?"
        val cursor = try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(mediaId),
                null
            )
        } catch (_: RuntimeException) {
            null
        }

        cursor?.use {
            if (it.moveToFirst()) {
                return MediaMetadata(
                    fileName = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)).orEmpty(),
                    contentType = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)).orEmpty(),
                    byteCount = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                )
            }
        }
        return null
    }

    /** The v1 response is still a single Base64 value, so cap the in-memory read to avoid OOMs. */
    private fun readOriginalBytesBounded(uri: Uri, declaredBytes: Long?): ByteArray? {
        if (declaredBytes != null && declaredBytes > MAX_V1_MEDIA_BYTES) return null
        return context.contentResolver.openInputStream(uri)?.use { input ->
            ByteArrayOutputStream(
                declaredBytes?.takeIf { it in 1..MAX_V1_MEDIA_BYTES }?.toInt() ?: DEFAULT_BUFFER_BYTES
            ).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_V1_MEDIA_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private fun readThumbnailBytes(uri: Uri): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val bitmap = context.contentResolver.loadThumbnail(uri, Size(360, 360), null)
        return try {
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 84, stream)
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private data class MediaMetadata(
        val fileName: String,
        val contentType: String,
        val byteCount: Long
    )

    private data class PhotoRow(
        val mediaId: Long,
        val fileName: String,
        val mimeType: String,
        val byteCount: Long,
        val width: Int,
        val height: Int,
        val takenAtMillis: Long,
        val dateSource: String,
        val addedAtSeconds: Long,
        val modifiedAtSeconds: Long,
        val albumId: String,
        val albumName: String,
        val orientationDegrees: Int
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("mediaId", mediaId.toString())
            .put("fileName", fileName)
            .put("mimeType", mimeType)
            .put("byteCount", byteCount)
            .put("width", width)
            .put("height", height)
            .put("takenAt", instantOrEpoch(takenAtMillis))
            .put("modifiedAt", instantOrEpoch(safeSecondsToMillis(modifiedAtSeconds)))
            .put("dateSource", dateSource)
            .put("albumId", albumId)
            .put("albumName", albumName.ifBlank { "Other" })
            .put("orientationDegrees", orientationDegrees)

        private fun instantOrEpoch(epochMillis: Long): String =
            runCatching { Instant.ofEpochMilli(epochMillis.coerceAtLeast(0)).toString() }
                .getOrDefault(Instant.EPOCH.toString())

        private fun safeSecondsToMillis(seconds: Long): Long =
            when {
                seconds <= 0 -> 0
                seconds > Long.MAX_VALUE / 1000L -> Long.MAX_VALUE
                else -> seconds * 1000L
            }
    }

    private companion object {
        const val MAX_V1_MEDIA_BYTES = 32L * 1024L * 1024L
        const val DEFAULT_BUFFER_BYTES = 64 * 1024
    }
}
