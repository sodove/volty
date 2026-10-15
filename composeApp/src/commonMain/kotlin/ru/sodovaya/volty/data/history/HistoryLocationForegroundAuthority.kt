package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

internal class HistoryLocationForegroundTicket internal constructor(
    val recording: CapturedHistoryGpsRecording,
    val id: String,
    internal val onLost: (HistoryLocationForegroundTicket) -> Unit,
)
internal data class HistoryLocationForegroundState(
    val ticket: HistoryLocationForegroundTicket? = null,
    val service: Any? = null,
    val captureReady: Boolean = false,
)

/** Native caller must promote the actual location FGS BEFORE acknowledge.
 * One atomic snapshot fences GPS before original cleanup. GPS offer is nonblocking;
 * neither it nor onLost may join native work or perform IO while holding this lock.
 * Separate from Nearby: its tickets, service callbacks and shutdown cannot authorize history.
 */
internal class HistoryLocationForegroundAuthority(
    private val permission: () -> Boolean,
    private val nextId: () -> String,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(HistoryLocationForegroundState())
    val state = mutableState.asStateFlow()
    private var sequence = 0L

    fun request(recording: CapturedHistoryGpsRecording,
        onLost: (HistoryLocationForegroundTicket) -> Unit): HistoryLocationForegroundTicket = synchronized(lock) {
        val previous = mutableState.value.ticket
        if (previous != null && sameRecording(previous.recording, recording)) return@synchronized previous
        val uuid = nextId()
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(uuid))
        check(sequence < Long.MAX_VALUE) { "Location ticket generation exhausted" }
        val ticket = HistoryLocationForegroundTicket(recording, "$uuid/${++sequence}", onLost)
        // Publish all fields together BEFORE callbacks. A synchronous observer may replace
        // this pending ticket; nothing below is allowed to overwrite that newer selection.
        mutableState.value = HistoryLocationForegroundState(ticket)
        previous?.onLost?.invoke(previous)
        ticket
    }

    fun find(id: String): HistoryLocationForegroundTicket? = synchronized(lock) {
        mutableState.value.ticket?.takeIf { it.id == id }
    }

    fun acknowledge(ticket: HistoryLocationForegroundTicket, service: Any): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.ticket !== ticket || !permissionAvailable() ||
            current.service?.let { it !== service } == true) return@synchronized false
        mutableState.value = current.copy(service = service)
        // Publication may synchronously revoke or replace this acknowledgement.
        mutableState.value.let { it.ticket === ticket && it.service === service } && permissionAvailable()
    }

    /** Original demand owner calls this only AFTER successful native provider acquisition. */
    fun admitCapture(ticket: HistoryLocationForegroundTicket): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.ticket !== ticket || !allowsLocked(ticket.recording)) return@synchronized false
        mutableState.value = current.copy(captureReady = true)
        mutableState.value.ticket === ticket && canCaptureLocked(ticket.recording)
    }

    /** Fence writer intake before awaiting original provider cleanup; retain foreground proof. */
    fun fenceCapture(ticket: HistoryLocationForegroundTicket): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.ticket !== ticket) false
        else { mutableState.value = current.copy(captureReady = false); true }
    }

    fun canCapture(recording: CapturedHistoryGpsRecording): Boolean = synchronized(lock) { canCaptureLocked(recording) }

    fun allows(recording: CapturedHistoryGpsRecording): Boolean = synchronized(lock) { allowsLocked(recording) }

    fun offer(recording: CapturedHistoryGpsRecording, block: () -> HistoryOffer): HistoryOffer = synchronized(lock) {
        if (!canCaptureLocked(recording)) HistoryOffer.RETIRED else block()
    }

    fun stop(ticket: HistoryLocationForegroundTicket): Boolean = synchronized(lock) {
        if (mutableState.value.ticket !== ticket) false else { retire(ticket); true }
    }

    fun destroyed(service: Any): Boolean = synchronized(lock) {
        val current = mutableState.value
        val ticket = current.ticket
        if (ticket == null || current.service !== service) false else { retire(ticket); true }
    }

    fun refreshPermissions() = synchronized(lock) {
        if (!permissionAvailable()) mutableState.value.ticket?.let(::retire)
    }

    suspend fun awaitReady(ticket: HistoryLocationForegroundTicket, timeoutMillis: Long = 10_000): Boolean {
        require(timeoutMillis > 0)
        try {
            val ready = withTimeoutOrNull(timeoutMillis) {
                state.map { snapshot ->
                    when {
                        snapshot.ticket !== ticket -> false
                        snapshot.service != null -> allows(ticket.recording)
                        else -> null
                    }
                }.filterNotNull().first()
            } ?: false
            currentCoroutineContext().ensureActive()
            if (!ready) stop(ticket)
            return ready
        } catch (cancel: CancellationException) { stop(ticket); throw cancel }
    }

    private fun retire(ticket: HistoryLocationForegroundTicket) {
        mutableState.value = HistoryLocationForegroundState()
        ticket.onLost(ticket)
    }

    private fun canCaptureLocked(recording: CapturedHistoryGpsRecording): Boolean =
        mutableState.value.captureReady && allowsLocked(recording)

    private fun allowsLocked(recording: CapturedHistoryGpsRecording): Boolean {
        val current = mutableState.value
        return current.service != null && current.ticket?.let { sameRecording(it.recording, recording) } == true &&
            permissionAvailable()
    }

    private fun sameRecording(a: CapturedHistoryGpsRecording, b: CapturedHistoryGpsRecording): Boolean =
        a.identity === b.identity && a.generation == b.generation && a.rideId == b.rideId

    private fun permissionAvailable(): Boolean = try { permission() } catch (_: Exception) { false }
}
