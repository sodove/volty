package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.nearby.community.*

data class ChannelDeletionState(
    val community: String? = null,
    val channel: String? = null,
    val busy: Boolean = false,
    val error: ChannelCommandFailure? = null,
    val deleted: ChannelDeletionReceipt? = null,
)

/** Screen consumer of account commands. The server owns channel removal and media cleanup. */
class ChannelDeletionWorkspace(
    parent: CoroutineScope,
    private val account: Job,
    private val actor: String,
    private val workspace: CommunityWorkspace,
    private val commands: ChannelCommandsPort,
    private val nextId: () -> String,
) {
    private val screen = requireNotNull(parent.coroutineContext[Job])
    init {
        invitationDraftId(actor)
        require(workspace.belongsTo(screen, account))
    }

    private val lifetime = SupervisorJob(screen)
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val mutex = Mutex()
    private val output = MutableStateFlow(ChannelDeletionState())
    val state = output.asStateFlow()

    private data class Target(val community: String, val channel: String)
    private data class Request(val target: Target, val operation: String)

    /** Retain uncertain operation IDs per exact target, including while another target is deleted. */
    private val retryOperations = mutableMapOf<Target, String>()
    private var closed = false
    private var accountGuardian: Job? = null

    init {
        accountGuardian = CoroutineScope(parent.coroutineContext + account).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        scope.launch { workspace.retired.first { it }; close() }
        scope.launch {
            workspace.browser.state.collect { browser ->
                mutex.withLock {
                    val shown = output.value
                    if (active() && !shown.busy && shown.community != null &&
                        ownerProof(browser, shown.community) == false) {
                        output.value = ChannelDeletionState()
                    }
                }
            }
        }
    }

    private fun active() = !closed && screen.isActive && account.isActive && lifetime.isActive && workspace.active

    private fun ownedDetail(): CommunityDetails? {
        if (!active()) return null
        val browser = workspace.browser.state.value
        return browser.detail?.takeIf {
            !browser.detailLoading && browser.detailError == null &&
                it.community.id == browser.selectedCommunity && it.ownerId == actor
        }
    }

    private fun ownerProof(browser: CommunityBrowserState, community: String): Boolean? {
        if (browser.selectedCommunity != community) return false
        if (browser.detailLoading) return null
        if (browser.detailError != null) return false
        val detail = browser.detail ?: return null
        if (detail.community.id != community) return false
        return detail.ownerId == actor
    }

    private fun ownsTarget(target: Target): Boolean = ownedDetail()?.let { detail ->
        detail.community.id == target.community && detail.channels.any { it.id == target.channel }
    } == true

    suspend fun delete(community: String, channel: String): Boolean = mutex.withLock {
        if (!active() || output.value.busy || workspace.state.value.busy) return@withLock false
        val target = Target(community, channel)
        if (!ownsTarget(target)) return@withLock false
        val operation = retryOperations[target] ?: nextId().also(::invitationDraftId).also {
            retryOperations[target] = it
        }
        val request = Request(target, operation)
        output.value = ChannelDeletionState(community = community, channel = channel, busy = true)
        scope.launch { issue(request) }
        true
    }

    private suspend fun issue(request: Request) {
        val admitted = mutex.withLock {
            active() && workspace.state.value.busy.not() &&
                output.value.community == request.target.community && output.value.channel == request.target.channel &&
                output.value.busy && ownsTarget(request.target)
        }
        if (!admitted) {
            mutex.withLock {
                if (active() && output.value.busy && output.value.community == request.target.community &&
                    output.value.channel == request.target.channel) output.value = ChannelDeletionState()
            }
            return
        }

        val result = try {
            commands.delete(request.target.community, request.target.channel, request.operation)
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            ChannelDeletionCommandResult.Failed(ChannelCommandFailure.UNAVAILABLE)
        } catch (_: Exception) {
            ChannelDeletionCommandResult.Failed(ChannelCommandFailure.UNAVAILABLE)
        }

        mutex.withLock {
            if (!active()) return@withLock
            val current = output.value
            if (!current.busy || current.community != request.target.community || current.channel != request.target.channel) return@withLock
            // A held response may only update the same still-owned, still-listed channel.
            if (!ownsTarget(request.target)) {
                output.value = ChannelDeletionState()
                return@withLock
            }
            when (result) {
                is ChannelDeletionCommandResult.Failed -> output.value = ChannelDeletionState(
                    community = request.target.community,
                    channel = request.target.channel,
                    error = result.reason,
                )
                is ChannelDeletionCommandResult.Deleted -> {
                    val receipt = result.receipt
                    if (receipt.operationId != request.operation || receipt.communityId != request.target.community ||
                        receipt.channelId != request.target.channel || receipt.revision < 0) {
                        output.value = ChannelDeletionState(
                            community = request.target.community,
                            channel = request.target.channel,
                            error = ChannelCommandFailure.INVALID_RESPONSE,
                        )
                    } else {
                        retryOperations.remove(request.target)
                        output.value = ChannelDeletionState(
                            community = request.target.community,
                            channel = request.target.channel,
                            deleted = receipt,
                        )
                        // Refresh only this same selected server; never select a replacement channel.
                        if (ownedDetail()?.community?.id == request.target.community &&
                            workspace.browser.state.value.selectedCommunity == request.target.community) {
                            workspace.browser.openCommunity(request.target.community)
                        }
                    }
                }
            }
        }
    }

    suspend fun close(): Unit = withContext(NonCancellable) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            retryOperations.clear()
            output.value = ChannelDeletionState()
            lifetime.cancel()
            accountGuardian?.cancel()
        }
    }
}
