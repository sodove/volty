package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.domain.history.*

class HistoryMediaPickerToken internal constructor(internal val favoriteId: String, internal val generation: Any)
data class HistoryMediaOpen(val id: String, val uri: String, val mime: String)
data class HistoryMediaState(
    val available: Boolean = false,
    val favoriteId: String? = null,
    val items: List<HistoryMediaRecord> = emptyList(),
    val previewUris: Map<String, String> = emptyMap(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val picker: HistoryMediaPickerToken? = null,
    val error: HistoryMediaFailure? = null,
    val recovery: HistoryMediaRecovery = HistoryMediaRecovery(),
    val open: HistoryMediaOpen? = null,
)

class HistoryMediaComponent(context: ComponentContext, parentScope: CoroutineScope, private val repository: HistoryMediaRepository?) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(HistoryMediaState(available = repository != null))
    val state = mutableState.asStateFlow()
    private var generation = Any()
    private var request = Any()
    private var job: Job? = null
    init { context.lifecycle.doOnDestroy { close() } }
    fun selectFavorite(id: String?) {
        if (!lifetime.isActive || id == state.value.favoriteId) return
        require(id == null || id.isNotBlank())
        generation = Any(); job?.cancel(); job = null
        mutableState.value = HistoryMediaState(available = repository != null, favoriteId = id)
    }
    fun refresh() = operation { id ->
        val listing = list(id)
        check(listing.items.all { it.favoriteId == id && it.status == HistoryMediaStatus.READY })
        val ids = listing.items.map { it.id }.toSet()
        val previews = listing.previewUris.filter { (id, uri) -> id in ids && uri.startsWith("content://") }
        return@operation { state -> state.copy(items = listing.items.toList(), previewUris = previews, loaded = true, recovery = listing.recovery) }
    }
    fun beginPick(): HistoryMediaPickerToken? {
        val current = state.value
        val id = current.favoriteId ?: return null
        if (!lifetime.isActive || repository == null || current.busy || current.picker != null) return null
        val token = HistoryMediaPickerToken(id, generation)
        mutableState.value = current.copy(picker = token, error = null, open = null)
        return token.takeIf { lifetime.isActive && state.value.picker === it && generation === it.generation }
    }
    fun picked(token: HistoryMediaPickerToken, uri: String?, mime: String?) {
        if (!lifetime.isActive || generation !== token.generation || state.value.picker !== token) return
        mutableState.value = state.value.copy(picker = null)
        if (uri == null) return
        if (!uri.startsWith("content://") || mime.isNullOrBlank()) {
            mutableState.value = state.value.copy(error = if (mime.isNullOrBlank()) HistoryMediaFailure.INVALID_MEDIA else HistoryMediaFailure.ACCESS_DENIED)
            return
        }
        operation { id ->
            val saved = importItem(HistoryMediaPick(id, uri, mime))
            check(saved.favoriteId == id && saved.status == HistoryMediaStatus.READY)
            return@operation { state -> state.copy(loaded = false, items = (state.items.filterNot { it.id == saved.id } + saved)
                .sortedWith(compareByDescending<HistoryMediaRecord> { it.createdEpochMillis }.thenByDescending { it.id })) }
        }
    }
    fun delete(item: HistoryMediaRecord) {
        if (!contains(item)) return
        operation {
            delete(item.id)
            return@operation { state -> state.copy(items = state.items.filterNot { it.id == item.id }, previewUris = state.previewUris - item.id) }
        }
    }
    fun open(item: HistoryMediaRecord) {
        if (!contains(item)) return
        operation {
            val uri = readableUri(item.id)
            check(uri.startsWith("content://"))
            return@operation { state -> state.copy(open = HistoryMediaOpen(item.id, uri, item.mime)) }
        }
    }
    fun consumeOpen() { if (lifetime.isActive) mutableState.value = state.value.copy(open = null) }
    fun cancelImport() {
        val pending = job ?: return
        // A scheduled launch cancelled before entry never runs its finally.
        // Retire publication now; repository cleanup may finish independently.
        request = Any(); job = null
        pending.cancel()
        if (lifetime.isActive) mutableState.value = state.value.copy(busy = false)
    }
    fun close() { lifetime.cancel() }
    private fun contains(item: HistoryMediaRecord) = lifetime.isActive && item.favoriteId == state.value.favoriteId &&
        state.value.items.any { it.id == item.id }

    private fun operation(block: suspend HistoryMediaRepository.(String) -> ((HistoryMediaState) -> HistoryMediaState)) {
        val target = repository ?: return
        val id = state.value.favoriteId ?: return
        if (!lifetime.isActive || state.value.busy || state.value.picker != null) return
        val selected = generation
        val captured = Any(); request = captured
        mutableState.value = state.value.copy(busy = true, error = null, open = null, recovery = HistoryMediaRecovery())
        if (!matches(selected, id) || request !== captured) return
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val transform = target.block(id)
                currentCoroutineContext().ensureActive()
                if (matches(selected, id) && request === captured) mutableState.value = transform(state.value)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (matches(selected, id) && request === captured)
                    mutableState.value = state.value.copy(error = (error as? HistoryMediaException)?.reason ?: HistoryMediaFailure.STORAGE)
            } finally {
                if (matches(selected, id) && request === captured) { job = null; mutableState.value = state.value.copy(busy = false) }
            }
        }
        job = task; task.start()
    }
    private fun matches(selected: Any, id: String) = lifetime.isActive && generation === selected && state.value.favoriteId == id
}
