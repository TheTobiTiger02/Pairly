package dev.pairly.android.features.apps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.Base64
import dev.pairly.android.features.control.PairlyAccessibilityService
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lists the phone's launchable apps and opens one on request - Phone Link's "Apps" list.
 *
 * Only apps with a launcher entry are listed, which is also exactly what the manifest's
 * `<queries>` block makes visible; no QUERY_ALL_PACKAGES is needed.
 */
class AppCatalog(private val context: Context) {

    fun listPayload(requestId: String): JSONObject {
        val packageManager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                val label = info.loadLabel(packageManager).toString().ifBlank { info.packageName }
                Triple(info.packageName, label, info.loadIcon(packageManager))
            }
            .sortedBy { it.second.lowercase() }
            .toList()

        val array = JSONArray()
        apps.forEach { (packageName, label, icon) ->
            array.put(
                JSONObject()
                    .put("packageName", packageName)
                    .put("label", label)
                    .put("iconPngBase64", encodeIcon(icon) ?: JSONObject.NULL)
            )
        }
        return JSONObject().put("requestId", requestId).put("apps", array)
    }

    /**
     * Opens the app's launcher activity.
     *
     * Android silently drops activity starts from background apps - startActivity returns
     * normally and nothing appears. Two contexts are exempt: a bound accessibility service, and an
     * app holding the "display over other apps" grant. Without either, report the failure instead
     * of claiming a launch that never happened.
     */
    fun launch(requestId: String, packageName: String): JSONObject {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return result(requestId, "failed", "$packageName is not installed or has no launcher screen.")

        val launcher: Context = PairlyAccessibilityService.instance
            ?: context.takeIf { Settings.canDrawOverlays(it) }
            ?: return result(
                requestId,
                "failed",
                "Android does not allow opening apps from the background. Turn on the Pairly " +
                    "accessibility service (remote control) or \"Display over other apps\" on the phone."
            )
        return try {
            launcher.startActivity(intent)
            result(requestId, "launched")
        } catch (e: Exception) {
            result(requestId, "failed", "Android did not allow opening the app: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun result(requestId: String, status: String, error: String? = null) = JSONObject()
        .put("requestId", requestId)
        .put("status", status)
        .put("error", error ?: JSONObject.NULL)

    private fun encodeIcon(drawable: Drawable): String? = runCatching {
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also {
            drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
            drawable.draw(it)
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    private companion object {
        const val ICON_SIZE_PX = 64
    }
}
