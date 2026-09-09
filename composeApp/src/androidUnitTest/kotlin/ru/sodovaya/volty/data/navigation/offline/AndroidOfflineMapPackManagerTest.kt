package ru.sodovaya.volty.data.navigation.offline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackDefinition
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackKey
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackState
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapStyleVariant
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidOfflineMapPackManagerTest {
    @Test
    fun definition_metadata_and_repeated_prepare_reuse_one_pack() = runTest {
        val client = FakeOfflinePackClient()
        val store = AndroidOfflineMapPackStore()
        val manager = AndroidOfflineMapPackManager(client, store, this)

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
        assertEquals(30_000, metadata.tileLimit)
        assertNull(metadata.ofmRevision)
    }

    @Test
    fun schema_one_metadata_remains_readable_with_a_positive_legacy_tile_limit() {
        val decoded = AndroidOfflineMapPackStore().decode(SCHEMA_ONE_METADATA.encodeToByteArray())!!

        assertEquals(DEFINITION.key, decoded.key)
        assertEquals(30_000, decoded.tileLimit)
    }

    @Test
    fun restored_inactive_incomplete_pack_is_resumed_by_prepare_without_duplication() = runTest {
        val store = AndroidOfflineMapPackStore()
        val restored = FakeOfflinePack(
            metadata = store.encode(DEFINITION),
            initialStatus = INACTIVE_INCOMPLETE,
        )
        val client = FakeOfflinePackClient(listOf(restored))
        val manager = AndroidOfflineMapPackManager(client, store, this)

        manager.prepare(DEFINITION)

        assertEquals(0, client.createCount)
        assertEquals(30_000L, client.tileLimit)
        assertEquals(1, restored.resumeCount)
        assertEquals(OfflineMapPackState.Preparing, manager.states.value.single())
    }

    @Test
    fun restored_active_complete_pack_is_published_ready_and_paused() = runTest {
        val store = AndroidOfflineMapPackStore()
        val restored = FakeOfflinePack(
            metadata = store.encode(DEFINITION),
            initialStatus = ACTIVE_COMPLETE,
        )
        val manager = AndroidOfflineMapPackManager(
            FakeOfflinePackClient(listOf(restored)),
            store,
            this,
        )

        manager.prepare(DEFINITION)

        assertEquals(OfflineMapPackState.Ready, manager.states.value.single())
        assertEquals(1, restored.pauseCount)
    }

    @Test
    fun active_then_complete_callback_becomes_ready_and_pauses_serially() = runTest {
        val client = FakeOfflinePackClient()
        val manager = AndroidOfflineMapPackManager(client, AndroidOfflineMapPackStore(), this)
        manager.prepare(DEFINITION)

        client.pack.emitStatus(ACTIVE_INCOMPLETE)
        advanceUntilIdle()
        assertEquals(OfflineMapPackState.Downloading(11, 8, 20), manager.states.value.single())

        client.pack.emitStatus(ACTIVE_COMPLETE)
        advanceUntilIdle()
        assertEquals(OfflineMapPackState.Ready, manager.states.value.single())
        assertEquals(1, client.pack.pauseCount)
    }

    @Test
    fun explicit_pause_publishes_a_non_downloading_state() = runTest {
        val client = FakeOfflinePackClient()
        val manager = AndroidOfflineMapPackManager(client, AndroidOfflineMapPackStore(), this)
        manager.prepare(DEFINITION)
        client.pack.emitStatus(ACTIVE_INCOMPLETE)
        advanceUntilIdle()

        manager.pause(DEFINITION.key)

        assertEquals(OfflineMapPackState.Preparing, manager.states.value.single())
        assertEquals(1, client.pack.pauseCount)
    }

    @Test
    fun tile_limit_and_tile_limit_error_stay_failed_instead_of_becoming_ready() = runTest {
        val client = FakeOfflinePackClient()
        val manager = AndroidOfflineMapPackManager(client, AndroidOfflineMapPackStore(), this)
        manager.prepare(DEFINITION)

        client.pack.emitTileLimit()
        client.pack.emitStatus(ACTIVE_COMPLETE)
        advanceUntilIdle()
        assertEquals(OfflineMapPackState.Failed("tile_limit"), manager.states.value.single())

        manager.resume(DEFINITION.key)
        client.pack.emitError("TILE_LIMIT", "tile limit exceeded")
        advanceUntilIdle()
        assertEquals(OfflineMapPackState.Failed("tile_limit"), manager.states.value.single())
    }

    @Test
    fun earlier_tile_limit_failure_wins_when_dispatcher_attempts_completion_first() = runTest {
        val dispatcher = ReverseDispatcher()
        val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val client = FakeOfflinePackClient()
        val manager = AndroidOfflineMapPackManager(client, AndroidOfflineMapPackStore(), managerScope)
        dispatcher.runAll()
        manager.prepare(DEFINITION)

        client.pack.emitTileLimit()
        client.pack.emitStatus(ACTIVE_COMPLETE)
        dispatcher.runLast()

        assertEquals(OfflineMapPackState.Failed("tile_limit"), manager.states.value.single())
        managerScope.cancel()
    }

    @Test
    fun queued_completion_and_late_error_cannot_pause_or_republish_after_delete() = runTest {
        val client = FakeOfflinePackClient()
        val manager = AndroidOfflineMapPackManager(client, AndroidOfflineMapPackStore(), this)
        manager.prepare(DEFINITION)
        val staleObserver = client.pack.currentObserver!!
        client.pack.deleteGate = CompletableDeferred()

        staleObserver.onStatus(ACTIVE_COMPLETE)
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) { manager.delete(DEFINITION.key) }
        assertTrue(client.pack.operationLog.contains("delete-start"))
        staleObserver.onError("TILE_LIMIT", "late tile limit")
        staleObserver.onStatus(ACTIVE_COMPLETE)

        client.pack.deleteGate!!.complete(Result.success(Unit))
        deletion.join()
        advanceUntilIdle()

        assertTrue(manager.states.value.isEmpty())
        assertFalse(client.pack.operationLog.dropWhile { it != "delete-start" }.contains("pause"))
        assertNull(client.pack.currentObserver)
    }

    @Test
    fun cancellation_after_delete_starts_still_reconciles_the_success_callback() = runTest {
        val client = FakeOfflinePackClient()
        val manager = AndroidOfflineMapPackManager(client, AndroidOfflineMapPackStore(), this)
        manager.prepare(DEFINITION)
        client.pack.deleteGate = CompletableDeferred()

        val deletion = launch(start = CoroutineStart.UNDISPATCHED) { manager.delete(DEFINITION.key) }
        deletion.cancel()
        assertFalse(deletion.isCompleted)
        client.pack.deleteGate!!.complete(Result.success(Unit))
        deletion.join()

        assertTrue(manager.states.value.isEmpty())
        assertEquals(1, client.pack.deleteCount)
    }

    @Test
    fun cancellation_while_delete_is_queued_does_not_call_sdk_delete() = runTest {
        val client = FakeOfflinePackClient()
        val manager = AndroidOfflineMapPackManager(client, AndroidOfflineMapPackStore(), this)
        manager.prepare(DEFINITION)
        client.pack.pauseGate = CompletableDeferred()
        val pause = launch(start = CoroutineStart.UNDISPATCHED) { manager.pause(DEFINITION.key) }
        val deletion = launch { manager.delete(DEFINITION.key) }
        advanceUntilIdle()

        deletion.cancel()
        client.pack.pauseGate!!.complete(Unit)
        pause.join()
        deletion.join()

        assertEquals(0, client.pack.deleteCount)
    }

    @Test
    fun cancellation_while_native_delete_admission_is_queued_does_not_call_sdk_delete() = runTest {
        val client = FakeOfflinePackClient()
        val manager = AndroidOfflineMapPackManager(client, AndroidOfflineMapPackStore(), this)
        manager.prepare(DEFINITION)
        client.pack.deleteAdmissionGate = CompletableDeferred()

        val deletion = launch(start = CoroutineStart.UNDISPATCHED) { manager.delete(DEFINITION.key) }
        deletion.cancel()
        client.pack.deleteAdmissionGate!!.complete(Unit)
        deletion.join()

        assertEquals(0, client.pack.deleteCount)
        assertTrue(client.pack.currentObserver != null)
    }

    @Test
    fun failed_delete_reattaches_observer_and_a_retry_can_remove_the_pack() = runTest {
        val client = FakeOfflinePackClient()
        val manager = AndroidOfflineMapPackManager(client, AndroidOfflineMapPackStore(), this)
        manager.prepare(DEFINITION)
        client.pack.deleteGate = CompletableDeferred(Result.failure(IllegalStateException("delete failed")))

        manager.delete(DEFINITION.key)

        assertEquals(OfflineMapPackState.Failed("sdk_error"), manager.states.value.single())
        assertTrue(client.pack.currentObserver != null)

        client.pack.deleteGate = CompletableDeferred(Result.success(Unit))
        manager.delete(DEFINITION.key)

        assertEquals(2, client.pack.deleteCount)
        assertTrue(manager.states.value.isEmpty())
    }

    private class FakeOfflinePackClient(
        private val listed: List<SdkOfflinePack> = emptyList(),
    ) : SdkOfflinePackClient {
        val pack = FakeOfflinePack()
        var createCount = 0
        var tileLimit: Long? = null
        var createdDefinition: OfflineTilePyramidRegionDefinition? = null
        var createdMetadata: ByteArray? = null

        override suspend fun listPacks(): List<SdkOfflinePack> = listed

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
            pack.storedMetadata = metadata
            return pack
        }
    }

    private class FakeOfflinePack(
        metadata: ByteArray = byteArrayOf(),
        private val initialStatus: SdkOfflinePackStatus? = null,
    ) : SdkOfflinePack {
        override val id: Long = 41L
        var storedMetadata: ByteArray = metadata
        override val metadata: ByteArray get() = storedMetadata
        var currentObserver: SdkOfflinePackObserver? = null
        val operationLog = mutableListOf<String>()
        var resumeCount = 0
        var pauseCount = 0
        var deleteCount = 0
        var deleteAdmissionGate: CompletableDeferred<Unit>? = null
        var deleteGate: CompletableDeferred<Result<Unit>>? = null
        var pauseGate: CompletableDeferred<Unit>? = null

        override suspend fun setObserver(observer: SdkOfflinePackObserver?) {
            operationLog += if (observer == null) "detach" else "attach"
            currentObserver = observer
        }

        override suspend fun status(): SdkOfflinePackStatus? = initialStatus

        override suspend fun resume() {
            operationLog += "resume"
            resumeCount += 1
        }

        override suspend fun pause() {
            operationLog += "pause"
            pauseCount += 1
            pauseGate?.await()
        }

        override suspend fun invalidate() {
            operationLog += "invalidate"
        }

        override suspend fun invokeDelete(onInvoked: (SdkOfflinePackDeletion) -> Unit) {
            operationLog += "delete-admission"
            deleteAdmissionGate?.await()
            operationLog += "delete-start"
            deleteCount += 1
            onInvoked(object : SdkOfflinePackDeletion {
                override suspend fun awaitCompletion() {
                    deleteGate?.await()?.getOrThrow()
                    operationLog += "delete-end"
                }
            })
        }

        fun emitStatus(status: SdkOfflinePackStatus) = currentObserver!!.onStatus(status)

        fun emitError(reason: String, message: String) = currentObserver!!.onError(reason, message)

        fun emitTileLimit() = currentObserver!!.onTileLimitExceeded()
    }

    private class ReverseDispatcher : CoroutineDispatcher() {
        private val queued = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued += block
        }

        fun runLast() {
            queued.removeAt(queued.lastIndex).run()
        }

        fun runAll() {
            while (queued.isNotEmpty()) runLast()
        }
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
        private val INACTIVE_INCOMPLETE = SdkOfflinePackStatus(
            isActive = false,
            isComplete = false,
            completedResources = 5,
            completedTiles = 3,
            requiredResources = 20,
            requiredResourcesIsPrecise = true,
        )
        private val ACTIVE_INCOMPLETE = SdkOfflinePackStatus(
            isActive = true,
            isComplete = false,
            completedResources = 11,
            completedTiles = 8,
            requiredResources = 20,
            requiredResourcesIsPrecise = true,
        )
        private val ACTIVE_COMPLETE = SdkOfflinePackStatus(
            isActive = true,
            isComplete = true,
            completedResources = 20,
            completedTiles = 17,
            requiredResources = 20,
            requiredResourcesIsPrecise = true,
        )
        private const val SCHEMA_ONE_METADATA =
            """{"schemaVersion":1,"regionId":"ekb","styleVariant":"BRIGHT","styleUrl":"https://tiles.openfreemap.org/styles/bright","bounds":{"south":56.3,"west":59.55,"north":57.25,"east":61.45},"minZoom":5,"maxZoom":13,"ofmRevision":null}"""
    }
}
