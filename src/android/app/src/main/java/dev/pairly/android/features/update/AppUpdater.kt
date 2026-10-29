package dev.pairly.android.features.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import dev.pairly.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Self-update from GitHub Releases. Android verifies the downloaded APK carries the same signing
 * certificate as the installed app, so only builds signed with the release key can replace it.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/TheTobiTiger02/Pairly/releases/latest"
    private const val APK_ASSET_NAME = "Pairly.apk"
    private const val PREFS = "app_updater"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private val CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(12)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data object UpToDate : Result
        data class Installing(val version: String) : Result
        data class Failed(val message: String) : Result
    }

    private data class Release(val version: String, val apkUrl: String)

    /** Background check; debug builds are signed differently and never self-update. */
    suspend fun checkIfDue(context: Context) {
        if (BuildConfig.DEBUG) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        val result = checkAndInstall(context)
        if (result is Result.Failed) Log.w(TAG, "Update check failed: ${result.message}")
    }

    suspend fun checkAndInstall(context: Context): Result = withContext(Dispatchers.IO) {
        try {
            val release = fetchLatestRelease() ?: return@withContext Result.Failed("No release with $APK_ASSET_NAME found.")
            if (!isNewerVersion(release.version, BuildConfig.VERSION_NAME)) return@withContext Result.UpToDate
            install(context.applicationContext, release)
            Result.Installing(release.version)
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fetchLatestRelease(): Release? {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("GitHub returned HTTP ${response.code}.")
            val json = JSONObject(response.body?.string().orEmpty())
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") == APK_ASSET_NAME) {
                    return Release(json.getString("tag_name"), asset.getString("browser_download_url"))
                }
            }
            return null
        }
    }

    private fun install(context: Context, release: Release) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Silent once this app is the installer of record; otherwise Android asks once.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("APK download returned HTTP ${response.code}.")
                val body = response.body ?: throw IllegalStateException("APK download was empty.")
                session.openWrite("base.apk", 0, body.contentLength()).use { out ->
                    body.byteStream().copyTo(out)
                    session.fsync(out)
                }
            }
            val statusIntent = Intent(context, UpdateInstallReceiver::class.java)
                .putExtra(UpdateInstallReceiver.EXTRA_VERSION, release.version)
            // Mutable: the installer adds the status and confirmation intent as extras.
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
            session.close()
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }
}

/** True when [candidate] (e.g. "v1.10.0") is a higher dotted version than [current]. */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = parseVersion(candidate) ?: return false
    val b = parseVersion(current) ?: return true
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun parseVersion(value: String): List<Int>? {
    val core = value.trim().removePrefix("v").substringBefore('-')
    if (core.isEmpty()) return null
    return core.split('.').map { it.toIntOrNull() ?: return null }
}
