package dev.pairly.android.features.sms

import android.content.Context
import android.telephony.SmsManager
import android.provider.Telephony
import org.json.JSONObject
import java.time.Instant

class SmsRepository(private val context: Context) {
    fun recentMessages(limit: Int = 50): List<JSONObject> {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE
        )

        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )
        } catch (_: RuntimeException) {
            null
        }

        cursor?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadId = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val messages = mutableListOf<JSONObject>()
            while (cursor.moveToNext() && messages.size < limit) {
                messages += JSONObject()
                    .put("messageId", cursor.getLong(id).toString())
                    .put("threadId", cursor.getLong(threadId).toString())
                    .put("address", cursor.getString(address).orEmpty())
                    .put("body", cursor.getString(body).orEmpty())
                    .put("fromMe", cursor.getInt(type) == Telephony.Sms.MESSAGE_TYPE_SENT)
                    .put("sentAt", Instant.ofEpochMilli(cursor.getLong(date)).toString())
            }
            return messages
        }

        return emptyList()
    }

    fun groupRecentMessagesByThread(limit: Int = 50): Map<String, List<JSONObject>> {
        return recentMessages(limit).groupBy { it.optString("threadId") }
    }

    fun recentThreads(limit: Int = 50): List<JSONObject> {
        return groupRecentMessagesByThread(limit).map { (threadId, messages) ->
            val latest = messages.maxByOrNull { it.optString("sentAt") }
            JSONObject()
                .put("threadId", threadId)
                .put("displayName", latest?.optString("address").orEmpty())
                .put("address", latest?.optString("address").orEmpty())
                .put("snippet", latest?.optString("body").orEmpty())
                .put("updatedAt", latest?.optString("sentAt").orEmpty())
                .put("unreadCount", 0)
        }
    }

    fun sendText(address: String, body: String): Boolean {
        if (address.isBlank() || body.isBlank()) {
            return false
        }

        return try {
            SmsManager.getDefault().sendTextMessage(address, null, body, null, null)
            true
        } catch (_: RuntimeException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
