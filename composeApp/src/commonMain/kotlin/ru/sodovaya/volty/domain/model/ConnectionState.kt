package ru.sodovaya.volty.domain.model

sealed class ConnectionState {
    /** A poll write fault belongs to one BLE address, never the whole vehicle. */
    data class LinkWriteFailure(
        val address: String,
        val consecutiveFailures: Int,
        val lastFailure: String
    )

    /** A link is notifying, but its plain VESC decoder has not recognised a reply. */
    data class LinkNotUnderstood(val address: String)

    /** One sibling is still live while this address is being re-established. */
    data class LinkReconnecting(
        val address: String,
        val attempt: Int,
        val reason: String
    )

    data object Idle : ConnectionState()
    data object Scanning : ConnectionState()
    data class Connecting(val vehicle: Vehicle?) : ConnectionState()
    /**
     * A live BLE connection. [linkWriteFailures] reports only links whose poll
     * command is not reaching the wire; it is deliberately distinct from a
     * quiet device, which accepted writes but has not replied yet. [linkNotUnderstood]
     * names plain VESC links that are notifying but have not produced any
     * recognisable reply, so reconnecting them would only churn a healthy GATT link.
     * [linkReconnecting] keeps a controller sibling's retry status and reason
     * visible even though another online link keeps the vehicle-level fold Connected.
     */
    data class Connected(
        val vehicle: Vehicle?,
        val linkWriteFailures: List<LinkWriteFailure> = emptyList(),
        val linkNotUnderstood: List<LinkNotUnderstood> = emptyList(),
        val linkReconnecting: List<LinkReconnecting> = emptyList()
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
