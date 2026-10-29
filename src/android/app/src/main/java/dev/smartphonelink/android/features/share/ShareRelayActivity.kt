package dev.smartphonelink.android.features.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import dev.smartphonelink.android.R
import dev.smartphonelink.android.features.clipboard.ClipboardImagePreparer
import dev.smartphonelink.android.features.clipboard.ClipboardSyncController
import dev.smartphonelink.android.network.ConnectionSettings
import dev.smartphonelink.android.network.PhoneLinkConnectionService

class ShareRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = ConnectionSettings(this)
        if (settings.trustKey.isBlank()) {
            finishWithToast(R.string.share_pair_first)
            return
        }

        val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        when {
            intent.type?.startsWith("image/") == true && stream != null -> relayImage(stream)
            !intent.getCharSequenceExtra(Intent.EXTRA_TEXT).isNullOrBlank() -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT).toString()
                val queued = ClipboardSyncController.sendPhoneClipboard(this, text)
                finishWithToast(if (queued) R.string.share_text_queued else R.string.share_not_relayed)
            }
            else -> finishWithToast(R.string.share_unsupported)
        }
    }

    private fun relayImage(uri: Uri) {
        Thread({
            val prepared = ClipboardImagePreparer.prepare(this, uri)
            runOnUiThread {
                prepared.onSuccess {
                    PhoneLinkConnectionService.sendClipboardImage(this, it.file.absolutePath, it.fileName)
                    finishWithToast(R.string.share_image_queued)
                }.onFailure {
                    Toast.makeText(this, it.message ?: getString(R.string.share_not_relayed), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }, "ClipboardImagePrepare").start()
    }

    private fun finishWithToast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
