package dev.pairly.android.features.clipboard

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.pairly.android.R
import dev.pairly.android.network.PairlyConnectionService

class ClipboardRelayActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var attemptedRelay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed({
            if (!attemptedRelay) {
                relayAndFinish()
            }
        }, 250L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !attemptedRelay) {
            relayAndFinish()
        }
    }

    private fun relayAndFinish() {
        attemptedRelay = true
        val imageUri = ClipboardRelay(this).readFocusedImageUri()
        if (imageUri != null) {
            Thread({
                val prepared = ClipboardImagePreparer.prepare(this, imageUri)
                runOnUiThread {
                    prepared.onSuccess {
                        PairlyConnectionService.sendClipboardImage(this, it.file.absolutePath, it.fileName)
                        Toast.makeText(this, R.string.share_image_queued, Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this, it.message ?: getString(R.string.clipboard_unavailable), Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }, "ClipboardTileImagePrepare").start()
            return
        }
        val sent = ClipboardSyncController.syncCurrentClipboard(
            this,
            unavailableStatus = "Phone clipboard sync needs app focus."
        )
        Toast.makeText(
            this,
            if (sent) {
                getString(R.string.clipboard_queued_all_devices)
            } else {
                getString(R.string.clipboard_unavailable)
            },
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
