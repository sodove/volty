package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.data.ble.HistoryTelemetryIngress
import ru.sodovaya.volty.domain.location.RideLocationState

/** App-owned explicit lifecycle; screens do not own this supervisor. prepare() must
 * complete before installing ingress in the repository's sealed recorder choice.
 * The app owner must close it; failed shutdown retains the original resources for retry.
 */
internal class HistoryRecordingRuntime(
    private val clock: HistoryRecordingClock,
    private val dispatcher: CoroutineDispatcher,
    private val createWriter: () -> HistoryWriter,
    private val locations: StateFlow<RideLocationState>,
    private val foreground: HistoryLocationForegroundAuthority,
    private val setServiceEnabled: (Boolean) -> Unit,
    private val createLocationSession: (CoroutineScope, CapturedHistoryGpsRecording) -> HistoryRecordingLocationSession,
    private val newId: () -> String,
    private val retryOpportunities: Flow<Unit> = emptyFlow(),
) {
    internal class Binding(val controller: RideRecordingController, val ingress: HistoryTelemetryIngress,
        val locations: Flow<HistoryRecordingLocationSnapshot?>,
        val writerDiagnostics: StateFlow<HistoryWriterDiagnostics>)
    internal data class Status(val binding: Binding? = null, val closing: Boolean = false)
    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()
    private class Owned(val binding: Binding, val writer: HistoryWriter,
        val demand: HistoryRecordingGpsDemand, val scope: CoroutineScope,
        val gps: HistoryGpsRecorder, val retries: Job)
    private val controls = Mutex()
    @Volatile private var closing = false
    private var owned: Owned? = null
    private var pendingWriter: HistoryWriter? = null
    private val mutableOpportunityFailure = MutableStateFlow<String?>(null)
    val opportunityFailure = mutableOpportunityFailure.asStateFlow()

    suspend fun prepare(): Binding {
        currentCoroutineContext().ensureActive()
        return controls.withLock {
            currentCoroutineContext().ensureActive()
            check(!closing) { "Recording runtime is closing" }
            owned?.let { return@withLock it.binding }
            // Recovery is bootstrap-only. No writer actor exists until this ACK.
            clock.prepare()
            currentCoroutineContext().ensureActive()
            check(!closing) { "Recording runtime closed during recovery" }
            withContext(NonCancellable) {
                val writer = createWriter()
                pendingWriter = writer // Reserve before any further assembly or close reentry.
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                try {
                    lateinit var controller: RideRecordingController
                    val demand = HistoryRecordingGpsDemand({ controller.captureGpsRecording() }, setServiceEnabled,
                        { createLocationSession(scope, it) })
                    // clock.prepare already recovered interrupted rows, before writer construction.
                    controller = RideRecordingController(writer, demand, newId, { 0 })
                    val binding = Binding(controller,
                        HistoryTelemetryIngress(controller, clock::readTick, manageConnections = true),
                        demand.locationStates, writer.diagnostics)
                    val gps = HistoryGpsRecorder(scope, controller, locations, foreground, clock::readTick)
                    val retries = scope.launch {
                        try {
                            retryOpportunities.collect { if (!closing) controller.retryDemand() }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { mutableOpportunityFailure.value = error::class.simpleName ?: "OpportunityFailure" }
                    }
                    check(!closing) { "Recording runtime closed during assembly" }
                    owned = Owned(binding, writer, demand, scope, gps, retries)
                    pendingWriter = null
                    // Publication is observation only. Closing is a tombstone:
                    // a delayed prepare may not put UI controls back afterwards.
                    mutableStatus.update { if (it.closing) it else it.copy(binding = binding) }
                    binding
                } catch (error: Exception) {
                    scope.cancel(); scope.coroutineContext[Job]?.join()
                    try { writer.close(); pendingWriter = null }
                    catch (cleanup: Exception) {
                        // Failed rollback still owns this original actor; a later close
                        // must retry it before the runtime can release its last reference.
                        closing = true
                        if (cleanup !== error) error.addSuppressed(cleanup)
                    }
                    throw error
                }
            }
        }
    }

    suspend fun close() {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            mutableStatus.update { it.copy(binding = null, closing = true) }
            closing = true
            controls.withLock {
                val current = owned
                if (current == null) {
                    pendingWriter?.let { it.close(); pendingWriter = null }
                    return@withLock
                }
                current.retries.cancelAndJoin()
                current.gps.closeAndJoin()
                // This fences new native work and drains original accepted receipts.
                // Durable failure propagates; neither writer nor native owner is discarded.
                current.binding.ingress.closeNative()
                // Controller diagnostics deliberately absorb optional GPS errors. Runtime
                // disposal instead requires the actual original native cleanup ACK.
                current.demand.setEnabled(false)
                current.scope.cancel(); current.scope.coroutineContext[Job]?.join()
                current.writer.close()
                owned = null
            }
        }
        currentCoroutineContext().ensureActive()
    }
}
