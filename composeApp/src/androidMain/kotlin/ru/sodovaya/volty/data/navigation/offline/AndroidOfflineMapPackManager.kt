package ru.sodovaya.volty.data.navigation.offline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val packs = linkedMapOf<OfflineMapPackKey, SdkOfflinePack>()
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
                    if (state(definition.key) !is OfflineMapPackState.Ready &&
                        state(definition.key) !is OfflineMapPackState.Preparing
                    ) {
                        update(definition.key, OfflineMapPackState.Preparing)
                        existing.resume()
                    }
                    return
                }

                update(definition.key, OfflineMapPackState.Preparing)
                client.setTileLimit(definition.tileLimit.toLong())
                val created = client.createPack(definition.toSdkDefinition(pixelRatio), store.encode(definition))
                packs[definition.key] = created
                observe(definition.key, created)
                created.resume()
            } catch (error: Throwable) {
                update(definition.key, OfflineMapPackState.Failed(error.failureCode()))
            }
        }
    }

    override suspend fun pause(key: OfflineMapPackKey) = operate(key) { it.pause() }

    override suspend fun resume(key: OfflineMapPackKey) = operate(key) { pack ->
        update(key, OfflineMapPackState.Preparing)
        pack.resume()
    }

    override suspend fun invalidate(key: OfflineMapPackKey) = operate(key) { pack ->
        pack.invalidate()
        update(key, OfflineMapPackState.Preparing)
    }

    override suspend fun delete(key: OfflineMapPackKey) {
        operationMutex.withLock {
            try {
                loadPacksLocked()
                val pack = packs[key] ?: return
                pack.delete()
                packs.remove(key)
                removeState(key)
            } catch (error: Throwable) {
                update(key, OfflineMapPackState.Failed(error.failureCode()))
            }
        }
    }

    private suspend fun operate(key: OfflineMapPackKey, action: suspend (SdkOfflinePack) -> Unit) {
        operationMutex.withLock {
            try {
                loadPacksLocked()
                packs[key]?.let { action(it) }
            } catch (error: Throwable) {
                update(key, OfflineMapPackState.Failed(error.failureCode()))
            }
        }
    }

    private suspend fun loadPacksLocked() {
        if (loaded) return
        client.listPacks().sortedBy(SdkOfflinePack::id).forEach { pack ->
            val metadata = store.decode(pack.metadata) ?: return@forEach
            if (packs.putIfAbsent(metadata.key, pack) == null) {
                observe(metadata.key, pack)
                pack.status()?.let { onStatus(metadata.key, pack, it) }
                    ?: update(metadata.key, OfflineMapPackState.Preparing)
            }
        }
        loaded = true
    }

    private suspend fun observe(key: OfflineMapPackKey, pack: SdkOfflinePack) {
        pack.setObserver(object : SdkOfflinePackObserver {
            override fun onStatus(status: SdkOfflinePackStatus) = onStatus(key, pack, status)

            override fun onError(reason: String, message: String) {
                fail(key, pack, "$reason $message".failureCode())
            }

            override fun onTileLimitExceeded() {
                fail(key, pack, TILE_LIMIT_FAILURE)
            }
        })
    }

    private fun onStatus(key: OfflineMapPackKey, pack: SdkOfflinePack, status: SdkOfflinePackStatus) {
        if (state(key) is OfflineMapPackState.Failed) return
        if (status.isComplete) {
            update(key, OfflineMapPackState.Ready)
            scope.launch { runCatching { pack.pause() } }
        } else if (status.isActive) {
            update(
                key,
                OfflineMapPackState.Downloading(
                    completedResources = status.completedResources,
                    completedTiles = status.completedTiles,
                    totalResources = status.requiredResources.takeIf { status.requiredResourcesIsPrecise },
                ),
            )
        } else {
            update(key, OfflineMapPackState.Preparing)
        }
    }

    private fun fail(key: OfflineMapPackKey, pack: SdkOfflinePack, code: String) {
        update(key, OfflineMapPackState.Failed(code))
        scope.launch { runCatching { pack.pause() } }
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
    suspend fun setObserver(observer: SdkOfflinePackObserver)
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

    override suspend fun setObserver(observer: SdkOfflinePackObserver) = withContext(mainDispatcher) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                observer.onStatus(status.toSdkStatus())
            }

            override fun onError(error: OfflineRegionError) {
                observer.onError(error.reason, error.message)
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                observer.onTileLimitExceeded()
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
