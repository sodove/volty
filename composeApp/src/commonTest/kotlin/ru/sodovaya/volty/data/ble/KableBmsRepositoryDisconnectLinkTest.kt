package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Task 10 (sub-project A, Part A foundation): per-link disconnect. Tears down
 * ONE link of a multi-link vehicle — its session and reconnect job — and
 * drops it from the fold, leaving the vehicle's other links live. This is the
 * handoff primitive: the ride-time BMS -> head-unit handoff (a later part)
 * drops a link without tearing down the whole vehicle connection.
 *
 * Modeled on [KableBmsRepositoryMultiLinkTest]: fakes only, the production
 * fold/reconnect pathways driven through the same test seams
 * ([KableBmsRepository.installLinksForTest], [KableBmsRepository.markLinkOnlineForTest],
 * [KableBmsRepository.simulateLinkDropForTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryDisconnectLinkTest {

    private class StubVehicleRepository : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
        // Explicit, because both of VehicleRepository's gauge-peak members are abstract:
        // no fake gets a silent default. Nothing in this file rides a learned dial range
        // (G §9.2), and an EMPTY map is the honest answer rather than a missing one --
        // absence in that map means "has learned nothing", which is exactly the case here.
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    /** Every test here owns its repository through [bleRepositoryTest] — see there for why that is not optional. */
    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = StubVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        body = body
    )

    private fun twoLinkVehicle(): Vehicle = Vehicle(
        id = "v-multi",
        name = "Rig",
        iconKey = "battery",
        packs = listOf(
            Pack(index = 0, label = "Main", bmsType = BmsType.ANT_BMS, bmsAddress = ADDR_A),
            Pack(index = 1, label = "Aux", bmsType = BmsType.JBD_BMS, bmsAddress = ADDR_B)
        ),
        topology = PackTopology.PARALLEL,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    private fun sample(current: Float, voltage: Float = 74.0f) =
        BmsData(voltage = voltage, current = current, isConnected = true)

    @Test
    fun `disconnectLink drops one link, keeps the other Connected and producing`() = repoTest { repo ->
        val v = twoLinkVehicle()
        val funnels = repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)
        repo.markLinkOnlineForTest(ADDR_A)
        repo.markLinkOnlineForTest(ADDR_B)
        funnels[0](0, sample(current = 8.85f), emptyList())
        funnels[1](0, sample(current = 4.20f), emptyList())
        assertEquals(2, repo.linkCountForTest())

        repo.disconnectLink(ADDR_B)
        runCurrent()

        assertEquals(1, repo.linkCountForTest(), "the dropped link must be removed from the fold")
        assertEquals(ConnectionState.Connected(v), repo.connectionState.value, "the surviving link keeps the vehicle Connected")
        assertNull(repo.linkReconnectJobForTest(ADDR_B), "the dropped link must not linger with a reconnect job")
        assertNull(repo.linkReconnectJobForTest(ADDR_A), "the surviving link is untouched")

        // The surviving link keeps producing through the same funnel.
        funnels[0](0, sample(current = 9.10f), emptyList())
        val snap = repo.activeVehicleData.value
        assertTrue(snap.packs[0].isOnline)
        assertEquals(9.10f, snap.packs[0].data.current, absoluteTolerance = 0.001f)
    }

    @Test
    fun `disconnectLink cancels a reconnecting link's own loop`() = repoTest { repo ->
        val v = twoLinkVehicle()
        repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)
        repo.markLinkOnlineForTest(ADDR_A)
        repo.markLinkOnlineForTest(ADDR_B)

        repo.simulateLinkDropForTest(ADDR_B, "Link dropped")
        runCurrent()
        val jobB = assertNotNull(repo.linkReconnectJobForTest(ADDR_B))
        assertTrue(jobB.isActive)

        repo.disconnectLink(ADDR_B)
        runCurrent()

        assertFalse(jobB.isActive, "dropping the link must cancel its own reconnect loop")
        assertEquals(ConnectionState.Connected(v), repo.connectionState.value, "link A alone keeps the vehicle Connected")
        assertEquals(1, repo.linkCountForTest())
    }

    @Test
    fun `disconnectLink on the last link degenerates to a full disconnect`() = repoTest { repo ->
        val v = singlePackVehicle(
            id = "v-one", name = "Solo", iconKey = "scooter",
            bmsType = BmsType.JK_BMS, bmsAddress = ADDR_A,
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0L)
        )
        repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)
        repo.markLinkOnlineForTest(ADDR_A)
        assertEquals(ConnectionState.Connected(v), repo.connectionState.value)

        repo.disconnectLink(ADDR_A)
        runCurrent()

        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
        assertEquals(0, repo.linkCountForTest())
        assertNull(repo.activeVehicle.value, "a full disconnect must clear the active vehicle")
    }

    @Test
    fun `disconnectLink is a no-op for an address with no installed link`() = repoTest { repo ->
        val v = twoLinkVehicle()
        repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)
        repo.markLinkOnlineForTest(ADDR_A)
        repo.markLinkOnlineForTest(ADDR_B)

        repo.disconnectLink("AA:BB:CC:DD:EE:99")
        runCurrent()

        assertEquals(2, repo.linkCountForTest())
        assertEquals(ConnectionState.Connected(v), repo.connectionState.value)
    }

    private companion object {
        const val ADDR_A = "AA:BB:CC:DD:EE:01"
        const val ADDR_B = "AA:BB:CC:DD:EE:02"
    }
}
