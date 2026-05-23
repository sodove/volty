package com.volty.app.domain.model

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Scanning : ConnectionState()
    data class Connecting(val vehicle: Vehicle?) : ConnectionState()
    data class Connected(val vehicle: Vehicle?) : ConnectionState()
    data object Disconnected : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}
