package ru.sodovaya.volty.data.navigation.offline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackDefinition
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackKey
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackState
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface OfflineMapPackManager {
    val states: StateFlow<List<OfflineMapPackState>>
    suspend fun prepare(definition: OfflineMapPackDefinition)
    suspend fun pause(key: OfflineMapPackKey)
    suspend fun resume(key: OfflineMapPackKey)
    suspend fun invalidate(key: OfflineMapPackKey)
    suspend fun delete(key: OfflineMapPackKey)
}

class AndroidOfflineMapPackManager internal constructor(
    private val client: SdkOfflinePackClient,
    private val store: AndroidOfflineMapPackStore,
    private val scope: CoroutineScope,
    private val pixelRatio: Float = 1f,
) : OfflineMapPackManager {
    constructor(
        offlineManager: OfflineManager,
        store: AndroidOfflineMapPackStore,
        scope: CoroutineScope,
        pixelRatio: Float,
    ) : this(
        client = MapLibreSdkOfflinePackClient(offlineManager),
        store = store,
        scope = scope,
        pixelRatio = pixelRatio,
    )

    private val operationMutex = Mutex()
    private val callbackQueueLock = Any()
    private val callbackQueue = ArrayDeque<QueuedCallback>()
    private var callbackDrainScheduled = false
    private val packs = linkedMapOf<OfflineMapPackKey, PackEntry>()
    private val stateByKey = linkedMapOf<OfflineMapPackKey, OfflineMapPackState>()
    private val _states = MutableStateFlow<List<OfflineMapPackState>>(emptyList())
    override val states: StateFlow<List<OfflineMapPackState>> = _states
    private var loaded = false

    init {
        scope.launch {
            operationMutex.withLock {
                runCatching { loadPacksLocked() }
            }
        }
    }

    override suspend fun prepare(definition: OfflineMapPackDefinition) {
        operationMutex.withLock {
            try {
                loadPacksLocked()
                val existing = packs[definition.key]
                if (existing != null) {
                    if (!existing.complete && !existing.downloadActive) {
                        update(definition.key, OfflineMapPackState.Preparing)
                        attachObserverLocked(existing)
                        client.setTileLimit(existing.tileLimit.toLong())
                        existing.pack.resume()
                        existing.downloadActive = true
                    }
                    return
                }

                update(definition.key, OfflineMapPackState.Preparing)
                client.setTileLimit(definition.tileLimit.toLong())
                val created = client.createPack(definition.toSdkDefinition(pixelRatio), store.encode(definition))
                val entry = PackEntry(definition.key, created, definition.tileLimit)
                packs[definition.key] = entry
                attachObserverLocked(entry)
                created.resume()
                entry.downloadActive = true
            } catch (error: Throwable) {
                update(definition.key, OfflineMapPackState.Failed(error.failureCode()))
            }
        }
    }

    override suspend fun pause(key: OfflineMapPackKey) = operate(key) { entry ->
        entry.pack.pause()
        entry.downloadActive = false
        rotateObserverLocked(entry)
        val current = state(key)
        if (current !is OfflineMapPackState.Ready && current !is OfflineMapPackState.Failed) {
            update(key, OfflineMapPackState.Preparing)
        }
    }

    override suspend fun resume(key: OfflineMapPackKey) = operate(key) { entry ->
        if (entry.complete) return@operate
        update(key, OfflineMapPackState.Preparing)
        attachObserverLocked(entry)
        client.setTileLimit(entry.tileLimit.toLong())
        entry.pack.resume()
        entry.downloadActive = true
    }

    override suspend fun invalidate(key: OfflineMapPackKey) = operate(key) { entry ->
        entry.pack.invalidate()
        entry.complete = false
        entry.downloadActive = false
        update(key, OfflineMapPackState.Preparing)
    }

    override suspend fun delete(key: OfflineMapPackKey) {
        operationMutex.withLock {
            loadPacksLocked()
            val entry = packs[key] ?: return@withLock
            try {
                entry.pack.setObserver(null)
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { attachObserverLocked(entry) }
                throw cancelled
            }
            // From this explicit destructive boundary onward, native callback reconciliation wins
            // over cancellation so the retained entry cannot disagree with MapLibre.
            entry.lifecycle = PackLifecycle.DELETING
            entry.observerGeneration += 1
            withContext(NonCancellable) {
                try {
                    entry.pack.delete()
                    if (packs[key] === entry) {
                        packs.remove(key)
                        removeState(key)
                    }
                } catch (error: Throwable) {
                    if (packs[key] === entry) {
                        entry.lifecycle = PackLifecycle.ACTIVE
                        attachObserverLocked(entry)
                        update(key, OfflineMapPackState.Failed(error.failureCode()))
                    }
                }
            }
        }
    }

    private suspend fun operate(key: OfflineMapPackKey, action: suspend (PackEntry) -> Unit) {
        operationMutex.withLock {
            try {
                loadPacksLocked()
                packs[key]?.takeIf { it.lifecycle == PackLifecycle.ACTIVE }?.let { action(it) }
            } catch (error: Throwable) {
                update(key, OfflineMapPackState.Failed(error.failureCode()))
            }
        }
    }

    private suspend fun loadPacksLocked() {
        if (loaded) return
        client.listPacks().sortedBy(SdkOfflinePack::id).forEach { pack ->
            val metadata = store.decode(pack.metadata) ?: return@forEach
            if (metadata.key !in packs) {
                val entry = PackEntry(metadata.key, pack, metadata.tileLimit)
                packs[metadata.key] = entry
                attachObserverLocked(entry)
                val status = pack.status()
                if (status == null) {
                    update(metadata.key, OfflineMapPackState.Preparing)
                } else {
                    applyStatusLocked(entry, status)
                }
            }
        }
        loaded = true
    }

    private suspend fun attachObserverLocked(entry: PackEntry) {
        entry.observerGeneration += 1
        val generation = entry.observerGeneration
        entry.pack.setObserver(object : SdkOfflinePackObserver {
            override fun onStatus(status: SdkOfflinePackStatus) {
                dispatchCallback(entry, generation) { current ->
                    applyStatusLocked(current, status)
                }
            }

            override fun onError(reason: String, message: String) {
                dispatchCallback(entry, generation) { current ->
                    failLocked(current, "$reason $message".failureCode())
                }
            }

            override fun onTileLimitExceeded() {
                dispatchCallback(entry, generation) { current ->
                    failLocked(current, TILE_LIMIT_FAILURE)
                }
            }
        })
    }

    private fun dispatchCallback(
        entry: PackEntry,
        generation: Long,
        action: suspend (PackEntry) -> Unit,
    ) {
        val shouldScheduleDrain = synchronized(callbackQueueLock) {
            callbackQueue.addLast(QueuedCallback(entry, generation, action))
            if (callbackDrainScheduled) {
                false
            } else {
                callbackDrainScheduled = true
                true
            }
        }
        if (shouldScheduleDrain) scope.launch { drainCallbacks() }
    }

    private suspend fun drainCallbacks() {
        while (true) {
            val callback = synchronized(callbackQueueLock) {
                if (callbackQueue.isEmpty()) {
                    callbackDrainScheduled = false
                    null
                } else {
                    callbackQueue.removeFirst()
                }
            } ?: return
            operationMutex.withLock {
                val current = packs[callback.entry.key]
                if (current === callback.entry &&
                    current.lifecycle == PackLifecycle.ACTIVE &&
                    current.observerGeneration == callback.generation
                ) {
                    callback.action(current)
                }
            }
        }
    }

    private suspend fun rotateObserverLocked(entry: PackEntry) {
        if (entry.lifecycle == PackLifecycle.ACTIVE) attachObserverLocked(entry)
    }

    private fun publishStatusLocked(entry: PackEntry, status: SdkOfflinePackStatus) {
        if (status.isComplete) {
            update(entry.key, OfflineMapPackState.Ready)
        } else if (status.isActive) {
            update(
                entry.key,
                OfflineMapPackState.Downloading(
                    completedResources = status.completedResources,
                    completedTiles = status.completedTiles,
                    totalResources = status.requiredResources.takeIf { status.requiredResourcesIsPrecise },
                ),
            )
        } else {
            update(entry.key, OfflineMapPackState.Preparing)
        }
    }

    private suspend fun applyStatusLocked(entry: PackEntry, status: SdkOfflinePackStatus) {
        if (state(entry.key) is OfflineMapPackState.Failed) return
        entry.downloadActive = status.isActive
        entry.complete = status.isComplete
        publishStatusLocked(entry, status)
        if (status.isComplete && status.isActive) {
            entry.pack.pause()
            entry.downloadActive = false
            rotateObserverLocked(entry)
        }
    }

    private suspend fun failLocked(entry: PackEntry, code: String) {
        update(entry.key, OfflineMapPackState.Failed(code))
        if (entry.downloadActive) {
            runCatching { entry.pack.pause() }
            entry.downloadActive = false
            rotateObserverLocked(entry)
        }
    }

    private fun state(key: OfflineMapPackKey): OfflineMapPackState? = synchronized(stateByKey) {
        stateByKey[key]
    }

    private fun update(key: OfflineMapPackKey, state: OfflineMapPackState) = synchronized(stateByKey) {
        stateByKey[key] = state
        publishStatesLocked()
    }

    private fun removeState(key: OfflineMapPackKey) = synchronized(stateByKey) {
        stateByKey.remove(key)
        publishStatesLocked()
    }

    private fun publishStatesLocked() {
        _states.value = stateByKey.entries
            .sortedWith(compareBy({ it.key.regionId }, { it.key.style.name }, { it.key.ofmStyleUrl }))
            .map { it.value }
    }

    private fun OfflineMapPackDefinition.toSdkDefinition(pixelRatio: Float) =
        OfflineTilePyramidRegionDefinition(
            key.ofmStyleUrl,
            LatLngBounds.Builder()
                .include(LatLng(bounds.south, bounds.west))
                .include(LatLng(bounds.north, bounds.east))
                .build(),
            minZoom.toDouble(),
            maxZoom.toDouble(),
            pixelRatio,
        )

    private fun Throwable.failureCode(): String = message.orEmpty().failureCode()

    private fun String.failureCode(): String {
        val normalized = lowercase()
        return when {
            "tile_limit" in normalized || ("tile" in normalized && "limit" in normalized) -> TILE_LIMIT_FAILURE
            "connection" in normalized -> "connection"
            else -> "sdk_error"
        }
    }

    private companion object {
        const val TILE_LIMIT_FAILURE = "tile_limit"
    }

    private data class PackEntry(
        val key: OfflineMapPackKey,
        val pack: SdkOfflinePack,
        val tileLimit: Int,
        var downloadActive: Boolean = false,
        var complete: Boolean = false,
        var observerGeneration: Long = 0,
        var lifecycle: PackLifecycle = PackLifecycle.ACTIVE,
    )

    private data class QueuedCallback(
        val entry: PackEntry,
        val generation: Long,
        val action: suspend (PackEntry) -> Unit,
    )

    private enum class PackLifecycle {
        ACTIVE,
        DELETING,
    }
}

internal data class SdkOfflinePackStatus(
    val isActive: Boolean,
    val isComplete: Boolean,
    val completedResources: Long,
    val completedTiles: Long,
    val requiredResources: Long,
    val requiredResourcesIsPrecise: Boolean,
)

internal interface SdkOfflinePackObserver {
    fun onStatus(status: SdkOfflinePackStatus)
    fun onError(reason: String, message: String)
    fun onTileLimitExceeded()
}

internal interface SdkOfflinePack {
    val id: Long
    val metadata: ByteArray
    suspend fun setObserver(observer: SdkOfflinePackObserver?)
    suspend fun status(): SdkOfflinePackStatus?
    suspend fun resume()
    suspend fun pause()
    suspend fun invalidate()
    suspend fun delete()
}

internal interface SdkOfflinePackClient {
    suspend fun listPacks(): List<SdkOfflinePack>
    suspend fun setTileLimit(limit: Long)
    suspend fun createPack(
        definition: OfflineTilePyramidRegionDefinition,
        metadata: ByteArray,
    ): SdkOfflinePack
}

private class MapLibreSdkOfflinePackClient(
    private val manager: OfflineManager,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : SdkOfflinePackClient {
    override suspend fun listPacks(): List<SdkOfflinePack> = withContext(mainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    if (continuation.isActive) continuation.resume(offlineRegions.orEmpty().map(::MapLibreSdkOfflinePack))
                }

                override fun onError(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
            })
        }
    }

    override suspend fun setTileLimit(limit: Long) = withContext(mainDispatcher) {
        manager.setOfflineMapboxTileCountLimit(limit)
    }

    override suspend fun createPack(
        definition: OfflineTilePyramidRegionDefinition,
        metadata: ByteArray,
    ): SdkOfflinePack = withContext(mainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    if (continuation.isActive) continuation.resume(MapLibreSdkOfflinePack(offlineRegion))
                }

                override fun onError(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
            })
        }
    }
}

private class MapLibreSdkOfflinePack(
    private val region: OfflineRegion,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : SdkOfflinePack {
    override val id: Long get() = region.id
    override val metadata: ByteArray get() = region.metadata

    override suspend fun setObserver(observer: SdkOfflinePackObserver?) = withContext(mainDispatcher) {
        region.setObserver(observer?.let {
            object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    it.onStatus(status.toSdkStatus())
                }

                override fun onError(error: OfflineRegionError) {
                    it.onError(error.reason, error.message)
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    it.onTileLimitExceeded()
                }
            }
        })
    }

    override suspend fun status(): SdkOfflinePackStatus? = withContext(mainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    if (continuation.isActive) continuation.resume(status?.toSdkStatus())
                }

                override fun onError(error: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException(error ?: "offline status failed"))
                    }
                }
            })
        }
    }

    override suspend fun resume() = withContext(mainDispatcher) {
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    override suspend fun pause() = withContext(mainDispatcher) {
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
    }

    override suspend fun invalidate() = withContext(mainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            region.invalidate(object : OfflineRegion.OfflineRegionInvalidateCallback {
                override fun onInvalidate() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
            })
        }
    }

    override suspend fun delete() = withContext(mainDispatcher) {
        suspendCancellableCoroutine { continuation ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
            })
        }
    }

    private fun OfflineRegionStatus.toSdkStatus() = SdkOfflinePackStatus(
        isActive = downloadState == OfflineRegion.STATE_ACTIVE,
        isComplete = isComplete,
        completedResources = completedResourceCount,
        completedTiles = completedTileCount,
        requiredResources = requiredResourceCount,
        requiredResourcesIsPrecise = isRequiredResourceCountPrecise,
    )
}
