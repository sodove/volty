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
internal class IncomingCallOffer internal constructor(internal val ticket: IncomingCallNotificationTicket,
    val call: DirectCallSnapshot, val callerName: String? = null) { val id: String get() = ticket.id }
internal interface IncomingCallNotificationPort {
    fun show(ticket: IncomingCallNotificationTicket, call: DirectCallSnapshot, remainingMillis: Long): Boolean
    fun label(ticket: IncomingCallNotificationTicket, callerName: String, remainingMillis: Long): Boolean = false
    fun cancel(ticket: IncomingCallNotificationTicket)
}
/** Native publication only. An in-app offer cannot fabricate a notification action. */
internal data class IncomingCallNotificationState(val ticket: IncomingCallNotificationTicket? = null,
    val call: DirectCallSnapshot? = null, val retired: Boolean = false)

/** Queued SERIAL account-owned ringing offers and notifications. Borrows runtime; never controls media. */
internal class IncomingCallNotificationOwner(private val account: SocialAuthenticatedAccount, private val runtime: AuthenticatedVoiceRuntime,
    private val queuedDispatcher: CoroutineDispatcher, pulses: Flow<Unit>, private val clock: () -> Long,
    private val port: IncomingCallNotificationPort, private val nextId: () -> String,
    private val callerNames: IncomingCallCallerNames? = null) {
    private val verifier = IncomingCallHintVerifier(account, runtime, clock)
    private val lifetime = run {
        require(queuedDispatcher.isDispatchNeeded(account.scope().coroutineContext)) {"Queued serial dispatcher required"}
        require(callerNames == null || callerNames.belongsTo(account)) {"Caller names belong to another account"}
        SupervisorJob(account.job)
    }
    private val scope = CoroutineScope(account.scope().coroutineContext + lifetime + queuedDispatcher)
    private val output = MutableStateFlow(IncomingCallNotificationState())
    val state = output.asStateFlow()
    private val offerOutput = MutableStateFlow<IncomingCallOffer?>(null)
    val offer = offerOutput.asStateFlow()
    suspend fun acceptOffer(original: IncomingCallOffer): DirectCallSnapshot? = withContext(queuedDispatcher) {
        if (offerOutput.value !== original) return@withContext null
        accept(original.ticket, fromOffer = true)
    }
    suspend fun declineOffer(original: IncomingCallOffer, operation: VoiceOperationId): Deferred<DirectCallReply>? =
        withContext(queuedDispatcher) {
            invitationDraftId(operation.value)
            if (offerOutput.value !== original) return@withContext null
            val action = consume(original.ticket, fromOffer = true) ?: return@withContext null
            runtime.requestDeclineIncomingCall(action.call, operation)
        }
    private val reads = MutableStateFlow(0)
    /** Actual executing readers only; zero does not establish queue or socket idleness. */
    val readInFlight = reads.asStateFlow()
    private val cancellationFailed = MutableStateFlow(false)
    val cleanupFailed = cancellationFailed.asStateFlow()
    private class Hint(val callId: String, val consumed: Boolean = false,
        val beforePublish: (suspend () -> Boolean)? = null) {
        val completed = CompletableDeferred<Boolean>()
    }
    private var pending: Hint? = null
    private var latest: Hint? = null
    private var running: Hint? = null
    private var readerJob: Job? = null
    private var lastTime: Long? = null
    private var observation: DirectCallRingObservation? = null
    private val ticketOwner = Any()
    private val issuedIds = mutableSetOf<String>()
    private var nameJob: Job? = null
    private var namesClosed = false
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
        enqueueHint(callId, null) != null
    }

    private fun enqueueHint(callId: String, beforePublish: (suspend () -> Boolean)?): Hint? {
        invitationDraftId(callId)
        if (tick() == null) return null
        if (beforePublish == null && (pending?.callId == callId || (pending == null && running?.callId == callId))) return null
        withdraw()
        if (!lifetime.isActive) return null
        latest?.completed?.complete(false)
        val hint = Hint(callId, beforePublish = beforePublish)
        latest = hint; pending = hint
        if (readerJob == null) startReader()
        return hint
    }

    /** A background caller must wait for verification and its final publication fence. */
    suspend fun verifiedHint(callId: String, beforePublish: suspend () -> Boolean): Boolean {
        val hint = withContext(queuedDispatcher) {enqueueHint(callId, beforePublish)} ?: return false
        // The worker borrows this waiter; account-owned verification outlives waiter cancellation.
        return hint.completed.await()
    }

    /** Polling retries failed verification, but never replaces an earned or consumed offer. */
    suspend fun discoveryHint(callId: String): Boolean = withContext(queuedDispatcher) {
        invitationDraftId(callId)
        if (tick() == null || offerOutput.value?.call?.callId == callId ||
            latest?.let {it.callId == callId && it.consumed} == true) return@withContext false
        hint(callId)
    }

    suspend fun close() = withContext(NonCancellable) {withContext(queuedDispatcher) {closeOwned()}}
    /** A successful discovery response withdrew this exact call, including a held hint read. */
    suspend fun withdrawCall(callId: String) = withContext(queuedDispatcher) {
        if (latest?.callId == callId) {latest?.completed?.complete(false); latest = null}
        if (pending?.callId == callId) pending = null
        if (offerOutput.value?.call?.callId == callId) withdraw()
    }
    /** Metadata only: the caller still needs visible Activity admission to join the call. */
    suspend fun acceptTarget(ticket: IncomingCallNotificationTicket): DirectCallSnapshot? = accept(ticket, fromOffer = false)
    private suspend fun accept(ticket: IncomingCallNotificationTicket, fromOffer: Boolean): DirectCallSnapshot? = withContext(queuedDispatcher) {
        val action = consume(ticket, fromOffer) ?: return@withContext null
        if (!lifetime.isActive) return@withContext null
        val reply = try {
            reads.update {it + 1}
            verifier.verify(action.call.callId)
        } catch (failure: Exception) {
            retryVerification(action)
            throw failure
        } finally {reads.update {it - 1}}
        val now = tick() ?: return@withContext null
        if (reply is IncomingCallHintReply.Failed) retryVerification(action)
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
    /** A failed read earns no offer. A later discovery must verify again and mint a new ticket. */
    private fun retryVerification(action: Action) {
        if (latest === action.marker && lifetime.isActive && account.job.isActive && !runtime.retired.value)
            latest = Hint(action.call.callId)
    }
    private fun consume(ticket: IncomingCallNotificationTicket, fromOffer: Boolean = false): Action? {
        if (tick() == null || ticket.owner !== ticketOwner ||
            (if (fromOffer) offerOutput.value?.ticket !== ticket else output.value.ticket !== ticket)) return null
        val call = offerOutput.value?.call ?: return null
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
                    try {
                        if (lifetime.isActive && latest === hint) {
                            val allowed = try {hint.beforePublish?.invoke() ?: true}
                                catch (cancelled: CancellationException) {
                                    currentCoroutineContext().ensureActive(); false
                                } catch (_: Exception) {false}
                            // The fence may suspend for durable storage; recheck the exact queued marker.
                            if (allowed && lifetime.isActive && latest === hint) {
                                publish(reply)
                                hint.completed.complete(offerOutput.value?.call?.callId == hint.callId)
                            }
                        }
                    } finally {hint.completed.complete(false)}
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
        if (!shown || !lifetime.isActive || !account.job.isActive) cancelIssued(ticket)
        if (!lifetime.isActive || !account.job.isActive) return
        // Native submission can spend the remaining credit; it cannot earn fresh display time.
        val afterSubmission = tick()
        if (afterSubmission == null || !reply.observation.isFresh(afterSubmission)) {
            if (shown) cancelIssued(ticket)
            return
        }
        observation = reply.observation
        offerOutput.value = IncomingCallOffer(ticket, reply.call)
        if (shown) output.value = IncomingCallNotificationState(ticket, reply.call)
        enrichName(requireNotNull(offerOutput.value))
    }

    private fun enrichName(original: IncomingCallOffer) {
        val names = callerNames ?: return
        nameJob = scope.launch {
            val name = try {incomingCallDisplayName(names.lookup(original.call.callerId))}
                catch (cancelled: CancellationException) {throw cancelled}
                catch (_: Exception) {null}
            val now = tick() ?: return@launch
            if (name == null || offerOutput.value !== original) return@launch
            offerOutput.value = IncomingCallOffer(original.ticket, original.call, name)
            if (output.value.ticket === original.ticket) {
                val remaining = observation?.deadlineMillis?.minus(now) ?: return@launch
                try {port.label(original.ticket, name, remaining)} catch (_: Exception) { /* Optional metadata. */ }
                tick()
            }
        }
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
        nameJob?.cancel(); nameJob = null
        val ticket = output.value.ticket
        offerOutput.value = null
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
        withdraw(); latest?.completed?.complete(false); pending?.completed?.complete(false)
        pending = null; latest = null
        output.value = IncomingCallNotificationState(retired = true)
        lifetime.cancel()
        if (!namesClosed) {
            namesClosed = true
            try {callerNames?.close()} catch (_: Exception) { /* Metadata cleanup cannot retain an offer. */ }
        }
    }
}
