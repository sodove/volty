package ru.sodovaya.volty.presentation.pack

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.domain.stats.PackAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class PackDetailComponentTest {

    private class FakeBmsRepo : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> = Result.success(Unit)
        override suspend fun connectDemo(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}
    }

    private fun component(repo: FakeBmsRepo, packIndex: Int): DefaultPackDetailComponent =
        DefaultPackDetailComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            packIndex = packIndex,
            bmsRepository = repo,
            onBackRequested = {}
        )

    private fun packState(
        index: Int,
        cells: List<Float>,
        sections: List<SectionState> = emptyList(),
        online: Boolean = true,
        voltage: Float = cells.sum()
    ) = PackState(
        pack = Pack(index, "Pack ${index + 1}", BmsType.BEGODE, "AA:BB"),
        data = BmsData(voltage = voltage, cellVoltages = cells, isConnected = online),
        sections = sections,
        isOnline = online
    )

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `observes the pack with the requested index`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo, packIndex = 1)
        advanceUntilIdle()

        repo.activeVehicleData.value = PackAggregator.build(
            listOf(
                packState(0, List(4) { 3.7f }),
                packState(1, List(4) { 3.8f })
            ),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        val pack = c.state.value.pack
        assertNotNull(pack)
        assertEquals(1, pack.pack.index)
        assertEquals(3.8f, pack.data.cellVoltages.first())
    }

    @Test
    fun `sections from the protocol group the cells by section`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo, packIndex = 0)
        advanceUntilIdle()

        // 6 cells over 2 sections with the protocol declaring each section's
        // exact cell range — the only breakdown the UI will group by (list
        // arithmetic over a possibly-truncated cell list is refused, see
        // groupPackCells).
        val cells = listOf(3.701f, 3.702f, 3.703f, 3.711f, 3.712f, 3.713f)
        repo.activeVehicleData.value = PackAggregator.build(
            listOf(
                packState(
                    0, cells,
                    sections = listOf(
                        SectionState(index = 0, voltage = 11.106f, cellRange = 0..2),
                        SectionState(index = 1, voltage = 11.136f, cellRange = 3..5)
                    )
                )
            ),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        val groups = c.state.value.groups
        assertEquals(2, groups.size)
        assertEquals(0, groups[0].section?.index)
        assertEquals(1, groups[1].section?.index)
        assertEquals(cells.subList(0, 3), groups[0].voltages)
        assertEquals(cells.subList(3, 6), groups[1].voltages)
        // Positional indices: the second section's first cell is physical cell 4.
        assertEquals(0, groups[0].startIndex)
        assertEquals(3, groups[1].startIndex)
    }

    @Test
    fun `no sections means one flat group with no fabricated breakdown`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo, packIndex = 0)
        advanceUntilIdle()

        val cells = List(4) { 3.7f }
        repo.activeVehicleData.value = PackAggregator.build(
            listOf(packState(0, cells)),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        val groups = c.state.value.groups
        assertEquals(1, groups.size)
        assertNull(groups[0].section)
        assertEquals(0, groups[0].startIndex)
        assertEquals(cells, groups[0].voltages)
    }

    @Test
    fun `cells not dividing evenly across sections fall back to a flat list`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo, packIndex = 0)
        advanceUntilIdle()

        // 5 cells over 2 sections — still assembling at boot, or a truncated
        // list. Guessing a boundary would lie about the physical layout.
        repo.activeVehicleData.value = PackAggregator.build(
            listOf(
                packState(
                    0, List(5) { 3.7f },
                    sections = listOf(
                        SectionState(index = 0, voltage = 9.25f),
                        SectionState(index = 1, voltage = 9.25f)
                    )
                )
            ),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        val groups = c.state.value.groups
        assertEquals(1, groups.size)
        assertNull(groups[0].section)
    }

    @Test
    fun `offline pack is exposed as offline and keeps its last values`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo, packIndex = 1)
        advanceUntilIdle()

        repo.activeVehicleData.value = PackAggregator.build(
            listOf(
                packState(0, List(4) { 3.7f }),
                packState(1, List(4) { 3.69f }, online = false, voltage = 14.76f)
            ),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        val pack = c.state.value.pack
        assertNotNull(pack)
        assertFalse(pack.isOnline)
        assertEquals(14.76f, pack.data.voltage)
        assertTrue(c.state.value.groups.isNotEmpty())
    }
}
