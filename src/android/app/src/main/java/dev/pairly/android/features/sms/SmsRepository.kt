package dev.pairly.android.features.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import android.os.Build
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

    /** The newest messages of one conversation, for when the PC opens it. */
    fun threadMessages(threadId: String, limit: Int = 100): List<JSONObject> {
        val id = threadId.toLongOrNull() ?: return emptyList()
        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.DATE),
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(id.toString()),
                "${Telephony.Sms.DATE} DESC"
            )
        } catch (_: RuntimeException) {
            null
        } ?: return emptyList()
        return cursor.use {
            val messages = mutableListOf<JSONObject>()
            while (it.moveToNext() && messages.size < limit) {
                messages += JSONObject()
                    .put("messageId", it.getLong(0).toString())
                    .put("threadId", threadId)
                    .put("address", it.getString(1).orEmpty())
                    .put("body", it.getString(2).orEmpty())
                    .put("fromMe", it.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT)
                    .put("sentAt", Instant.ofEpochMilli(it.getLong(4)).toString())
            }
            messages
        }
    }

    /**
     * The most recently active conversations: the newest message of each, the contact's name when
     * the number is in the address book (and Pairly may read contacts), and how many are unread.
     * Walks the messages newest first and stops once it has [limit] conversations.
     */
    fun recentThreads(limit: Int = 200): List<JSONObject> {
        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null, null, "${Telephony.Sms.DATE} DESC"
            )
        } catch (_: RuntimeException) {
            null
        } ?: return emptyList()
        val unread = unreadByThread()
        val names = HashMap<String, String>()
        val threads = LinkedHashMap<Long, JSONObject>()
        cursor.use {
            while (it.moveToNext() && threads.size < limit) {
                val threadId = it.getLong(0)
                if (threads.containsKey(threadId)) continue
                val address = it.getString(1).orEmpty()
                threads[threadId] = JSONObject()
                    .put("threadId", threadId.toString())
                    .put("displayName", names.getOrPut(address) { contactName(address) ?: address })
                    .put("address", address)
                    .put("snippet", it.getString(2).orEmpty())
                    .put("updatedAt", Instant.ofEpochMilli(it.getLong(3)).toString())
                    .put("unreadCount", unread[threadId] ?: 0)
            }
        }
        return threads.values.toList()
    }

    private fun unreadByThread(): Map<Long, Int> {
        val cursor = try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID),
                "${Telephony.Sms.READ} = 0", null, null
            )
        } catch (_: RuntimeException) {
            null
        } ?: return emptyMap()
        val counts = HashMap<Long, Int>()
        cursor.use { while (it.moveToNext()) counts.merge(it.getLong(0), 1, Int::plus) }
        return counts
    }

    private fun contactName(address: String): String? {
        if (address.isBlank() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null }
        } catch (_: RuntimeException) {
            null
        }
    }

    fun sendText(address: String, body: String): Boolean {
        if (address.isBlank() || body.isBlank()) {
            return false
        }

        return try {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            // Longer than one SMS: send it as parts the receiving phone joins again.
            val parts = sms.divideMessage(body)
            if (parts.size > 1) sms.sendMultipartTextMessage(address, null, parts, null, null)
            else sms.sendTextMessage(address, null, body, null, null)
            true
        } catch (_: RuntimeException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
