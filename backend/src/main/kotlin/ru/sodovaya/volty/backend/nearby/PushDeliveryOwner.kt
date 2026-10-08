package ru.sodovaya.volty.backend.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One bounded attempt; true means queue work, never proof of handset delivery. */
fun interface PushDeliveryTick { fun tick(): Boolean }
internal data class PushDeliveryState(val wanted: Boolean = true, val producerPending: Boolean = true,
                                      val inFlight: Int = 0, val failures: Long = 0)

/** Separate lane; interrupt blocking transport, and count actual producer completion. */
internal class PushDeliveryOwner private constructor(parent: CoroutineScope, pulses: Flow<Unit>,
                                                     work: PushDeliveryTick, dispatcher: CoroutineDispatcher) {
    private val root = checkNotNull(parent.coroutineContext[Job])
    private val lifetime = SupervisorJob(root)
    private val scope = CoroutineScope(parent.coroutineContext + lifetime + dispatcher)
    private val mutableState = MutableStateFlow(PushDeliveryState())
    val state = mutableState.asStateFlow()
    private val mutableStopped = MutableStateFlow(false)
    val stopped = mutableStopped.asStateFlow()
    private var guardian: Job? = null
    private val runner = scope.launch(start = CoroutineStart.LAZY) {
        pulses.collect {
            currentCoroutineContext().ensureActive()
            if (!root.isActive || !mutableState.value.wanted) throw CancellationException("Push delivery stopped")
            mutableState.update { it.copy(inFlight = it.inFlight + 1) }
            try { runInterruptible { work.tick() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(failures = if (it.failures == Long.MAX_VALUE) it.failures else it.failures + 1) }
            } finally { mutableState.update { it.copy(inFlight = it.inFlight - 1) } }
        }
    }
    init {
        guardian = parent.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { requestStop() }
        }
        runner.invokeOnCompletion {
            mutableState.update { it.copy(wanted = false, producerPending = false) }
            mutableStopped.value = true
            guardian?.cancel(); lifetime.complete()
        }
        runner.start()
    }
    private fun requestStop() { mutableState.update { it.copy(wanted = false) }; lifetime.cancel() }
    fun close(): Boolean { requestStop(); return runner.isCompleted }
    companion object {
        fun start(parent: CoroutineScope, pulses: Flow<Unit>, work: PushDeliveryTick,
                  dispatcher: CoroutineDispatcher = Dispatchers.IO): PushDeliveryOwner {
            check(checkNotNull(parent.coroutineContext[Job]).isActive) { "Actual application root required" }
            return PushDeliveryOwner(parent, pulses, work, dispatcher)
        }
    }
}
