package dev.smartphonelink.android.network

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

enum class ConnectionPhase {
    Disconnected,
    Discovering,
    Connecting,
    Authenticating,
    Ready,
    Reconnecting,
    Rejected
}

data class ConnectionState(
    val phase: ConnectionPhase,
    val detail: String,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val isReady: Boolean get() = phase == ConnectionPhase.Ready
}

object PhoneLinkConnection {
    val client: LanClient = LanClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArraySet<(ConnectionState) -> Unit>()

    @Volatile
    var host: String = ""

    @Volatile
    var deviceId: String = ""

    @Volatile
    var isConnected: Boolean = false

    @Volatile
    var isTransportConnected: Boolean = false

    val isReady: Boolean
        get() = isConnected && connectionState.phase == ConnectionPhase.Ready

    @Volatile
    var status: String = "Disconnected"
        private set

    @Volatile
    var connectionState: ConnectionState = ConnectionState(ConnectionPhase.Disconnected, "Disconnected")
        private set

    fun send(json: String): Boolean = client.send(json)

    fun updateStatus(value: String) {
        status = value
        mainHandler.post {
            statusListeners.forEach { listener -> listener(value) }
        }
    }

    fun updateState(phase: ConnectionPhase, detail: String) {
        val state = ConnectionState(phase, detail)
        connectionState = state
        status = detail
        mainHandler.post {
            stateListeners.forEach { listener -> listener(state) }
            statusListeners.forEach { listener -> listener(detail) }
        }
    }

    fun addStatusListener(listener: (String) -> Unit): () -> Unit {
        statusListeners.add(listener)
        listener(status)
        return { statusListeners.remove(listener) }
    }

    fun addStateListener(listener: (ConnectionState) -> Unit): () -> Unit {
        stateListeners.add(listener)
        listener(connectionState)
        return { stateListeners.remove(listener) }
    }
}
