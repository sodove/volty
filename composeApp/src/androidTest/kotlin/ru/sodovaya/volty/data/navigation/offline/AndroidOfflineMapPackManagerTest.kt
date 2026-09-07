package ru.sodovaya.volty.data.navigation.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackDefinition
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackKey
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackState
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapStyleVariant
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidOfflineMapPackManagerTest {
    @Test
    fun testDefinitionAndRepeatedPrepareReuseOneMetadataKey() = runBlocking {
        val client = FakeOfflinePackClient()
        val store = AndroidOfflineMapPackStore()
        val manager = AndroidOfflineMapPackManager(
            client = client,
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        manager.prepare(DEFINITION)
        manager.prepare(DEFINITION)

        assertEquals(1, client.createCount)
        assertEquals(30_000L, client.tileLimit)
        val sdkDefinition = client.createdDefinition!!
        assertEquals("https://tiles.openfreemap.org/styles/bright", sdkDefinition.styleURL)
        assertEquals(56.30, sdkDefinition.bounds!!.latitudeSouth)
        assertEquals(59.55, sdkDefinition.bounds!!.longitudeWest)
        assertEquals(57.25, sdkDefinition.bounds!!.latitudeNorth)
        assertEquals(61.45, sdkDefinition.bounds!!.longitudeEast)
        assertEquals(5.0, sdkDefinition.minZoom)
        assertEquals(13.0, sdkDefinition.maxZoom)

        val metadata = store.decode(client.createdMetadata!!)!!
        assertEquals("ekb", metadata.regionId)
        assertEquals(OfflineMapStyleVariant.BRIGHT, metadata.style)
        assertEquals("https://tiles.openfreemap.org/styles/bright", metadata.styleUrl)
        assertEquals(OfflineRegionBounds(56.30, 59.55, 57.25, 61.45), metadata.bounds)
        assertEquals(5, metadata.minZoom)
        assertEquals(13, metadata.maxZoom)
        assertNull(metadata.ofmRevision)
    }

    @Test
    fun testActiveThenCompleteCallbackBecomesReady() = runBlocking {
        val client = FakeOfflinePackClient()
        val manager = AndroidOfflineMapPackManager(
            client = client,
            store = AndroidOfflineMapPackStore(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        manager.prepare(DEFINITION)

        client.pack.emitStatus(
            SdkOfflinePackStatus(
                isActive = true,
                isComplete = false,
                completedResources = 11,
                completedTiles = 8,
                requiredResources = 20,
                requiredResourcesIsPrecise = true,
            ),
        )
        assertEquals(OfflineMapPackState.Downloading(11, 8, 20), manager.states.value.single())

        client.pack.emitStatus(
            SdkOfflinePackStatus(
                isActive = true,
                isComplete = true,
                completedResources = 20,
                completedTiles = 17,
                requiredResources = 20,
                requiredResourcesIsPrecise = true,
            ),
        )
        assertEquals(OfflineMapPackState.Ready, manager.states.value.single())
        assertEquals(1, client.pack.pauseCount)
    }

    @Test
    fun testTileLimitAndTileLimitErrorStayFailedInsteadOfBecomingReady() = runBlocking {
        val client = FakeOfflinePackClient()
        val manager = AndroidOfflineMapPackManager(
            client = client,
            store = AndroidOfflineMapPackStore(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        manager.prepare(DEFINITION)

        client.pack.emitTileLimit()
        client.pack.emitStatus(
            SdkOfflinePackStatus(
                isActive = true,
                isComplete = true,
                completedResources = 20,
                completedTiles = 17,
                requiredResources = 20,
                requiredResourcesIsPrecise = true,
            ),
        )
        assertEquals(OfflineMapPackState.Failed("tile_limit"), manager.states.value.single())

        manager.resume(DEFINITION.key)
        client.pack.emitError("TILE_LIMIT", "tile limit exceeded")
        assertEquals(OfflineMapPackState.Failed("tile_limit"), manager.states.value.single())
    }

    private class FakeOfflinePackClient : SdkOfflinePackClient {
        val pack = FakeOfflinePack()
        var createCount = 0
        var tileLimit: Long? = null
        var createdDefinition: OfflineTilePyramidRegionDefinition? = null
        var createdMetadata: ByteArray? = null

        override suspend fun listPacks(): List<SdkOfflinePack> = emptyList()

        override suspend fun setTileLimit(limit: Long) {
            tileLimit = limit
        }

        override suspend fun createPack(
            definition: OfflineTilePyramidRegionDefinition,
            metadata: ByteArray,
        ): SdkOfflinePack {
            createCount += 1
            createdDefinition = definition
            createdMetadata = metadata
            return pack
        }
    }

    private class FakeOfflinePack : SdkOfflinePack {
        override val id: Long = 41L
        override val metadata: ByteArray = byteArrayOf()
        private var observer: SdkOfflinePackObserver? = null
        var pauseCount = 0

        override suspend fun setObserver(observer: SdkOfflinePackObserver) {
            this.observer = observer
        }

        override suspend fun status(): SdkOfflinePackStatus? = null

        override suspend fun resume() = Unit

        override suspend fun pause() {
            pauseCount += 1
        }

        override suspend fun invalidate() = Unit

        override suspend fun delete() = Unit

        fun emitStatus(status: SdkOfflinePackStatus) = observer!!.onStatus(status)

        fun emitError(reason: String, message: String) = observer!!.onError(reason, message)

        fun emitTileLimit() = observer!!.onTileLimitExceeded()
    }

    companion object {
        private val DEFINITION = OfflineMapPackDefinition(
            key = OfflineMapPackKey(
                regionId = "ekb",
                style = OfflineMapStyleVariant.BRIGHT,
                ofmStyleUrl = "https://tiles.openfreemap.org/styles/bright",
            ),
            bounds = OfflineRegionBounds(56.30, 59.55, 57.25, 61.45),
            minZoom = 5,
            maxZoom = 13,
            tileLimit = 30_000,
        )
    }
}
