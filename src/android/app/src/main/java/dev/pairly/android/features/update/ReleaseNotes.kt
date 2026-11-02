package dev.pairly.android.features.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The "What's new" section of a GitHub release, shown once after an update. */
object ReleaseNotes {
    private const val RELEASE_BY_TAG_URL = "https://api.github.com/repos/TheTobiTiger02/Pairly/releases/tags/v"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Bullet points for [version], or null when offline or the release has none. */
    suspend fun fetch(version: String): List<String>? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASE_BY_TAG_URL + version.removePrefix("v"))
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parse(JSONObject(response.body?.string().orEmpty()).optString("body"))
            }
        }.getOrNull()
    }

    /**
     * Takes the lines under "## What's new" (the Release workflow writes that section) and strips
     * Markdown list markers, links and emphasis.
     */
    fun parse(body: String): List<String>? {
        val lines = body.lines()
        val start = lines.indexOfFirst { it.trim().startsWith("## ") && it.contains("new", ignoreCase = true) }
        val section = if (start >= 0) {
            lines.drop(start + 1).takeWhile { !it.trim().startsWith("## ") }
        } else {
            lines.takeWhile { !it.trim().startsWith("## ") }
        }
        val notes = section.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("**Full changelog", ignoreCase = true) }
            .map { line ->
                line.removePrefix("- ").removePrefix("* ")
                    .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                    .replace("**", "")
                    .replace("`", "")
                    .trim()
            }
            .filter { it.isNotEmpty() }
        return notes.takeIf { it.isNotEmpty() }
    }
}
