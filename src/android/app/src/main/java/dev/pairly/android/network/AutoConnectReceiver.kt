package dev.pairly.android.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AutoConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ConnectionWatchdog.ACTION_WATCHDOG
        ) {
            return
        }

        val settings = ConnectionSettings(context)
        if (!settings.autoConnectEnabled || settings.trustKey.isBlank() || settings.savedPairingRejected) {
            ConnectionWatchdog.cancel(context)
            return
        }

        // Always re-arm: setAndAllowWhileIdle is one-shot, so the next tick only exists if this
        // one schedules it.
        ConnectionWatchdog.schedule(context)

        if (action == ConnectionWatchdog.ACTION_WATCHDOG && PairlyConnection.isConnected) {
            return
        }

        runCatching { PairlyConnectionService.startConnect(context) }
            .onFailure { Log.w("PairlyBoot", "Automatic reconnect could not start yet.", it) }
    }
}
