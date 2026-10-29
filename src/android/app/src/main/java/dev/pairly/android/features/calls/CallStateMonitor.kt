package dev.pairly.android.features.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.pairly.android.features.notifications.LastNotificationRelay
import dev.pairly.android.network.PairlyConnectionService
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Relays incoming-call state to the PC.
 *
 * This replaces a manifest [android.content.BroadcastReceiver] for ACTION_PHONE_STATE_CHANGED:
 * that is a restricted implicit broadcast, and One UI in particular does not deliver it reliably
 * to a background app. A listener registered from the running foreground service does.
 *
 * Caller number: available on API 26-30 through the deprecated [PhoneStateListener] overload when
 * READ_CALL_LOG is granted. On API 31+ the platform removed the number from the callback entirely,
 * so the caller is read from the dialer's call notification instead (needs notification access).
 */
class CallStateMonitor(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PairlyCallState")
    }

    private var telephonyCallback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null
    private var lastState: Int = TelephonyManager.CALL_STATE_IDLE
    private var sessionId: String = UUID.randomUUID().toString()

    fun start() {
        if (!hasPhoneStateAccess()) {
            Log.i(TAG, "READ_PHONE_STATE not granted; incoming calls will not reach the PC.")
            return
        }

        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleState(state, number = "")
                }
                telephonyCallback = callback
                telephony.registerTelephonyCallback(executor, callback)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Replaced by TelephonyCallback on API 31+")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleState(state, phoneNumber.orEmpty())
                    }
                }
                legacyListener = listener
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        }.onFailure { Log.w(TAG, "Could not register the call state listener.", it) }
    }

    fun stop() {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let { telephony?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let { telephony?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        }
        telephonyCallback = null
        legacyListener = null
        executor.shutdownNow()
    }

    private fun handleState(state: Int, number: String) {
        if (state == lastState) return
        val previous = lastState
        lastState = state

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                sessionId = UUID.randomUUID().toString()
                PairlyConnectionService.sendCallRinging(sessionId, number.ifBlank { waitForCallerFromNotification() })
            }
            // Answered (after ringing) or dialled from the phone: either way a call is in progress.
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (previous == TelephonyManager.CALL_STATE_IDLE) sessionId = UUID.randomUUID().toString()
                PairlyConnectionService.sendCallActive(sessionId, number.ifBlank { LastNotificationRelay.activeCallTitle().orEmpty() })
            }
            TelephonyManager.CALL_STATE_IDLE -> PairlyConnectionService.sendCallIdle()
        }
    }

    /** The dialer posts its notification a moment after the state change; give it a second. */
    private fun waitForCallerFromNotification(): String {
        repeat(CALLER_LOOKUP_ATTEMPTS) {
            LastNotificationRelay.activeCallTitle()?.let { return it }
            Thread.sleep(CALLER_LOOKUP_INTERVAL_MS)
        }
        return ""
    }

    private fun hasPhoneStateAccess(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "PairlyCallState"
        const val CALLER_LOOKUP_ATTEMPTS = 5
        const val CALLER_LOOKUP_INTERVAL_MS = 200L
    }
}
