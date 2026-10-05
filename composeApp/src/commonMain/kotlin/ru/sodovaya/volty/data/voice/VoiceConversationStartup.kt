package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.data.nearby.DirectCallSnapshot
import ru.sodovaya.volty.data.nearby.DirectCallPhase
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.voice.*

internal interface VoiceConversationForegroundPort {
    val current: StateFlow<VoiceForegroundTicket?>
    fun request(runtime: AuthenticatedVoiceRuntime): VoiceForegroundTicket
    suspend fun awaitReady(ticket: VoiceForegroundTicket): Boolean
    fun stop(ticket: VoiceForegroundTicket): Boolean
}

internal enum class VoiceConversationPhase { STOPPED, PREPARING, JOIN_SUBMITTED, FAILED }
internal fun interface VoiceConversationCallRosterFactory {
    suspend fun create(account: SocialAuthenticatedAccount, tag: DirectCallRosterTag,
                       own: VoiceSessionTag): AuthenticatedCallRosterOwner
}
internal sealed interface VoiceConversationTarget {
    val destinationId: String
    data class Channel(override val destinationId: String) : VoiceConversationTarget
    data class OutgoingCall(override val destinationId: String, val calleeId: String, val idempotencyKey: String) : VoiceConversationTarget
    data class IncomingCall(val call: DirectCallSnapshot) : VoiceConversationTarget {
        override val destinationId get() = call.callId
    }
}
internal data class VoiceConversationState(
    val phase: VoiceConversationPhase = VoiceConversationPhase.STOPPED,
    val channelId: String? = null,
    val operationId: VoiceOperationId? = null,
    /** Actual matching runtime evidence only; absent during preparation or withdrawn intent. */
    val voice: VoiceSessionState? = null,
    val target: VoiceConversationTarget? = null,
    val callRoster: DirectCallRosterPresenceView? = null,
)

/** One process conversation intent. All selection/foreground operations run on queued Main.
 * UI command callers do not own startup or the active conversation. */
internal class VoiceConversationStartup(
    private val queuedDispatcher: CoroutineDispatcher,
    private val foreground: VoiceConversationForegroundPort,
    private val callRosters: VoiceConversationCallRosterFactory?,
    private val acquire: suspend (SocialAuthenticatedAccount) -> AuthenticatedVoiceRuntime,
) {
    constructor(queuedDispatcher: CoroutineDispatcher, foreground: VoiceConversationForegroundPort,
                acquire: suspend (SocialAuthenticatedAccount) -> AuthenticatedVoiceRuntime) :
        this(queuedDispatcher, foreground, null, acquire)
    private val mutableState = MutableStateFlow(VoiceConversationState())
    val state = mutableState.asStateFlow()
    private class Intent(val account: SocialAuthenticatedAccount, val target: VoiceConversationTarget,
                         val operation: VoiceOperationId, val replace: Boolean,
                         val preferences: suspend () -> VoiceAudioPreferences,
                         var launcher: ((VoiceForegroundTicket) -> Boolean)?) {
        val channel get() = target.destinationId
        var runtime: AuthenticatedVoiceRuntime? = null
        var ticket: VoiceForegroundTicket? = null
        var work: Job? = null
        var guardian: Job? = null
        var foregroundObserver: Job? = null
        var rosterSession: VoiceSessionTag? = null
        var roster: AuthenticatedCallRosterOwner? = null
        var rosterWork: Job? = null
    }
    private var selected: Intent? = null
    private var rootJob: Job? = null
    private val issuedOperations = mutableSetOf<String>()

    suspend fun start(account: SocialAuthenticatedAccount, channel: String, operation: VoiceOperationId,
                      replace: Boolean = false, readPreferences: suspend () -> VoiceAudioPreferences,
                      launch: (VoiceForegroundTicket) -> Boolean) =
        start(account, VoiceConversationTarget.Channel(channel), operation, replace, readPreferences, launch)

    suspend fun start(account: SocialAuthenticatedAccount, target: VoiceConversationTarget, operation: VoiceOperationId,
                      replace: Boolean = false, readPreferences: suspend () -> VoiceAudioPreferences,
                      launch: (VoiceForegroundTicket) -> Boolean) = withContext(queuedDispatcher) {
        currentCoroutineContext().ensureActive()
        check(account.job.isActive) { "Authenticated account retired" }
        invitationDraftId(account.userId.value)
        invitationDraftId(target.destinationId)
        invitationDraftId(operation.value)
        require(target is VoiceConversationTarget.Channel || callRosters != null) { "Call roster owner required" }
        when (target) {
            is VoiceConversationTarget.Channel -> Unit
            is VoiceConversationTarget.OutgoingCall -> {
                require(!replace) { "Call admission cannot replace by channel UUID" }
                invitationDraftId(target.calleeId); invitationDraftId(target.idempotencyKey)
                require(target.calleeId != account.userId.value) { "Cannot call own account" }
            }
            is VoiceConversationTarget.IncomingCall -> {
                require(!replace && target.call.phase == DirectCallPhase.RINGING &&
                    target.call.calleeId == account.userId.value && target.call.callerId != account.userId.value) { "Not an incoming ring" }
                invitationDraftId(target.call.callerId)
            }
        }
        val parent = account.retirementParentScope()
        val job = requireNotNull(parent.coroutineContext[Job]) { "Conversation requires application Job" }
        check(job.isActive) { "Conversation parent stopped" }
        require(job !== account.job) { "Conversation cleanup must outlive account cancellation" }
        require(queuedDispatcher.isDispatchNeeded(parent.coroutineContext)) { "Queued serial dispatcher required" }
        require(rootJob == null || rootJob === job) { "Conversation repository root does not match" }
        require(issuedOperations.add(operation.value)) { "Conversation operation cannot be reused" }
        rootJob = job
        // Fence the previous intent before cancellation or any asynchronous acquisition.
        selected?.let { finish(it, VoiceConversationPhase.STOPPED) }
        val intent = Intent(account, target, operation, replace, readPreferences, launch)
        selected = intent
        mutableState.value = conversationState(intent, VoiceConversationPhase.PREPARING)
        val scope = CoroutineScope(parent.coroutineContext + queuedDispatcher)
        intent.work = scope.launch(start = CoroutineStart.LAZY) { execute(intent, scope) }
        intent.guardian = account.scope().launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { withContext(queuedDispatcher) { finish(intent, VoiceConversationPhase.STOPPED) } } }
        }
        intent.work?.start()
    }

    suspend fun stop() = withContext(NonCancellable) {
        withContext(queuedDispatcher) { selected?.let {finish(it, VoiceConversationPhase.STOPPED)} }
    }

    /** Withdraws only this original intent, including preparation. True is admission, not native cleanup ACK. */
    suspend fun stop(operation: VoiceOperationId): Boolean = withContext(NonCancellable) {
        withContext(queuedDispatcher) {
            val intent = selected?.takeIf {it.operation == operation} ?: return@withContext false
            finish(intent, VoiceConversationPhase.STOPPED)
            true
        }
    }

    /** True is command admission, not evidence of capture/rendering or native effect completion. */
    suspend fun setMuted(operation: VoiceOperationId, value: Boolean): Boolean =
        control(operation) {it.setMuted(operation, value)}
    suspend fun setDeafened(operation: VoiceOperationId, value: Boolean): Boolean =
        control(operation) {it.setDeafened(operation, value)}

    private suspend fun control(operation: VoiceOperationId,
                                command: suspend (AuthenticatedVoiceRuntime) -> Boolean): Boolean = withContext(queuedDispatcher) {
        val intent = selected ?: return@withContext false
        val runtime = intent.runtime ?: return@withContext false
        if (!isCurrent(intent) || intent.operation != operation || mutableState.value.phase != VoiceConversationPhase.JOIN_SUBMITTED ||
            foreground.current.value !== intent.ticket || runtime.state.value.operationId != operation || !runtime.state.value.wanted)
            return@withContext false
        command(runtime) && isCurrent(intent) && foreground.current.value === intent.ticket
    }

    private suspend fun execute(intent: Intent, scope: CoroutineScope) {
        try {
            if (!isCurrent(intent)) return
            val runtime = acquire(intent.account)
            if (!isCurrent(intent) || runtime.retired.value) return
            intent.runtime = runtime
            val registered = when (val target = intent.target) {
                is VoiceConversationTarget.Channel -> true
                is VoiceConversationTarget.OutgoingCall -> runtime.registerOutgoingCall(target.destinationId,
                    target.calleeId, target.idempotencyKey, intent.operation)
                is VoiceConversationTarget.IncomingCall -> runtime.registerIncomingCall(target.call, intent.operation)
            }
            if (!registered || !isCurrent(intent)) return
            val ticket = foreground.request(runtime)
            intent.ticket = ticket
            intent.foregroundObserver = scope.launch {
                foreground.current.first {it !== ticket}
                finish(intent, VoiceConversationPhase.STOPPED)
            }
            // Consume Activity-bound launch before loading preferences or observing a call.
            if (!launchForeground(intent, ticket) || !foreground.awaitReady(ticket) || !isCurrent(intent)) return
            val accepted = runtime.joinWithAudio(intent.channel, intent.operation, intent.replace, intent.preferences)
            if (!accepted || !isCurrent(intent)) return
            mutableState.value = conversationState(intent, VoiceConversationPhase.JOIN_SUBMITTED)
            // Earlier terminal/wanted values cannot finish a new pending intent. Only this
            // unique operation's state can do so; ticket/account retirement have their own fences.
            val terminal = combine(runtime.state, runtime.retired) {voice, retired ->
                when {
                    retired || !isCurrent(intent) || foreground.current.value !== ticket -> VoiceConversationPhase.STOPPED
                    voice.operationId == intent.operation && !voice.wanted ->
                        if (voice.phase == VoicePhase.FAILED) VoiceConversationPhase.FAILED else VoiceConversationPhase.STOPPED
                    else -> {
                        if (voice.operationId == intent.operation) {
                            observeCallRoster(intent, scope, voice)
                            if (!isCurrent(intent)) return@combine VoiceConversationPhase.STOPPED
                            mutableState.value = conversationState(intent, VoiceConversationPhase.JOIN_SUBMITTED, voice)
                        }
                        null
                    }
                }
            }.filterNotNull().first()
            finish(intent, terminal)
        } catch (_: CancellationException) {
            // Explicit stop/replacement already fenced. Unexpected parent cancellation still
            // reaches the final original-ticket cleanup without borrowing the cancelled UI Job.
        } catch (_: Exception) {
            finish(intent, VoiceConversationPhase.FAILED)
        } finally {
            intent.launcher = null
            withContext(NonCancellable) { withContext(queuedDispatcher) { finish(intent, VoiceConversationPhase.FAILED) } }
        }
    }

    private fun isCurrent(intent: Intent) = selected === intent && intent.account.job.isActive
    private fun conversationState(intent: Intent, phase: VoiceConversationPhase, voice: VoiceSessionState? = null) =
        VoiceConversationState(phase, intent.channel, intent.operation, voice, intent.target,
            intent.roster?.takeIf {voice != null && matchesSession(intent, it.ownSession)}?.state?.value)

    private fun matchesSession(intent: Intent, own: VoiceSessionTag): Boolean {
        val voice = intent.runtime?.state?.value ?: return false
        return isCurrent(intent) && intent.runtime?.retired?.value == false && foreground.current.value === intent.ticket &&
            voice.wanted && voice.operationId == intent.operation && voice.sessionId == own.sessionId && voice.generation == own.generation
    }

    private fun observeCallRoster(intent: Intent, scope: CoroutineScope, voice: VoiceSessionState) {
        val peer = when (val target = intent.target) {
            is VoiceConversationTarget.Channel -> return
            is VoiceConversationTarget.OutgoingCall -> target.calleeId
            is VoiceConversationTarget.IncomingCall -> target.call.callerId
        }
        val own = VoiceSessionTag(voice.sessionId ?: return, voice.generation)
        if (!voice.wanted) return
        intent.rosterSession?.let {
            if (it != own) finish(intent, VoiceConversationPhase.FAILED)
            return
        }
        intent.rosterSession = own
        val tag = DirectCallRosterTag(intent.channel, peer, own.generation.value)
        intent.rosterWork = scope.launch {
            var reader: AuthenticatedCallRosterOwner? = null
            try {
                val created = requireNotNull(callRosters).create(intent.account, tag, own)
                reader = created
                if (!matchesSession(intent, own)) return@launch
                check(created.tag == tag && created.ownSession == own) {"Call roster factory returned another session"}
                intent.roster = created
                created.state.collect {presence ->
                    if (!matchesSession(intent, own)) return@collect
                    if (presence.retired) finish(intent, VoiceConversationPhase.STOPPED)
                    else mutableState.value = conversationState(intent, VoiceConversationPhase.JOIN_SUBMITTED, intent.runtime?.state?.value)
                }
            } catch (_: CancellationException) {
                // Replacement already withdrew its view; the unique reader still closes below.
                if (matchesSession(intent, own)) finish(intent, VoiceConversationPhase.FAILED)
            } catch (_: Exception) {
                if (matchesSession(intent, own)) finish(intent, VoiceConversationPhase.FAILED)
            } finally {
                withContext(NonCancellable) {reader?.close()}
            }
        }
    }

    // No suspension here: an Activity-bound callback cannot be spilled into the long-lived
    // readiness/preferences/active-conversation continuation frame.
    private fun launchForeground(intent: Intent, ticket: VoiceForegroundTicket): Boolean {
        val launcher = intent.launcher
        intent.launcher = null
        return launcher?.invoke(ticket) == true
    }

    private fun finish(intent: Intent, phase: VoiceConversationPhase) {
        if (selected !== intent) return
        selected = null
        intent.launcher = null
        mutableState.value = conversationState(intent, phase)
        intent.guardian?.cancel()
        intent.foregroundObserver?.cancel()
        intent.rosterWork?.cancel()
        intent.work?.cancel()
        val stopped = intent.ticket?.let(foreground::stop) == true
        if (!stopped) intent.runtime?.requestDisconnect()
    }
}
