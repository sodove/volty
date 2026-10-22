package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HistoryPreviewImageKey(val rideId: String, val revision: Long, val width: Int, val height: Int,
    val darkTheme: Boolean, val routeColor: Int, val segments: List<HistoryMapSegment>,
    val styleRevision: String = "thumbnail-v2:${ru.sodovaya.volty.presentation.map.MapRenderSourcePolicy.styleUrl(darkTheme)}") {
    init {
        require(rideId.isNotBlank() && revision >= 0 && width in 1..512 && height in 1..512 && styleRevision.isNotBlank())
        require(segments.sumOf { it.fixes.size } <= 128)
    }
}

/** Encoded images stay bounded and cannot be mutated through published state. */
class HistoryPreviewImage(bytes: ByteArray) {
    private val encoded = bytes.copyOf()
    val byteCount: Int get() = encoded.size
    init { require(encoded.size in 1..1_048_576) }
    fun copyBytes(): ByteArray = encoded.copyOf()
}
fun interface HistoryPreviewImageLoader {
    suspend fun load(key: HistoryPreviewImageKey): HistoryPreviewImage?
    suspend fun reconcileDeletedRides(): Boolean = true
}
data class HistoryPreviewImagesState(val images: Map<String, HistoryPreviewImage> = emptyMap(),
    val loading: Set<String> = emptySet(), val unavailable: Set<String> = emptySet(),
    val renderKeys: Map<String, HistoryPreviewImageKey> = emptyMap())

/** Visible rows only; a missing background never removes their route-only fallback. */
class HistoryPreviewImagesComponent(componentContext: ComponentContext, parentScope: CoroutineScope,
    private val loader: HistoryPreviewImageLoader, private val maxConcurrentLoads: Int = 2,
    private val cacheEntries: Int = 16, private val cacheBytes: Int = 8 * 1024 * 1024) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(HistoryPreviewImagesState())
    val state = mutableState.asStateFlow()
    private val permits = Semaphore(maxConcurrentLoads)
    private val requests = mutableMapOf<String, Request>()
    private val cache = linkedMapOf<HistoryPreviewImageKey, HistoryPreviewImage>()
    private var visible = emptyMap<String, HistoryPreviewImageKey>()
    private var transition = Any()
    init {
        require(maxConcurrentLoads in 1..2 && cacheEntries in 1..16 && cacheBytes in 1..8*1024*1024)
        componentContext.lifecycle.doOnDestroy { close() }
    }
    fun setVisible(keys: List<HistoryPreviewImageKey>) {
        if (!lifetime.isActive) return
        require(keys.size <= 20 && keys.map { it.rideId }.distinct().size == keys.size)
        val captured = Any(); transition = captured
        val oldVisible = visible; val old = mutableState.value
        visible = keys.associate { key -> key.rideId to key.copy(segments = key.segments.map { it.copy(fixes = it.fixes.toList()) }) }
        requests.values.toList().filter { visible[it.key.rideId] != it.key }.forEach(::retire)
        val images = linkedMapOf<String, HistoryPreviewImage>()
        val loading = mutableSetOf<String>(); val unavailable = mutableSetOf<String>()
        for ((id,key) in visible) {
            if (key.segments.all { it.fixes.isEmpty() }) continue
            val unchanged = oldVisible[id] == key
            val image = cache.remove(key)?.also { cache[key] = it } ?: old.images[id]?.takeIf { unchanged }
            when {
                image != null -> images[id] = image
                unchanged && id in old.unavailable -> unavailable += id
                else -> { if (requests[id]?.key != key) prepare(key); loading += id }
            }
        }
        if (!lifetime.isActive || transition !== captured) return
        mutableState.value = HistoryPreviewImagesState(images.toMap(),loading.toSet(),unavailable.toSet(),visible.toMap())
        // Publishing can synchronously change the viewport. Lazy jobs let that
        // transition retire an old request before it enters native rendering.
        for (request in requests.values.toList()) {
            if (!lifetime.isActive || transition !== captured) return
            if (current(request)) request.job.start()
        }
    }
    private fun prepare(key: HistoryPreviewImageKey) {
        val request = Request(key)
        request.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val image = permits.withPermit {
                    if (!current(request)) return@launch
                    loader.load(key)
                }
                currentCoroutineContext().ensureActive()
                if (!current(request)) return@launch
                if (image != null) put(key,image)
                val current = mutableState.value
                mutableState.value = current.copy(images = if (image == null) current.images else current.images + (key.rideId to image),
                    loading = current.loading - key.rideId,
                    unavailable = if (image == null) current.unavailable + key.rideId else current.unavailable - key.rideId)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (isActive && current(request)) mutableState.value = mutableState.value.copy(
                    loading = mutableState.value.loading - key.rideId, unavailable = mutableState.value.unavailable + key.rideId)
            } finally { if (requests[key.rideId] === request) requests.remove(key.rideId) }
        }
        requests[key.rideId] = request
    }
    private fun current(request: Request) = lifetime.isActive && requests[request.key.rideId] === request && visible[request.key.rideId] == request.key
    private fun retire(request: Request) { requests.remove(request.key.rideId); request.job.cancel() }
    private fun put(key: HistoryPreviewImageKey, image: HistoryPreviewImage) {
        if (image.byteCount > cacheBytes) return
        cache.remove(key); cache[key] = image
        while (cache.size > cacheEntries || cache.values.sumOf { it.byteCount } > cacheBytes) cache.remove(cache.keys.first())
    }
    fun forget(rideId: String) {
        if (!lifetime.isActive) return
        cache.keys.filter { it.rideId == rideId }.forEach(cache::remove)
        setVisible(visible.values.filterNot { it.rideId == rideId })
    }
    fun close() {
        transition = Any(); lifetime.cancel()
        requests.values.toList().forEach(::retire); visible = emptyMap(); cache.clear()
    }
    private class Request(val key: HistoryPreviewImageKey) { lateinit var job: Job }
}
