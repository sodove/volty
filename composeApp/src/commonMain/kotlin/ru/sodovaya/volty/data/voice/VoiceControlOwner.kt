package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import ru.sodovaya.volty.domain.voice.*

/** Server intent confirmations only. Ended is not an SFU revocation ACK. */
sealed interface VoiceStopRequest {
    data class Operation(val operation: VoiceOperationId, val generation: VoiceGeneration) : VoiceStopRequest
    data class Session(val tag: VoiceSessionTag) : VoiceStopRequest
}

data class VoiceControlDispatch(val effect: VoiceSessionEffect, val credentials: VoiceConnectionCredentials? = null,
    val sharingOrigin: VoiceSessionTag? = null)

/**
 * Mailbox foundation; caller owns lifetime and a NONBLOCKING engine/sharing
 * dispatcher. No timers, DI, offline persistence or SFU-ACK projection yet.
 * Network completion never owns state. Credentials are delivered once, not
 * exposed through state flows. Cancelled requests can still require cleanup.
 */
class VoiceControlOwner(
    private val scope: CoroutineScope,
    private val port: VoiceSessionControlPort,
    private val dispatch: (VoiceControlDispatch) -> Unit,
) {
    private val inbox = Channel<Command>(64)
    private val stateOutput = MutableStateFlow(VoiceSessionState())
    val state = stateOutput.asStateFlow()
    private val stopsOutput = MutableStateFlow<Set<VoiceStopRequest>>(emptySet())
    val unconfirmedStops = stopsOutput.asStateFlow()
    private val remoteStopsOutput = MutableStateFlow<Set<VoiceStopRequest>>(emptySet())
    /** Unknown/missing cleanup status remains pending independently of engine release. */
    val pendingRemoteStops = remoteStopsOutput.asStateFlow()
    private val workCount = MutableStateFlow(0)
    /** Application-owned producers including reply delivery; zero is not mailbox/socket idle. */
    val inFlight = workCount.asStateFlow()
    private var attempt: Attempt? = null
    private var joinJob: Job? = null
    private var heartbeatJob: Job? = null
    private val sessions = mutableMapOf<VoiceSessionId, VoiceSessionTag>()
    private val stopping = mutableSetOf<VoiceStopRequest>()
    private val ownerJob: Job

    init { ownerJob = scope.launch { for (command in inbox) handle(command) } }

    suspend fun join(channel: String, operation: VoiceOperationId, replace: Boolean = false) {
        send(Command.Join(channel, operation, replace))
    }
    suspend fun stop() = send(Command.Event(VoiceSessionEvent.Stop))
    suspend fun retryJoin() = send(Command.RetryJoin(null))
    suspend fun retryJoin(operation: VoiceOperationId, generation: VoiceGeneration) = send(Command.RetryJoin(operation to generation))
    suspend fun heartbeat() = send(Command.Heartbeat(null))
    suspend fun heartbeat(tag: VoiceSessionTag) = send(Command.Heartbeat(tag))
    suspend fun retryStops() = send(Command.RetryStops)
    suspend fun event(event: VoiceSessionEvent) {
        require(event !is VoiceSessionEvent.Join && event !is VoiceSessionEvent.SessionGranted && event !is VoiceSessionEvent.LeaseAck)
        send(Command.Event(event))
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Join -> {
                if (stateOutput.value.operationId == command.operation) return
                val transition = VoiceSessionReducer.reduce(stateOutput.value, VoiceSessionEvent.Join(command.operation))
                port.rememberJoinIntent(command.operation, transition.state.generation, command.channel, command.replace)
                attempt = Attempt(command.operation, transition.state.generation, command.channel, command.replace)
                apply(transition)
            }
            is Command.Event -> {
                if (command.event is VoiceSessionEvent.EnableSharing) {
                    val operation = stateOutput.value.operationId ?: return
                    val permitted = try {port.allowsSharing(operation)} catch (_: Exception) {false}
                    if (!permitted) return
                }
                apply(VoiceSessionReducer.reduce(stateOutput.value, command.event))
            }
            is Command.RetryJoin -> {
                val s = stateOutput.value
                if (s.wanted && s.sessionId == null && joinJob?.isActive != true &&
                    (command.expected == null || command.expected == s.operationId to s.generation)) attempt?.let(::requestJoin)
            }
            is Command.Heartbeat -> {
                val s = stateOutput.value
                if (s.wanted && s.sessionId != null && heartbeatJob?.isActive != true) {
                    val tag = VoiceSessionTag(s.sessionId, s.generation)
                    if (command.expected != null && command.expected != tag) return
                    heartbeatJob = launchWork { deliver(Command.HeartbeatReply(tag, network { port.heartbeat(tag) })) }
                }
            }
            Command.RetryStops -> (stopsOutput.value + remoteStopsOutput.value).forEach(::requestStop)
            is Command.JoinReply -> onJoin(command.attempt, command.reply)
            is Command.HeartbeatReply -> {
                val s = stateOutput.value
                if (!s.wanted || s.generation != command.tag.generation || s.sessionId != command.tag.sessionId) return
                when (val reply = command.reply) {
                    is VoiceHttpReply.Acknowledged -> apply(VoiceSessionReducer.reduce(s, VoiceSessionEvent.LeaseAck(reply.tag, reply.lease)))
                    is VoiceHttpReply.Expired, is VoiceHttpReply.Ended -> apply(VoiceSessionReducer.reduce(s, VoiceSessionEvent.Failed(s.generation)))
                    is VoiceHttpReply.Failed -> if (reply.reason in setOf(VoiceHttpFailure.ACCESS_DENIED, VoiceHttpFailure.UNAUTHORIZED))
                        apply(VoiceSessionReducer.reduce(s, VoiceSessionEvent.Failed(s.generation)))
                    else -> Unit
                }
            }
            is Command.StopReply -> {
                stopping -= command.request
                val ended = command.reply as? VoiceHttpReply.Ended ?: return
                val matches = when (val request = command.request) {
                    is VoiceStopRequest.Operation -> ended.generation == request.generation
                    is VoiceStopRequest.Session -> ended.generation == request.tag.generation && ended.sessionId == request.tag.sessionId
                }
                if (matches) {
                    stopsOutput.value -= command.request
                    if (ended.cleanupPending == false) {
                        remoteStopsOutput.value -= command.request
                        if (command.request is VoiceStopRequest.Session) sessions.remove(command.request.tag.sessionId)
                    }
                }
            }
        }
    }

    private fun onJoin(request: Attempt, reply: VoiceHttpReply) {
        val s = stateOutput.value
        val current = s.wanted && s.generation == request.generation && s.operationId == request.operation
        when (reply) {
            is VoiceHttpReply.Credentials -> {
                sessions[reply.tag.sessionId] = reply.tag
                apply(VoiceSessionReducer.reduce(s, VoiceSessionEvent.SessionGranted(request.operation,
                    request.generation, reply.tag.sessionId, reply.lease)), reply)
            }
            is VoiceHttpReply.Expired -> {
                sessions[reply.tag.sessionId] = reply.tag
                if (current) apply(VoiceSessionReducer.reduce(s, VoiceSessionEvent.Failed(request.generation)))
                requestStop(VoiceStopRequest.Session(reply.tag))
            }
            is VoiceHttpReply.Pending -> Unit // explicit retry reuses the same operation and destination
            else -> if (current) apply(VoiceSessionReducer.reduce(s, VoiceSessionEvent.Failed(request.generation)))
        }
    }

    private fun apply(transition: VoiceTransition, grant: VoiceHttpReply.Credentials? = null) {
        val preceding=stateOutput.value
        val previousTag=preceding.sessionId?.let {VoiceSessionTag(it,preceding.generation)}
        stateOutput.value = transition.state
        for (effect in transition.effects) when (effect) {
            is VoiceSessionEffect.RequestJoin -> requestJoin(checkNotNull(attempt).also {
                check(it.operation == effect.operationId && it.generation == effect.generation)
            })
            is VoiceSessionEffect.CancelJoin -> {
                joinJob?.cancel()
                requestStop(VoiceStopRequest.Operation(effect.operationId, effect.generation))
            }
            is VoiceSessionEffect.StopSession -> requestStop(VoiceStopRequest.Session(checkNotNull(sessions[effect.sessionId])))
            is VoiceSessionEffect.StopEngine -> {
                heartbeatJob?.cancel()
                dispatch(VoiceControlDispatch(effect))
            }
            is VoiceSessionEffect.StartEngine -> {
                check(grant?.tag == effect.tag)
                dispatch(VoiceControlDispatch(effect, grant.connection))
            }
            VoiceSessionEffect.AddNearbyDemand -> dispatch(VoiceControlDispatch(effect,sharingOrigin=
                transition.state.sessionId?.let {VoiceSessionTag(it,transition.state.generation)}))
            VoiceSessionEffect.RemoveNearbyDemand,VoiceSessionEffect.ClearSharingQueue ->
                dispatch(VoiceControlDispatch(effect,sharingOrigin=previousTag))
            else -> dispatch(VoiceControlDispatch(effect))
        }
    }

    private fun requestJoin(request: Attempt) {
        joinJob = launchWork { deliver(Command.JoinReply(request,
            network { port.join(request.operation, request.generation, request.channel, request.replace) })) }
    }

    private fun requestStop(request: VoiceStopRequest) {
        stopsOutput.value += request
        remoteStopsOutput.value += request
        if (!stopping.add(request)) return
        launchWork {
            val reply = network { when (request) {
                is VoiceStopRequest.Operation -> port.cancel(request.operation, request.generation)
                is VoiceStopRequest.Session -> port.disconnect(request.tag)
            } }
            deliver(Command.StopReply(request, reply))
        }
    }

    private fun launchWork(block: suspend CoroutineScope.() -> Unit): Job {
        workCount.update { check(it < Int.MAX_VALUE); it + 1 }
        val job = try { scope.launch(block = block) }
        catch (failure: Throwable) { workCount.update { it - 1 }; throw failure }
        // Cancelled queued jobs may never enter a body/finally. Only actual completion drains.
        job.invokeOnCompletion { workCount.update { it - 1 } }
        return job
    }

    private suspend fun network(block: suspend () -> VoiceHttpReply): VoiceHttpReply = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE)
    }

    // A transport may return after cancellation. Preserve that reply's identity
    // for cleanup while the mailbox lifetime exists; do not suppress it locally.
    private suspend fun deliver(command: Command) = withContext(NonCancellable) {
        select<Unit> {
            ownerJob.onJoin { }
            inbox.onSend(command) { }
        }
    }

    private suspend fun send(command: Command) = select<Unit> {
        ownerJob.onJoin { throw CancellationException("Voice owner closed") }
        inbox.onSend(command) { }
    }

    private data class Attempt(val operation: VoiceOperationId, val generation: VoiceGeneration, val channel: String, val replace: Boolean)
    private sealed interface Command {
        data class Join(val channel: String, val operation: VoiceOperationId, val replace: Boolean) : Command
        data class Event(val event: VoiceSessionEvent) : Command
        data class JoinReply(val attempt: Attempt, val reply: VoiceHttpReply) : Command
        data class HeartbeatReply(val tag: VoiceSessionTag, val reply: VoiceHttpReply) : Command
        data class StopReply(val request: VoiceStopRequest, val reply: VoiceHttpReply) : Command
        data class RetryJoin(val expected: Pair<VoiceOperationId, VoiceGeneration>?) : Command
        data class Heartbeat(val expected: VoiceSessionTag?) : Command
        data object RetryStops : Command
    }
}
