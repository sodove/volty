package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.nearby.community.*

sealed interface CommunityWorkspaceError {
    data class Community(val reason: CommunityFailure): CommunityWorkspaceError
    data class Removal(val reason: CommunityRemovalFailure): CommunityWorkspaceError
    data object InvalidInput: CommunityWorkspaceError
}
data class CommunityRemovalConfirmation(val community: String, val delete: Boolean)
data class CommunityWorkspaceState(
    val busy: Boolean = false,
    val confirmation: CommunityRemovalConfirmation? = null,
    val error: CommunityWorkspaceError? = null,
    val cleanupPending: Boolean = false,
)

/** Owns screen work only. Account ports and any conversation remain borrowed. */
class CommunityWorkspace(
    parent: CoroutineScope,
    private val account: Job,
    private val communities: CommunityPort,
    private val removals: CommunityRemovalPort,
    private val nextId: () -> String,
) {
    private val screen = requireNotNull(parent.coroutineContext[Job]) { "Workspace requires screen Job" }
    init { require(screen.isActive && account.isActive) { "Workspace owners must be active" } }
    private val lifetime = SupervisorJob(screen)
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val mutex = Mutex()
    private val output = MutableStateFlow(CommunityWorkspaceState())
    val state = output.asStateFlow()
    val browser = CommunityBrowser(scope, communities)
    val active: Boolean get() = positive()
    internal fun belongsTo(screen: Job, account: Job) = this.screen === screen && this.account === account && active
    private val retiredState = MutableStateFlow(false)
    /** Permission withdrawal, not proof that a cancellation-ignoring HTTP producer returned. */
    val retired = retiredState.asStateFlow()
    private var closed = false
    private sealed interface Request {
        data class Create(val name: String, val description: String, val visibility: CommunityVisibility): Request
        data class Join(val community: String): Request
        data class Remove(val community: String, val delete: Boolean, val successor: String?): Request
    }
    private data class Retry(val request: Request, val id: String?)
    private var retry: Retry? = null
    private sealed interface Completion {
        data class Entered(val details: CommunityDetails): Completion
        data class Removed(val receipt: CommunityRemovalReceipt): Completion
        data class Failed(val error: CommunityWorkspaceError): Completion
    }
    private var accountGuardian: Job? = null
    init {
        accountGuardian = CoroutineScope(parent.coroutineContext + account).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
    }

    suspend fun create(name: String, description: String, visibility: CommunityVisibility): Boolean = mutex.withLock {
        if (!available()) return@withLock false
        val title = name.trim(); val about = description.trim()
        if (title.length !in 1..80 || title.any(Char::isISOControl) || about.length > 1000 || '\u0000' in about) {
            output.value = output.value.copy(error = CommunityWorkspaceError.InvalidInput)
            return@withLock false
        }
        start(Request.Create(title, about, visibility)) { id ->
            entered(communities.create(requireNotNull(id), title, about, visibility))
        }
    }
    suspend fun join(community: String): Boolean = mutex.withLock {
        if (!available()) return@withLock false
        invitationDraftId(community)
        start(Request.Join(community)) { entered(communities.joinPublic(community)) }
    }
    suspend fun requestRemoval(community: String, delete: Boolean): Boolean = mutex.withLock {
        if (!available() || browser.state.value.selectedCommunity != community) return@withLock false
        invitationDraftId(community)
        output.value = output.value.copy(confirmation = CommunityRemovalConfirmation(community, delete), error = null)
        true
    }
    suspend fun dismissRemoval() = mutex.withLock {
        if (!closed) output.value = output.value.copy(confirmation = null)
    }
    suspend fun confirmRemoval(successor: String? = null): Boolean = mutex.withLock {
        if (!available()) return@withLock false
        val confirmation = output.value.confirmation ?: return@withLock false
        if (browser.state.value.selectedCommunity != confirmation.community) {
            output.value = output.value.copy(confirmation = null)
            return@withLock false
        }
        if (confirmation.delete && successor != null) return@withLock false
        successor?.let(::invitationDraftId)
        val request = Request.Remove(confirmation.community, confirmation.delete, successor)
        start(request) { id ->
            val result = if (request.delete) removals.delete(request.community, requireNotNull(id))
                else removals.leave(request.community, requireNotNull(id), request.successor)
            when (result) {
                is CommunityRemovalResult.Removed -> Completion.Removed(result.receipt)
                is CommunityRemovalResult.Failed -> Completion.Failed(CommunityWorkspaceError.Removal(result.reason))
            }
        }
    }

    suspend fun close(): Unit = withContext(NonCancellable) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            retiredState.value = true
            output.value = CommunityWorkspaceState(); retry = null
            lifetime.cancel(); accountGuardian?.cancel()
            browser.close()
        }
    }
    private fun positive() = !closed && screen.isActive && account.isActive && lifetime.isActive
    private fun available() = positive() && !output.value.busy

    // Mutex held through admission, never through the HTTP producer.
    private fun start(request: Request, work: suspend (String?) -> Completion): Boolean {
        val original = retry?.takeIf { it.request == request } ?: Retry(request,
            if (request is Request.Join) null else nextId().also(::invitationDraftId)).also { retry = it }
        val selection = browser.state.value.selectedCommunity
        output.value = CommunityWorkspaceState(busy = true)
        scope.launch {
            mutex.withLock { if (!positive()) return@launch }
            val result = try { work(original.id) } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                unavailable(request)
            } catch (_: Exception) { unavailable(request) }
            mutex.withLock {
                if (closed || !screen.isActive || !account.isActive || !lifetime.isActive) return@withLock
                when (result) {
                    is Completion.Failed -> output.value = CommunityWorkspaceState(error = result.error)
                    is Completion.Entered -> {
                        val expected = when (request) {
                            is Request.Create -> original.id
                            is Request.Join -> request.community
                            else -> null
                        }
                        if (result.details.community.id != expected) {
                            output.value = CommunityWorkspaceState(error = CommunityWorkspaceError.Community(CommunityFailure.INVALID_RESPONSE))
                        } else {
                            retry = null; output.value = CommunityWorkspaceState()
                            browser.refreshMemberships()
                            if (browser.state.value.selectedCommunity == selection) browser.openCommunity(result.details.community.id)
                        }
                    }
                    is Completion.Removed -> {
                        if (request !is Request.Remove || result.receipt.communityId != request.community || result.receipt.operationId != original.id) {
                            output.value = CommunityWorkspaceState(error = CommunityWorkspaceError.Removal(CommunityRemovalFailure.INVALID_RESPONSE))
                        } else {
                            retry = null; output.value = CommunityWorkspaceState(cleanupPending = result.receipt.cleanupPending)
                            browser.refreshMemberships()
                            if (browser.state.value.selectedCommunity == request.community) browser.clearSelection()
                        }
                    }
                }
            }
        }
        return true
    }
    private fun entered(result: CommunityResult<CommunityDetails>): Completion = when (result) {
        is CommunityResult.Ready -> Completion.Entered(result.value)
        is CommunityResult.Failed -> Completion.Failed(CommunityWorkspaceError.Community(result.reason))
    }
    private fun unavailable(request: Request) = Completion.Failed(
        if (request is Request.Remove) CommunityWorkspaceError.Removal(CommunityRemovalFailure.UNAVAILABLE)
        else CommunityWorkspaceError.Community(CommunityFailure.UNAVAILABLE))
}
