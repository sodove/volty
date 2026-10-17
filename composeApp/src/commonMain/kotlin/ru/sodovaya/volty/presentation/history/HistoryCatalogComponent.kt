package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.history.*

data class HistoryCatalogState(
    val rides: List<HistoryRide> = emptyList(),
    val nextCursor: HistoryCursor? = null,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val loadingOlder: Boolean = false,
    val truncated: Boolean = false,
    val error: HistoryReadError? = null,
) { val hasMore: Boolean get() = nextCursor != null }

/** Read-only catalog ownership. Commands share the parent's UI dispatcher;
 * pages load explicitly and do not preload route/metric/detail data.
 */
class HistoryCatalogComponent(
    componentContext: ComponentContext,
    parentScope: CoroutineScope,
    private val repository: HistoryReadRepository,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(HistoryCatalogState())
    val state = mutableState.asStateFlow()
    private var request: Any? = null
    private var job: Job? = null

    init { componentContext.lifecycle.doOnDestroy { close() } }

    fun refresh() {
        if (lifetime.isActive) readPage(append = false, before = null)
    }

    fun loadOlder() {
        val current = mutableState.value
        if (!lifetime.isActive || !current.loaded || current.refreshing || current.loadingOlder) return
        val cursor = current.nextCursor ?: return
        readPage(append = true, before = cursor)
    }

    private fun readPage(append: Boolean, before: HistoryCursor?) {
        val token = Any()
        request = token
        job?.cancel()
        job = null
        mutableState.value = mutableState.value.copy(refreshing = !append, loadingOlder = append, error = null)
        // Publication may synchronously refresh/close through a UI observer.
        if (!lifetime.isActive || request !== token) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = repository.rides(before)
                currentCoroutineContext().ensureActive()
                if (!lifetime.isActive || request !== token) return@launch
                require(page.next == null || page.next != before) { "Catalog cursor did not advance" }
                val rows = linkedMapOf<String, HistoryRide>()
                if (append) mutableState.value.rides.forEach { rows[it.id] = it }
                page.items.forEach { rows[it.id] = it }
                val current = mutableState.value
                mutableState.value = current.copy(rides = rows.values.toList(), nextCursor = page.next,
                    loaded = true, refreshing = false, loadingOlder = false,
                    truncated = page.truncated || (append && current.truncated), error = null)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (isActive && request === token) mutableState.value = mutableState.value.copy(
                    refreshing = false, loadingOlder = false, error = HistoryReadError.STORAGE)
            } finally {
                if (request === token) { request = null; job = null }
            }
        }
        job = next
        next.start()
    }

    fun close() {
        request = null
        job?.cancel()
        job = null
        lifetime.cancel()
    }
}
