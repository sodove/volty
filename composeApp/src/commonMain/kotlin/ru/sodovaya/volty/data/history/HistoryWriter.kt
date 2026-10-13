package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.history.*

interface HistoryWriter : HistoryIngress {
    val diagnostics: StateFlow<HistoryWriterDiagnostics>
    suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>)
    suspend fun flush()
    suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String? = null)
    suspend fun delete(rideId: String)
    suspend fun close()
}

/** One persistence owner, independent of screens; capacity includes the packet awaiting its commit. */
class BufferedHistoryWriter(
    private val store: HistoryStore,
    dispatcher: CoroutineDispatcher,
    ticks: Flow<Unit> = historyFlushTicks(),
) : HistoryWriter {
    private data class Session(val generation: Long, val ride: HistoryRide)
    private data class Packet(val batch: HistoryBatch, val events: Long, val bytes: Long)
    private data class State(
        val session: Session? = null,
        val accepting: Boolean = false,
        val closed: Boolean = false,
        val pending: List<Packet> = emptyList(),
        val events: Long = 0,
        val bytes: Long = 0,
        val committed: Long = 0,
        val dropped: Long = 0,
        val loss: HistoryMarker? = null,
        val nextToken: Long = 0,
        val timer: Boolean = false,
        val failure: String? = null,
    )
    private sealed interface Command {
        val receipt: CompletableDeferred<Unit>
        data class Begin(val generation: Long, val ride: HistoryRide, val sources: List<HistorySource>, override val receipt: CompletableDeferred<Unit>) : Command
        data class Flush(override val receipt: CompletableDeferred<Unit>) : Command
        data class Finish(val tick: HistoryTick, val status: HistoryRideStatus, override val receipt: CompletableDeferred<Unit>) : Command
        data class Close(override val receipt: CompletableDeferred<Unit>) : Command
        data class Delete(val rideId: String, override val receipt: CompletableDeferred<Unit>) : Command
    }
    private val state = MutableStateFlow(State())
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + dispatcher)
    private val controls = Channel<Command>(Channel.UNLIMITED)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val controlLock = Mutex()
    override val diagnostics: StateFlow<HistoryWriterDiagnostics> = state.map {
        HistoryWriterDiagnostics(it.events, it.bytes, it.committed, it.dropped, it.failure, it.accepting, it.closed)
    }.stateIn(scope, SharingStarted.Eagerly, HistoryWriterDiagnostics())

    init {
        scope.launch {
            ticks.collect {
                state.update { current -> if (current.failure == null && !current.closed) current.copy(timer = true) else current }
                wake.trySend(Unit)
            }
        }
        scope.launch {
            while (isActive) {
                select<Unit> {
                    controls.onReceive { command -> handle(command) }
                    wake.onReceive {
                        val current = state.value
                        if (current.failure == null && (current.timer || current.events >= FLUSH_EVENTS)) {
                            state.update { it.copy(timer = false) }
                            try { drain() } catch (error: Exception) { failed(error) }
                        }
                    }
                }
            }
        }
    }

    override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer {
        val events = batch.eventCount
        val bytes = batch.retainedBytes()
        while (true) {
            val old = state.value
            val session = old.session
            if (!old.accepting || session == null || session.generation != generation || session.ride.id != batch.rideId) return HistoryOffer.RETIRED
            if (events == 0L) return HistoryOffer.ACCEPTED
            if (events > MAX_EVENTS - old.events || bytes > MAX_BYTES - old.bytes) {
                val first = sequenceOf(batch.samples.asSequence().map { it.tick }, batch.gps.asSequence().map { it.tick },
                    batch.markers.asSequence().map { it.tick }).flatten().minByOrNull { it.elapsedMillis }
                val updated = old.copy(dropped = old.dropped + events,
                    loss = old.loss ?: first?.let { HistoryMarker(it, HistoryMarkerKind.OVERFLOW) })
                if (state.compareAndSet(old, updated)) { wake.trySend(Unit); return HistoryOffer.OVERFLOW }
                continue
            }
            val copied = batch.copy(samples = batch.samples.toList(), gps = batch.gps.toList(), markers = batch.markers.toList(),
                receiptId = token(session, old.nextToken))
            val updated = old.copy(pending = old.pending + Packet(copied, events, bytes), events = old.events + events,
                bytes = old.bytes + bytes, nextToken = old.nextToken + 1)
            if (state.compareAndSet(old, updated)) { wake.trySend(Unit); return HistoryOffer.ACCEPTED }
        }
    }

    override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) = controlLock.withLock {
        check(!state.value.closed && state.value.session == null) { "Writer already owns a ride or is closed" }
        val receipt = CompletableDeferred<Unit>()
        controls.send(Command.Begin(generation, ride, sources.toList(), receipt)); receipt.await()
    }
    override suspend fun flush() = controlLock.withLock {
        check(!state.value.closed) { "Writer is closed" }
        val receipt = CompletableDeferred<Unit>()
        controls.send(Command.Flush(receipt)); receipt.await()
    }
    override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) = controlLock.withLock {
        if (state.value.session?.generation != generation) return@withLock
        if (rideId != null && state.value.session?.ride?.id != rideId) return@withLock
        state.update { it.copy(accepting = false) }
        val receipt = CompletableDeferred<Unit>()
        controls.send(Command.Finish(tick, status, receipt)); receipt.await()
    }
    override suspend fun close() = controlLock.withLock {
        if (state.value.closed) return@withLock
        state.update { it.copy(accepting = false) }
        val receipt = CompletableDeferred<Unit>()
        controls.send(Command.Close(receipt)); receipt.await()
        owner.cancel()
    }
    override suspend fun delete(rideId: String) = controlLock.withLock {
        check(!state.value.closed) { "Writer is closed" }
        state.update { if (it.session?.ride?.id == rideId) it.copy(accepting = false) else it }
        val receipt = CompletableDeferred<Unit>()
        controls.send(Command.Delete(rideId, receipt)); receipt.await()
    }

    private suspend fun handle(command: Command) {
        try {
            when (command) {
                is Command.Begin -> {
                    store.begin(command.ride, command.sources)
                    state.update { it.copy(session = Session(command.generation, command.ride), accepting = true, failure = null) }
                }
                is Command.Flush -> drain()
                is Command.Finish -> {
                    drain()
                    val session = checkNotNull(state.value.session)
                    store.finish(session.ride.id, command.tick, command.status)
                    state.update { it.copy(session = null, accepting = false) }
                }
                is Command.Close -> {
                    drain()
                    state.update { it.copy(closed = true, accepting = false) }
                }
                is Command.Delete -> {
                    val active = state.value.session?.ride?.id == command.rideId
                    if (active) drain()
                    store.delete(command.rideId)
                    if (active) state.update { it.copy(session = null, accepting = false) }
                }
            }
            command.receipt.complete(Unit)
        } catch (error: Exception) {
            failed(error)
            command.receipt.completeExceptionally(error)
        }
    }

    private suspend fun drain() {
        while (true) {
            val current = state.value
            val packet = current.pending.firstOrNull()
            if (packet != null) {
                store.append(packet.batch)
                state.update { it.copy(pending = it.pending.drop(1), events = it.events - packet.events,
                    bytes = it.bytes - packet.bytes, committed = it.committed + packet.events, failure = null) }
                continue
            }
            val loss = current.loss ?: break
            val session = checkNotNull(current.session)
            // Reserved metadata lane: a full telemetry queue must still record its loss boundary.
            val gap = Packet(HistoryBatch(session.ride.id, emptyList(), emptyList(), listOf(loss),
                token(session, current.nextToken)), 0, 0)
            if (state.compareAndSet(current, current.copy(pending = listOf(gap), loss = null, nextToken = current.nextToken + 1))) continue
        }
        state.update { it.copy(failure = null) }
    }
    private fun failed(error: Exception) {
        state.update { it.copy(accepting = false, failure = error.message ?: "Persistence failed") }
    }
    private fun token(session: Session, sequence: Long) = "${session.ride.id}:${session.ride.started.clockGeneration}:$sequence"
    private companion object {
        const val MAX_EVENTS = 2048L
        const val MAX_BYTES = 4L * 1024 * 1024
        const val FLUSH_EVENTS = 128L
    }
}

private fun historyFlushTicks(): Flow<Unit> = flow {
    while (currentCoroutineContext().isActive) { delay(1000); emit(Unit) }
}
