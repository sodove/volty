package ru.sodovaya.volty.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Vehicle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepositoryTest {

    private fun newRepo(): SqlDelightVehicleRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        return SqlDelightVehicleRepository(VoltyDatabaseProvider(driver))
    }

    private fun v(id: String, name: String = "test") = Vehicle(
        id = id,
        name = name,
        iconKey = "generic",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:BB:CC:DD:EE:FF",
        chemistry = Chemistry.LI_ION_NMC,
        alertConfig = AlertConfig(),
        createdAt = Clock.System.now()
    )

    @Test
    fun `empty repo emits empty list`() = runTest {
        val repo = newRepo()
        assertEquals(emptyList(), repo.vehicles.first())
    }

    @Test
    fun `upsert then get returns the same vehicle`() = runTest {
        val repo = newRepo()
        repo.upsert(v("id-1", "Stealth"))
        val got = repo.get("id-1")
        assertNotNull(got)
        assertEquals("Stealth", got.name)
        assertEquals(BmsType.JK_BMS, got.bmsType)
        assertEquals(Chemistry.LI_ION_NMC, got.chemistry)
    }

    @Test
    fun `upsert replaces existing row`() = runTest {
        val repo = newRepo()
        repo.upsert(v("id-1", "A"))
        repo.upsert(v("id-1", "B"))
        val all = repo.vehicles.first()
        assertEquals(1, all.size)
        assertEquals("B", all[0].name)
    }

    @Test
    fun `delete removes vehicle`() = runTest {
        val repo = newRepo()
        repo.upsert(v("id-1"))
        repo.delete("id-1")
        assertNull(repo.get("id-1"))
    }

    @Test
    fun `touch sets lastConnectedAt`() = runTest {
        val repo = newRepo()
        repo.upsert(v("id-1"))
        repo.touch("id-1")
        val got = repo.get("id-1")
        assertNotNull(got?.lastConnectedAt)
    }

    @Test
    fun `vehicles ordered by isPinned desc then last-connected-or-created desc`() = runTest {
        val repo = newRepo()
        repo.upsert(v("a", "Older"))
        repo.upsert(v("b", "Newer"))
        repo.touch("a")
        val names = repo.vehicles.first().map { it.name }
        // "Older" was touched after "Newer" was inserted, so Older's lastConnectedAt > Newer's createdAt
        // expected order: Older, Newer
        assertEquals(listOf("Older", "Newer"), names)
    }

    @Test
    fun `alertConfig round-trips correctly`() = runTest {
        val repo = newRepo()
        val v = v("id-1").copy(
            alertConfig = AlertConfig(
                cellHighV = 4.21f,
                cellLowV = 2.79f,
                cellDeltaMv = 150,
                temperatureHighC = 55f,
                socLowPercent = 20,
                socCutoffPercent = 5,
                disconnectNotify = false,
                chargeCompleteNotify = true
            )
        )
        repo.upsert(v)
        val got = repo.get("id-1")
        assertNotNull(got)
        val a = got.alertConfig
        assertEquals(4.21f, a.cellHighV)
        assertEquals(2.79f, a.cellLowV)
        assertEquals(150, a.cellDeltaMv)
        assertEquals(55f, a.temperatureHighC)
        assertEquals(20, a.socLowPercent)
        assertEquals(5, a.socCutoffPercent)
        assertTrue(!a.disconnectNotify)
        assertTrue(a.chargeCompleteNotify)
    }
}
