package dev.pairly.android.features.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** The phone's contacts that have a number, so the PC can offer them when writing a new message. */
object ContactsReader {
    private const val LIMIT = 5000

    /** `error` is "permission" when Pairly may not read contacts. */
    fun response(context: Context, requestId: String): JSONObject {
        val result = JSONObject().put("requestId", requestId)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return result.put("contacts", JSONArray()).put("error", "permission")
        }

        val contacts = JSONArray()
        // The same number is often stored once per account (phone, Google, WhatsApp).
        val seen = HashSet<String>()
        try {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
                null, null, "${Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && contacts.length() < LIMIT) {
                    val name = cursor.getString(0).orEmpty()
                    val number = cursor.getString(1).orEmpty().trim()
                    if (number.isEmpty() || !seen.add(name + "|" + number.filter { it.isDigit() || it == '+' })) continue
                    val label = Phone.getTypeLabel(context.resources, cursor.getInt(2), cursor.getString(3))?.toString()
                    contacts.put(
                        JSONObject()
                            .put("name", name.ifBlank { number })
                            .put("number", number)
                            .put("label", label?.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                    )
                }
            }
        } catch (failure: RuntimeException) {
            return result.put("contacts", JSONArray()).put("error", failure.message ?: "The contacts could not be read.")
        }
        return result.put("contacts", contacts)
    }
}
