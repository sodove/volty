package ru.sodovaya.volty.domain.model

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Scanning : ConnectionState()
    data class Connecting(val vehicle: Vehicle?) : ConnectionState()
    /**
     * A live BLE connection. [consecutivePollWriteFailures] says the poll
     * command itself is not reaching the wire; it is deliberately distinct
     * from a quiet device, which accepted writes but has not replied yet.
     */
    data class Connected(
        val vehicle: Vehicle?,
        val consecutivePollWriteFailures: Int = 0,
        val lastPollWriteFailure: String? = null
    ) : ConnectionState()
    data object Disconnected : ConnectionState()

    /**
     * Transient state emitted by the BLE reconnect loop between attempts.
     *
     * Distinct from [Failed] so the UI can tell "we're still trying" from
     * "we gave up". Promoted from the legacy `Failed("Reconnecting…")`
     * abuse — see [ru.sodovaya.volty.data.ble.KableBmsRepository].
     */
    data class Reconnecting(val attempt: Int, val reason: String) : ConnectionState()

    /** Permanent failure. The repo has stopped trying. */
    data class Failed(val reason: String) : ConnectionState()
}
