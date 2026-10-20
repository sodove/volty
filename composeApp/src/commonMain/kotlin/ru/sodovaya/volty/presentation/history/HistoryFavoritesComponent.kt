package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.domain.history.*

enum class HistoryFavoritesError { STORAGE, INVALID_NAME, INVALID_RANGE, SOURCE_UNAVAILABLE, SOURCE_CHANGED, CONFLICT }
data class HistoryFavoritesState(
    val available: Boolean = false,
    val items: List<HistoryFavorite> = emptyList(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val next: HistoryCursor? = null,
    val error: HistoryFavoritesError? = null,
    val savedId: String? = null,
    val deletedId: String? = null,
)

class HistoryFavoritesComponent(context: ComponentContext, parentScope: CoroutineScope,
    private val store: HistoryFavoriteRepository?) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(HistoryFavoritesState(available = store != null))
    val state = mutableState.asStateFlow()
    init { context.lifecycle.doOnDestroy { close() } }
    fun refresh() = request {
        val page = page()
        currentCoroutineContext().ensureActive()
        mutableState.value = mutableState.value.copy(items = page.items.toList(), loaded = true, next = page.next)
    }
    fun loadOlder() {
        val cursor = state.value.next ?: return
        request {
            val page = page(cursor)
            currentCoroutineContext().ensureActive()
            mutableState.value = mutableState.value.copy(
                items = (state.value.items + page.items).distinctBy { it.id }, next = page.next)
        }
    }
    fun save(id: String, source: HistoryFavoriteSource, range: HistoryRange, name: String, revision: Long? = null) = request {
        val saved = save(id, source, range, name, revision)
        currentCoroutineContext().ensureActive()
        if (lifetime.isActive) mutableState.value = mutableState.value.copy(
            items = (state.value.items.filterNot { it.id == saved.id } + saved)
                .sortedWith(compareByDescending<HistoryFavorite> { it.createdEpochMillis }.thenByDescending { it.id }), savedId = saved.id)
    }
    fun delete(favorite: HistoryFavorite) = request {
        delete(favorite.id, favorite.revision)
        currentCoroutineContext().ensureActive()
        if (lifetime.isActive) mutableState.value = mutableState.value.copy(items = state.value.items.filterNot { it.id == favorite.id }, deletedId = favorite.id)
    }
    private fun request(block: suspend HistoryFavoriteRepository.() -> Unit) {
        val target = store ?: return
        if (!lifetime.isActive || state.value.busy) return
        mutableState.value = state.value.copy(busy = true, error = null, savedId = null, deletedId = null)
        if (!lifetime.isActive) return
        scope.launch {
            try {
                target.block()
                currentCoroutineContext().ensureActive()
                if (lifetime.isActive) mutableState.value = state.value.copy(busy = false)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (isActive) mutableState.value = state.value.copy(busy = false, error =
                    (failure as? HistoryFavoriteException)?.reason?.let { HistoryFavoritesError.valueOf(it.name) }
                        ?: HistoryFavoritesError.STORAGE)
            }
        }
    }
    fun close() { lifetime.cancel() }
}
