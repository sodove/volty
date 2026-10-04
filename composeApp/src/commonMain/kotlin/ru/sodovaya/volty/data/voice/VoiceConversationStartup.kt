package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.voice.*

internal interface VoiceConversationForegroundPort {
    val current: StateFlow<VoiceForegroundTicket?>
    fun request(runtime: AuthenticatedVoiceRuntime): VoiceForegroundTicket
    suspend fun awaitReady(ticket: VoiceForegroundTicket): Boolean
    fun stop(ticket: VoiceForegroundTicket): Boolean
}

internal enum class VoiceConversationPhase { STOPPED, PREPARING, JOIN_SUBMITTED, FAILED }
internal data class VoiceConversationState(
    val phase: VoiceConversationPhase = VoiceConversationPhase.STOPPED,
    val channelId: String? = null,
    val operationId: VoiceOperationId? = null,
    /** Actual matching runtime evidence only; absent during preparation or withdrawn intent. */
    val voice: VoiceSessionState? = null,
)

/** One process conversation intent. All selection/foreground operations run on queued Main.
 * UI command callers do not own startup or the active conversation. */
internal class VoiceConversationStartup(
    private val queuedDispatcher: CoroutineDispatcher,
    private val foreground: VoiceConversationForegroundPort,
    private val acquire: suspend (SocialAuthenticatedAccount) -> AuthenticatedVoiceRuntime,
) {
    private val mutableState = MutableStateFlow(VoiceConversationState())
    val state = mutableState.asStateFlow()
    private class Intent(val account: SocialAuthenticatedAccount, val channel: String,
                         val operation: VoiceOperationId, val replace: Boolean,
                         val preferences: suspend () -> VoiceAudioPreferences,
                         var launcher: ((VoiceForegroundTicket) -> Boolean)?) {
        var runtime: AuthenticatedVoiceRuntime? = null
        var ticket: VoiceForegroundTicket? = null
        var work: Job? = null
        var guardian: Job? = null
        var foregroundObserver: Job? = null
    }
    private var selected: Intent? = null
    private var rootJob: Job? = null
    private val issuedOperations = mutableSetOf<String>()

    suspend fun start(account: SocialAuthenticatedAccount, channel: String, operation: VoiceOperationId,
                      replace: Boolean = false, readPreferences: suspend () -> VoiceAudioPreferences,
                      launch: (VoiceForegroundTicket) -> Boolean) = withContext(queuedDispatcher) {
        currentCoroutineContext().ensureActive()
        check(account.job.isActive) { "Authenticated account retired" }
        invitationDraftId(account.userId.value)
        invitationDraftId(channel)
        invitationDraftId(operation.value)
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
        val intent = Intent(account, channel, operation, replace, readPreferences, launch)
        selected = intent
        mutableState.value = VoiceConversationState(VoiceConversationPhase.PREPARING, channel, operation)
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
            mutableState.value = VoiceConversationState(VoiceConversationPhase.JOIN_SUBMITTED, intent.channel, intent.operation)
            // Earlier terminal/wanted values cannot finish a new pending intent. Only this
            // unique operation's state can do so; ticket/account retirement have their own fences.
            val terminal = combine(runtime.state, runtime.retired) {voice, retired ->
                when {
                    retired || !isCurrent(intent) || foreground.current.value !== ticket -> VoiceConversationPhase.STOPPED
                    voice.operationId == intent.operation && !voice.wanted ->
                        if (voice.phase == VoicePhase.FAILED) VoiceConversationPhase.FAILED else VoiceConversationPhase.STOPPED
                    else -> {
                        if (voice.operationId == intent.operation)
                            mutableState.value = VoiceConversationState(VoiceConversationPhase.JOIN_SUBMITTED,
                                intent.channel, intent.operation, voice)
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
        mutableState.value = VoiceConversationState(phase, intent.channel, intent.operation)
        intent.guardian?.cancel()
        intent.foregroundObserver?.cancel()
        intent.work?.cancel()
        val stopped = intent.ticket?.let(foreground::stop) == true
        if (!stopped) intent.runtime?.requestDisconnect()
    }
}
