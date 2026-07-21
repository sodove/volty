package ru.sodovaya.volty.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.bmsType
import ru.sodovaya.volty.domain.model.singlePackVehicle
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

    private fun v(id: String, name: String = "test") = singlePackVehicle(
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

    private fun sampleVehicle() = v("sample-1")

    @Test
    fun roundTripsAMultiPackVehicle() = runTest {
        val repo = newRepo()
        val v = Vehicle(
            id = "wheel-1",
            name = "T4",
            iconKey = "wheel",
            packs = listOf(
                Pack(0, "Branch 1", BmsType.ANT_BMS, "AA:01", cellCount = 24),
                Pack(1, "Branch 2", BmsType.ANT_BMS, "AA:02", cellCount = 24)
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Clock.System.now()
        )
        repo.upsert(v)

        val got = repo.get("wheel-1")!!
        assertEquals(2, got.packs.size)
        assertEquals("Branch 2", got.packs[1].label)
        assertEquals("AA:02", got.packs[1].bmsAddress)
        assertEquals(24, got.packs[1].cellCount)
        assertEquals(PackTopology.PARALLEL, got.topology)
    }

    @Test
    fun packsComeBackOrderedByIndex() = runTest {
        val repo = newRepo()
        val v = Vehicle(
            id = "wheel-2",
            name = "Wheel",
            iconKey = "wheel",
            packs = listOf(
                Pack(1, "Second", BmsType.JK_BMS, "AA:02"),
                Pack(0, "First", BmsType.JK_BMS, "AA:01")
            ),
            topology = PackTopology.SERIES,
            chemistry = Chemistry.LIFEPO4,
            createdAt = Clock.System.now()
        )
        repo.upsert(v)

        val got = repo.get("wheel-2")!!
        assertEquals(listOf(0, 1), got.packs.map { it.index })
        assertEquals("First", got.packs[0].label)
        assertEquals(PackTopology.SERIES, got.topology)
    }

    @Test
    fun shrinkingThePackListRemovesTheOrphanedRow() = runTest {
        val repo = newRepo()
        val two = Vehicle(
            id = "wheel-3",
            name = "Wheel",
            iconKey = "wheel",
            packs = listOf(
                Pack(0, "First", BmsType.JK_BMS, "AA:01"),
                Pack(1, "Second", BmsType.JK_BMS, "AA:02")
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Clock.System.now()
        )
        repo.upsert(two)
        repo.upsert(two.copy(packs = two.packs.take(1)))

        assertEquals(1, repo.get("wheel-3")!!.packs.size)
    }

    @Test
    fun droppingAMiddlePackWithoutReindexingLeavesNoStaleRow() = runTest {
        // Pack indices are not required to be contiguous: dropping the middle
        // pack of three without renumbering ([0,1,2] -> [0,2]) must not leave
        // the old index-1 row behind. A size-based trim would.
        val repo = newRepo()
        val three = Vehicle(
            id = "wheel-4",
            name = "Wheel",
            iconKey = "wheel",
            packs = listOf(
                Pack(0, "First", BmsType.JK_BMS, "AA:01"),
                Pack(1, "Second", BmsType.JK_BMS, "AA:02"),
                Pack(2, "Third", BmsType.JK_BMS, "AA:03")
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Clock.System.now()
        )
        repo.upsert(three)
        repo.upsert(three.copy(packs = listOf(three.packs[0], three.packs[2])))

        val got = repo.get("wheel-4")!!
        assertEquals(listOf(0, 2), got.packs.map { it.index })
        assertEquals(listOf("First", "Third"), got.packs.map { it.label })
    }

    @Test
    fun deletingAVehicleLeavesNoOrphanPacks() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        val repo = SqlDelightVehicleRepository(provider)
        repo.upsert(sampleVehicle())
        repo.delete(sampleVehicle().id)
        assertNull(repo.get(sampleVehicle().id))
        // get() returning null only proves the VehicleRow is gone. The orphan
        // guarantee has to be asserted against PackRow itself: ON DELETE
        // CASCADE never fires here because PRAGMA foreign_keys is off by
        // default and nothing enables it.
        val remaining = provider.database.packRowQueries
            .selectByVehicle(sampleVehicle().id)
            .executeAsList()
        assertEquals(emptyList(), remaining)
    }

    @Test
    fun migratesAV2DatabaseToOnePackPerVehicle() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // The v2 schema exactly as it shipped: the v1 CREATE TABLE from
        // VehicleRow.sq plus 1.sqm's ALTER TABLE, which appends
        // temperatureWarnC as the last column. Frozen history — do not update
        // this DDL when the live schema changes.
        driver.execute(
            null,
            """
            CREATE TABLE VehicleRow (
                id                       TEXT NOT NULL PRIMARY KEY,
                name                     TEXT NOT NULL,
                iconKey                  TEXT NOT NULL,
                bmsType                  TEXT NOT NULL,
                bmsAddress               TEXT NOT NULL,
                chemistry                TEXT NOT NULL,
                cellCount                INTEGER,
                averagingWindowMin       INTEGER NOT NULL DEFAULT 5,
                cellHighV                REAL,
                cellLowV                 REAL,
                cellDeltaMv              INTEGER,
                temperatureHighC         REAL,
                socLowPercent            INTEGER,
                socCutoffPercent         INTEGER,
                disconnectNotify         INTEGER NOT NULL DEFAULT 1,
                chargeCompleteNotify     INTEGER NOT NULL DEFAULT 1,
                createdAt                TEXT NOT NULL,
                lastConnectedAt          TEXT,
                isPinned                 INTEGER NOT NULL DEFAULT 0,
                temperatureWarnC         REAL
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            "CREATE INDEX VehicleRow_pinned_recent ON VehicleRow(isPinned DESC, lastConnectedAt DESC, createdAt DESC)",
            0
        )
        driver.execute(
            null,
            """
            INSERT INTO VehicleRow(
                id, name, iconKey, bmsType, bmsAddress, chemistry, cellCount,
                averagingWindowMin, cellHighV, cellLowV, cellDeltaMv, temperatureHighC,
                socLowPercent, socCutoffPercent, disconnectNotify, chargeCompleteNotify,
                createdAt, lastConnectedAt, isPinned, temperatureWarnC
            ) VALUES (
                'v2-1', 'Old Faithful', 'scooter', 'JBD_BMS', '11:22:33:44:55:66', 'LIFEPO4', 16,
                7, 3.65, 2.5, 100, 60.0,
                15, 5, 0, 1,
                '2024-01-02T03:04:05Z', '2024-06-07T08:09:10Z', 1, 45.0
            )
            """.trimIndent(),
            0
        )

        // A second vehicle with NULL cellCount and NULL lastConnectedAt: it
        // exercises the nullable path through the copy statements, and having
        // two rows at all would catch a copy that lost its per-row scoping.
        driver.execute(
            null,
            """
            INSERT INTO VehicleRow(
                id, name, iconKey, bmsType, bmsAddress, chemistry, cellCount,
                averagingWindowMin, cellHighV, cellLowV, cellDeltaMv, temperatureHighC,
                socLowPercent, socCutoffPercent, disconnectNotify, chargeCompleteNotify,
                createdAt, lastConnectedAt, isPinned, temperatureWarnC
            ) VALUES (
                'v2-2', 'Barebones', 'wheel', 'ANT_BMS', 'AA:BB:CC:00:11:22', 'LI_ION_NMC', NULL,
                5, NULL, NULL, NULL, NULL,
                NULL, NULL, 1, 1,
                '2024-03-04T05:06:07Z', NULL, 0, NULL
            )
            """.trimIndent(),
            0
        )

        VoltyDatabase.Schema.migrate(driver, 2, 3)

        val repo = SqlDelightVehicleRepository(VoltyDatabaseProvider(driver))
        val got = repo.get("v2-1")
        assertNotNull(got)
        assertEquals("Old Faithful", got.name)
        assertEquals("scooter", got.iconKey)
        assertEquals(PackTopology.PARALLEL, got.topology)
        assertEquals(Chemistry.LIFEPO4, got.chemistry)
        assertEquals(7, got.averagingWindowMin)
        assertEquals(1, got.packs.size)
        val pack = got.packs[0]
        assertEquals(0, pack.index)
        // The migrated pack is labelled after the vehicle, mirroring singlePackVehicle().
        assertEquals("Old Faithful", pack.label)
        assertEquals(BmsType.JBD_BMS, pack.bmsType)
        assertEquals("11:22:33:44:55:66", pack.bmsAddress)
        assertEquals(16, pack.cellCount)
        val a = got.alertConfig
        assertEquals(3.65f, a.cellHighV)
        assertEquals(2.5f, a.cellLowV)
        assertEquals(100, a.cellDeltaMv)
        assertEquals(45f, a.temperatureWarnC)
        assertEquals(60f, a.temperatureHighC)
        assertEquals(15, a.socLowPercent)
        assertEquals(5, a.socCutoffPercent)
        assertTrue(!a.disconnectNotify)
        assertTrue(a.chargeCompleteNotify)
        assertEquals("2024-01-02T03:04:05Z", got.createdAt.toString())
        assertEquals("2024-06-07T08:09:10Z", got.lastConnectedAt?.toString())
        assertTrue(got.isPinned)

        // The second vehicle came through with its own single pack — not
        // zero (lost row) and not two (cross-vehicle copy) — and its NULL
        // columns stayed NULL.
        val second = repo.get("v2-2")
        assertNotNull(second)
        assertEquals("Barebones", second.name)
        assertEquals("wheel", second.iconKey)
        assertEquals(Chemistry.LI_ION_NMC, second.chemistry)
        assertEquals(1, second.packs.size)
        val secondPack = second.packs[0]
        assertEquals(0, secondPack.index)
        assertEquals("Barebones", secondPack.label)
        assertEquals(BmsType.ANT_BMS, secondPack.bmsType)
        assertEquals("AA:BB:CC:00:11:22", secondPack.bmsAddress)
        assertNull(secondPack.cellCount)
        assertNull(second.lastConnectedAt)
        assertEquals("2024-03-04T05:06:07Z", second.createdAt.toString())
        assertTrue(!second.isPinned)

        // Recreating VehicleRow drops its index with it; the migration must
        // have recreated the index on the renamed table.
        val indexSurvived = driver.executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'VehicleRow_pinned_recent'",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0
        ).value
        assertTrue(indexSurvived)
    }
}
