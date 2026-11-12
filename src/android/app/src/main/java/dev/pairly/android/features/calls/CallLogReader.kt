package dev.pairly.android.features.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** The phone's call history for the PC's Calls page, and calls the PC asks the phone to place. */
object CallLogReader {
    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The newest calls first. `error` is "permission" when Pairly may not read the call log. */
    fun response(context: Context, requestId: String, limit: Int = 100): JSONObject {
        val result = JSONObject().put("requestId", requestId)
        if (!granted(context, Manifest.permission.READ_CALL_LOG)) {
            return result.put("entries", JSONArray()).put("error", "permission")
        }
        val entries = JSONArray()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && entries.length() < limit) {
                    entries.put(
                        JSONObject()
                            .put("number", cursor.getString(0).orEmpty())
                            .put("name", cursor.getString(1)?.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                            .put("kind", kind(cursor.getInt(2)))
                            .put("startedAt", Instant.ofEpochMilli(cursor.getLong(3)).toString())
                            .put("durationSeconds", cursor.getLong(4))
                    )
                }
            }
        } catch (failure: RuntimeException) {
            return result.put("entries", JSONArray()).put("error", failure.message ?: "The call log could not be read.")
        }
        return result.put("entries", entries)
    }

    private fun kind(type: Int): String = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "missed"
        else -> "incoming"
    }

    /**
     * Places the call on the phone, as if dialled there; the call and its sound stay on the phone.
     * Returns null on success, otherwise what went wrong.
     */
    fun dial(context: Context, number: String): String? {
        val cleaned = number.filter { it.isDigit() || it in "+*#" }
        if (cleaned.isEmpty()) return "That is not a phone number."
        if (!granted(context, Manifest.permission.CALL_PHONE)) return "permission"
        return try {
            context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", cleaned, null), Bundle())
            null
        } catch (failure: RuntimeException) {
            failure.message ?: "The call could not be started."
        }
    }
}
