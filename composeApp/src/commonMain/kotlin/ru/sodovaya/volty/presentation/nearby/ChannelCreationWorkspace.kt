package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.nearby.community.*

data class ChannelCreationState(val community: String? = null, val busy: Boolean = false,
    val error: ChannelCommandFailure? = null, val invalidInput: Boolean = false,
    val created: ChannelCreationReceipt? = null)

/** A screen consumer of original account commands; never owns a conversation or borrowed browser. */
class ChannelCreationWorkspace(parent: CoroutineScope, private val account: Job, private val actor: String,
    private val workspace: CommunityWorkspace, private val commands: ChannelCommandsPort, private val nextId: () -> String) {
    private val screen = requireNotNull(parent.coroutineContext[Job])
    init { invitationDraftId(actor);require(workspace.belongsTo(screen, account)) }
    private val lifetime = SupervisorJob(screen)
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val mutex = Mutex()
    private val output = MutableStateFlow(ChannelCreationState())
    val state = output.asStateFlow()
    private data class Request(val community: String, val name: String, val channel: String)
    private var retry: Request? = null
    private var closed = false
    private var accountGuardian: Job? = null
    init {
        accountGuardian = CoroutineScope(parent.coroutineContext + account).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { close() } }
        scope.launch { workspace.retired.first { it };close() }
        scope.launch {
            workspace.browser.state.map { it.selectedCommunity }.distinctUntilChanged().collect { selected ->
                mutex.withLock {
                    if (active() && !output.value.busy && output.value.community != selected)
                        output.value = ChannelCreationState()
                }
            }
        }
    }
    private fun active() = !closed && screen.isActive && account.isActive && lifetime.isActive && workspace.active
    suspend fun create(name: String): Boolean = mutex.withLock {
        if (!active() || output.value.busy || workspace.state.value.busy) return@withLock false
        val browser = workspace.browser.state.value
        val detail = browser.detail?.takeIf { !browser.detailLoading && browser.detailError == null &&
            it.community.id == browser.selectedCommunity && it.ownerId == actor } ?: return@withLock false
        val title = name.trim()
        if (title.length !in 1..80 || title.any(Char::isISOControl)) {
            output.value = ChannelCreationState(detail.community.id, invalidInput = true)
            return@withLock false
        }
        val request = retry?.takeIf { it.community == detail.community.id && it.name == title }
            ?: Request(detail.community.id, title, nextId().also(::invitationDraftId)).also { retry = it }
        output.value = ChannelCreationState(request.community, busy = true)
        scope.launch {
            if (!active()) return@launch
            val result = try { commands.create(request.community, request.channel, request.name) }
                catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    ChannelCreationCommandResult.Failed(ChannelCommandFailure.UNAVAILABLE)
                } catch (_: Exception) { ChannelCreationCommandResult.Failed(ChannelCommandFailure.UNAVAILABLE) }
            mutex.withLock {
                if (!active()) return@withLock
                // The write may have completed, but its result cannot repaint a different server.
                if (workspace.browser.state.value.selectedCommunity != request.community) {
                    output.value = ChannelCreationState()
                    return@withLock
                }
                when (result) {
                    is ChannelCreationCommandResult.Failed -> output.value = ChannelCreationState(request.community, error = result.reason)
                    is ChannelCreationCommandResult.Created -> {
                        val receipt = result.receipt
                        if (receipt.communityId != request.community || receipt.channelId != request.channel ||
                            receipt.name != request.name || receipt.revision <= 0) {
                            output.value = ChannelCreationState(request.community, error = ChannelCommandFailure.INVALID_RESPONSE)
                        } else {
                            retry = null
                            output.value = ChannelCreationState(request.community, created = receipt)
                            workspace.browser.openCommunity(request.community)
                        }
                    }
                }
            }
        }
        true
    }
    suspend fun close(): Unit = withContext(NonCancellable) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true;retry = null;output.value = ChannelCreationState()
            lifetime.cancel();accountGuardian?.cancel()
        }
    }
}
