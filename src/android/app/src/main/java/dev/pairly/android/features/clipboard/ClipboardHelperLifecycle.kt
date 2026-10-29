package dev.pairly.android.features.clipboard

/** Rejects late bind callbacks after stop, permission loss, or a helper restart. */
class ClipboardHelperLifecycle {
    enum class State { NotInstalled, NotRunning, NotAllowed, Connecting, Ready, Error, Paused }
    var wanted = false
        private set
    var generation = 0L
        private set
    var state = State.NotRunning
        private set
    var bound = false
        private set

    fun start() { wanted = true }
    fun stop() { wanted = false; invalidate(); state = State.Paused }
    fun availability(installed: Boolean, running: Boolean, allowed: Boolean): Boolean {
        val unavailable = when {
            !installed -> State.NotInstalled
            !running -> State.NotRunning
            !allowed -> State.NotAllowed
            !wanted -> State.Paused
            else -> null
        }
        if (unavailable != null) { invalidate(); state = unavailable; return false }
        return true
    }
    fun binding(): Long { generation++; bound = true; state = State.Connecting; return generation }
    fun current(generation: Long): Boolean = wanted && bound && generation == this.generation
    fun healthy(generation: Long): Boolean {
        if (!current(generation)) return false
        state = State.Ready
        return true
    }
    fun failed(generation: Long): Boolean {
        if (!current(generation)) return false
        invalidate(); state = State.Error
        return true
    }
    private fun invalidate() { generation++; bound = false }
}
