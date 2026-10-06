package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.nearby.DirectCallSnapshot
import ru.sodovaya.volty.data.nearby.DirectCallFailure
import ru.sodovaya.volty.data.nearby.DirectCallReply
import ru.sodovaya.volty.data.nearby.DirectCallRingObservation
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.voice.VoiceOperationId

internal class IncomingCallNotificationTicket internal constructor(val id: String, internal val owner: Any)
internal interface IncomingCallNotificationPort {
    fun show(ticket: IncomingCallNotificationTicket, call: DirectCallSnapshot, remainingMillis: Long): Boolean
    fun cancel(ticket: IncomingCallNotificationTicket)
}
internal data class IncomingCallNotificationState(val ticket: IncomingCallNotificationTicket? = null,
    val call: DirectCallSnapshot? = null, val retired: Boolean = false)

/** Queued SERIAL account-owned notifications. Borrows runtime; never controls media. */
internal class IncomingCallNotificationOwner(private val account: SocialAuthenticatedAccount, private val runtime: AuthenticatedVoiceRuntime,
    private val queuedDispatcher: CoroutineDispatcher, pulses: Flow<Unit>, private val clock: () -> Long,
    private val port: IncomingCallNotificationPort, private val nextId: () -> String) {
    private val verifier = IncomingCallHintVerifier(account, runtime, clock)
    private val lifetime = run {
        require(queuedDispatcher.isDispatchNeeded(account.scope().coroutineContext)) {"Queued serial dispatcher required"}
        SupervisorJob(account.job)
    }
    private val scope = CoroutineScope(account.scope().coroutineContext + lifetime + queuedDispatcher)
    private val output = MutableStateFlow(IncomingCallNotificationState())
    val state = output.asStateFlow()
    private val reads = MutableStateFlow(0)
    /** Actual executing readers only; zero does not establish queue or socket idleness. */
    val readInFlight = reads.asStateFlow()
    private val cancellationFailed = MutableStateFlow(false)
    val cleanupFailed = cancellationFailed.asStateFlow()
    private class Hint(val callId: String, val consumed: Boolean = false)
    private var pending: Hint? = null
    private var latest: Hint? = null
    private var running: Hint? = null
    private var readerJob: Job? = null
    private var lastTime: Long? = null
    private var observation: DirectCallRingObservation? = null
    private val ticketOwner = Any()
    private val issuedIds = mutableSetOf<String>()
    private var publishingTicket: IncomingCallNotificationTicket? = null
    internal fun belongsTo(candidate: SocialAuthenticatedAccount): Boolean = account === candidate
    /** Called synchronously by native actions on this same queued serial owner. */
    internal fun mayIssueAction(ticket: IncomingCallNotificationTicket): Boolean =
        lifetime.isActive && account.job.isActive && ticket.owner === ticketOwner &&
            (publishingTicket === ticket || output.value.ticket === ticket)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {
                withContext(NonCancellable) {withContext(queuedDispatcher) {closeOwned()}}
            }
        }
        scope.launch {
            try {pulses.collect {tick()}}
            catch (cancelled: CancellationException) {if (!currentCoroutineContext().isActive) throw cancelled}
            catch (_: Exception) { /* A broken scheduling source cannot freeze notification credit. */ }
            finally {if (lifetime.isActive) closeOwned()}
        }
    }

    suspend fun hint(callId: String): Boolean = withContext(queuedDispatcher) {
        invitationDraftId(callId)
        if (tick() == null) return@withContext false
        if (pending?.callId == callId || (pending == null && running?.callId == callId)) return@withContext false
        withdraw()
        if (!lifetime.isActive) return@withContext false
        val hint = Hint(callId)
        latest = hint; pending = hint
        if (readerJob == null) startReader()
        true
    }

    /** Polling retries failed verification, but never replaces an earned or consumed offer. */
    suspend fun discoveryHint(callId: String): Boolean = withContext(queuedDispatcher) {
        invitationDraftId(callId)
        if (tick() == null || output.value.call?.callId == callId ||
            latest?.let {it.callId == callId && it.consumed} == true) return@withContext false
        hint(callId)
    }

    suspend fun close() = withContext(NonCancellable) {withContext(queuedDispatcher) {closeOwned()}}
    /** A successful discovery response withdrew this exact call, including a held hint read. */
    suspend fun withdrawCall(callId: String) = withContext(queuedDispatcher) {
        if (latest?.callId == callId) latest = null
        if (pending?.callId == callId) pending = null
        if (output.value.call?.callId == callId) withdraw()
    }
    /** Metadata only: the caller still needs visible Activity admission to join the call. */
    suspend fun acceptTarget(ticket: IncomingCallNotificationTicket): DirectCallSnapshot? = withContext(queuedDispatcher) {
        val action = consume(ticket) ?: return@withContext null
        if (!lifetime.isActive) return@withContext null
        val reply = try {
            reads.update {it + 1}
            verifier.verify(action.call.callId)
        } finally {reads.update {it - 1}}
        val now = tick() ?: return@withContext null
        if (latest !== action.marker || reply !is IncomingCallHintReply.Ringing || !reply.observation.isFresh(now))
            return@withContext null
        reply.call
    }

    /** Original negative submission; UI owns the returned waiter, not its cleanup producer. */
    suspend fun decline(ticket: IncomingCallNotificationTicket, operation: VoiceOperationId): Deferred<DirectCallReply>? =
        withContext(queuedDispatcher) {
            invitationDraftId(operation.value)
            val action = consume(ticket) ?: return@withContext null
            runtime.requestDeclineIncomingCall(action.call, operation)
        }

    private class Action(val call: DirectCallSnapshot, val marker: Hint)
    private fun consume(ticket: IncomingCallNotificationTicket): Action? {
        if (tick() == null || ticket.owner !== ticketOwner || output.value.ticket !== ticket) return null
        val call = output.value.call ?: return null
        val evidence = observation ?: return null
        if (evidence.callId != call.callId || evidence.revision != call.revision) return null
        val marker = Hint(call.callId, consumed = true)
        withdraw(); pending = null; latest = marker
        // Failed notification cancellation cannot abort an already authorized negative action.
        return Action(call, marker)
    }

    private fun startReader() {
        readerJob = scope.launch {
            try {
                while (lifetime.isActive) {
                    val hint = pending ?: break
                    pending = null; running = hint
                    val reply = try {
                        reads.update {it + 1}
                        verifier.verify(hint.callId)
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw cancelled
                        IncomingCallHintReply.Failed(DirectCallFailure.UNAVAILABLE)
                    } catch (_: Exception) {IncomingCallHintReply.Failed(DirectCallFailure.UNAVAILABLE)}
                    finally {running = null; reads.update {it - 1}}
                    if (lifetime.isActive && latest === hint) publish(reply)
                }
            } catch (_: CancellationException) {
                // An obsolete read has no notification permission, even if HTTP returns late.
            } catch (_: Exception) {closeOwned()}
            finally {
                readerJob = null
                if (lifetime.isActive && pending != null) startReader()
            }
        }
    }

    private fun publish(reply: IncomingCallHintReply) {
        val now = tick() ?: return
        if (reply !is IncomingCallHintReply.Ringing || !reply.observation.isFresh(now)) return
        val id = nextId().also(::invitationDraftId)
        require(issuedIds.add(id)) {"Notification ticket cannot be reused"}
        val ticket = IncomingCallNotificationTicket(id, ticketOwner)
        publishingTicket = ticket
        val shown = try {port.show(ticket, reply.call, reply.observation.deadlineMillis - now)} catch (_: Exception) {false}
            finally {publishingTicket = null}
        if (!shown || !lifetime.isActive || !account.job.isActive) {
            cancelIssued(ticket); return
        }
        observation = reply.observation
        output.value = IncomingCallNotificationState(ticket, reply.call)
    }

    private fun tick(): Long? {
        if (!lifetime.isActive || !account.job.isActive || runtime.retired.value) {closeOwned(); return null}
        val now = try {clock()} catch (_: Exception) {-1}
        if (now < 0 || lastTime?.let {now < it} == true) {closeOwned(); return null}
        lastTime = now
        if (observation?.isFresh(now) == false) withdraw()
        return now.takeIf {lifetime.isActive}
    }

    private fun withdraw() {
        val ticket = output.value.ticket
        output.value = IncomingCallNotificationState(retired = output.value.retired)
        observation = null
        ticket?.let(::cancelIssued)
    }
    private fun cancelIssued(ticket: IncomingCallNotificationTicket) {
        try {port.cancel(ticket)} catch (_: Exception) {
            cancellationFailed.value = true
            output.value = IncomingCallNotificationState(retired = true)
            lifetime.cancel()
        }
    }
    private fun closeOwned() {
        withdraw(); pending = null; latest = null
        output.value = IncomingCallNotificationState(retired = true)
        lifetime.cancel()
    }
}
