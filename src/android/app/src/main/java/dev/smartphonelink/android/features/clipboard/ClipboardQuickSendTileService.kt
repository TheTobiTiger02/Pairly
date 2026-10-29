package dev.smartphonelink.android.features.clipboard

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.smartphonelink.android.MainActivity
import dev.smartphonelink.android.R
import dev.smartphonelink.android.network.ConnectionSettings

class ClipboardQuickSendTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val paired = ConnectionSettings(this).trustKey.isNotBlank()
        qsTile?.apply {
            state = if (paired) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_clipboard_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(if (paired) R.string.tile_clipboard_ready else R.string.tile_clipboard_pair_first)
            }
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val target = if (ConnectionSettings(this).trustKey.isNotBlank()) {
            Intent(this, ClipboardRelayActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                41,
                target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(target)
        }
    }
}
