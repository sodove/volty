package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.nearby.community.*

data class CommunityPage(
    val items: List<CommunitySummary>? = null,
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: CommunityFailure? = null,
)
data class CommunityBrowserState(
    val query: String = "",
    val catalog: CommunityPage = CommunityPage(),
    val memberships: CommunityPage = CommunityPage(),
    val selectedCommunity: String? = null,
    val detail: CommunityDetails? = null,
    val detailLoading: Boolean = false,
    val detailError: CommunityFailure? = null,
)

/** Account/screen-owned read state. Closing never disconnects voice or changes membership. */
class CommunityBrowser(parent: CoroutineScope, private val port: CommunityPort, private val pageSize: Int = 20) {
    init { require(pageSize in 1..50) }

    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val mutex = Mutex()
    private val output = MutableStateFlow(CommunityBrowserState())
    val state = output.asStateFlow()
    private val cancellationGuardian = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { output.value = CommunityBrowserState() }
            }
        }
    }
    private val catalog = RequestOwner()
    private val memberships = RequestOwner()
    private val details = RequestOwner()

    suspend fun search(query: String) = mutex.withLock {
        if (!lifetime.isActive) return@withLock
        require(query.length <= 80)
        output.value = output.value.copy(query = query)
        requestPage(public = true, more = false)
    }
    suspend fun refreshMemberships() = mutex.withLock {
        if (lifetime.isActive) requestPage(public = false, more = false)
    }
    suspend fun loadMoreCatalog() = mutex.withLock {
        if (lifetime.isActive) requestPage(public = true, more = true)
    }
    suspend fun loadMoreMemberships() = mutex.withLock {
        if (lifetime.isActive) requestPage(public = false, more = true)
    }
    suspend fun openCommunity(id: String) = mutex.withLock {
        if (!lifetime.isActive) return@withLock
        val generation = details.retire()
        output.value = output.value.copy(selectedCommunity = id, detail = null, detailLoading = true, detailError = null)
        details.job = scope.launch {
            val result = network { port.detail(id) }
            mutex.withLock {
                if (!lifetime.isActive || details.generation != generation || output.value.selectedCommunity != id) return@withLock
                output.value = when (result) {
                    is CommunityResult.Ready -> if (result.value.community.id == id) {
                        output.value.copy(detail = result.value, detailLoading = false)
                    } else output.value.copy(detailLoading = false, detailError = CommunityFailure.INVALID_RESPONSE)
                    is CommunityResult.Failed -> output.value.copy(detailLoading = false, detailError = result.reason)
                }
            }
        }
    }
    suspend fun clearSelection() = mutex.withLock {
        details.retire()
        output.value = output.value.copy(selectedCommunity = null, detail = null, detailLoading = false, detailError = null)
    }
    suspend fun close() = mutex.withLock {
        lifetime.cancel()
        catalog.retire(); memberships.retire(); details.retire()
        output.value = CommunityBrowserState()
    }

    // Invoked under mutex; network waits never hold state ownership.
    private fun requestPage(public: Boolean, more: Boolean) {
        val owner = if (public) catalog else memberships
        val previous = if (public) output.value.catalog else output.value.memberships
        if (more && (previous.loading || !previous.hasMore || previous.items == null)) return
        val generation = owner.retire()
        val query = output.value.query
        val accepted = if (more) requireNotNull(previous.items) else emptyList()
        val after = accepted.lastOrNull()?.id
        setPage(public, if (more) previous.copy(loading = true, error = null) else CommunityPage(loading = true))
        owner.job = scope.launch {
            val result = network {
                if (public) port.catalog(query, after, pageSize) else port.memberships(after, pageSize)
            }
            mutex.withLock {
                if (!lifetime.isActive || owner.generation != generation) return@withLock
                val page = when (result) {
                    is CommunityResult.Ready -> CommunityPage(items = accepted + result.value, hasMore = result.value.size == pageSize)
                    is CommunityResult.Failed -> if (more) previous.copy(loading = false, error = result.reason)
                        else CommunityPage(error = result.reason)
                }
                setPage(public, page)
            }
        }
    }
    private fun setPage(public: Boolean, page: CommunityPage) {
        output.value = if (public) output.value.copy(catalog = page) else output.value.copy(memberships = page)
    }
    private suspend fun <T> network(action: suspend () -> CommunityResult<T>): CommunityResult<T> = try {
        action()
    } catch (cancelled: CancellationException) {
        if (!currentCoroutineContext().isActive) throw cancelled
        CommunityResult.Failed(CommunityFailure.UNAVAILABLE)
    } catch (_: Exception) {
        CommunityResult.Failed(CommunityFailure.UNAVAILABLE)
    }
    private class RequestOwner {
        var generation = 0L
        var job: Job? = null
        fun retire(): Long {
            check(generation < Long.MAX_VALUE)
            generation++
            job?.cancel(); job = null
            return generation
        }
    }
}
