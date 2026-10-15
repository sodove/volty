package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.model.ConnectionState

/** Independent desired BLE/recording retention. This is not native foreground readiness.
 * Every enable retries platform start; the OS may have destroyed a previously requested service.
 * The original requester must release its demand even if platform submission throws.
 */
internal class HistoryServiceOwnership(
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
) {
    private val lock = Any()
    private var ble = false
    private var recording = false
    val required: Boolean get() = synchronized(lock) { ble || recording }

    fun setBleEnabled(enabled: Boolean) = synchronized(lock) {
        ble = enabled
        reconcile(enabled)
    }

    fun setRecordingEnabled(enabled: Boolean) = synchronized(lock) {
        recording = enabled
        reconcile(enabled)
    }

    fun shouldKeepService(connection: ConnectionState): Boolean = synchronized(lock) {
        // Retraction: checking the union demand would retain orphan BLE-only services.
        // Only recording overrides the existing disconnected/failed startup guard.
        recording || when (connection) {
            ConnectionState.Idle, ConnectionState.Disconnected, is ConnectionState.Failed -> false
            else -> true
        }
    }

    private fun reconcile(enabled: Boolean) {
        // Keep submission order under the demand lock; never join native work in these callbacks.
        if (enabled) startService()
        else if (!ble && !recording) stopService()
    }
}
