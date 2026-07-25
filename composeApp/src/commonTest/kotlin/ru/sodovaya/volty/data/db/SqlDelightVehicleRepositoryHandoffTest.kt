package ru.sodovaya.volty.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.yieldsBmsToHeadUnit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Part C §5: persisting **"yield BMS to head unit while riding"**.
 *
 * The column is deliberately three-valued — NULL (unset, follow the default),
 * 0, 1 — so these tests pin all three, and the v5 -> v6 chain below pins that
 * an upgraded database and a fresh install agree on it.
 *
 * **`:composeApp:verifyCommonMainVoltyDatabaseMigration` has never run green in
 * this repo** — it dies on a native sqlite-jdbc load, recorded in
 * `B-vesc-dashboard.md §12.1` and confirmed there against an unmodified
 * checkout. That is an environment defect, not a statement about this
 * migration, but it does mean the `.sq`/`.sqm` agreement is verified HERE by
 * hand rather than by the tool built for it. **CI must run that task before
 * this ships**: if it fails there, `.sq` and `.sqm` disagree and upgraded
 * installs diverge from fresh ones in a way no test in this file can see.
 */
@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepositoryHandoffTest {

    private fun newInMemoryRepo(): SqlDelightVehicleRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        return SqlDelightVehicleRepository(VoltyDatabaseProvider(driver))
    }

    private fun scooter(id: String, yield: Boolean?) = Vehicle(
        id = id, name = "Scooter", iconKey = "scooter",
        packs = listOf(
            Pack(0, "ANT", BmsType.ANT_BMS, "AA:01", aliasGroup = "ant-72v"),
            Pack(1, "ANT via head unit", BmsType.VESC_BMS, "AA:0C", aliasGroup = "ant-72v")
        ),
        controllers = listOf(Controller(0, "Front", ControllerType.VESC, "AA:0C", canId = 41)),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now(),
        yieldBmsToHeadUnit = yield
    )

    @Test fun an_explicit_opt_out_round_trips() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(scooter("v-off", yield = false))
        val back = assertNotNull(repo.get("v-off"))
        assertEquals(false, back.yieldBmsToHeadUnit)
        assertFalse(back.yieldsBmsToHeadUnit, "an explicit false is the only thing that turns the handoff off")
    }

    @Test fun an_explicit_opt_in_round_trips_and_is_not_the_same_row_as_unset() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(scooter("v-on", yield = true))
        repo.upsert(scooter("v-unset", yield = null))
        // Both resolve to ON, but the STORED values must differ: collapsing
        // true and null would make a later change of default silently rewrite
        // a choice the rider actually made.
        assertEquals(true, assertNotNull(repo.get("v-on")).yieldBmsToHeadUnit)
        assertNull(assertNotNull(repo.get("v-unset")).yieldBmsToHeadUnit)
        assertTrue(assertNotNull(repo.get("v-on")).yieldsBmsToHeadUnit)
        assertTrue(assertNotNull(repo.get("v-unset")).yieldsBmsToHeadUnit)
    }

    /**
     * The hand-written upgrade chain that stands in for the broken verifier: a
     * database frozen at the v5 schema exactly as Part B shipped it, migrated
     * with the real `VoltyDatabase.Schema.migrate`, then read back through the
     * repository — which is compiled against the CURRENT `.sq`. If `5.sqm` and
     * `VehicleRow.sq` disagreed about the column, the read below would fail on
     * a missing column rather than quietly returning a wrong value.
     */
    @Test fun a_v5_database_upgrades_with_the_toggle_unset() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Frozen history — the v5 schema (v4's tables + 4.sqm's two columns).
        // Do NOT update this DDL when the live schema changes.
        driver.execute(
            null,
            """
            CREATE TABLE VehicleRow (
                id                       TEXT NOT NULL PRIMARY KEY,
                name                     TEXT NOT NULL,
                iconKey                  TEXT NOT NULL,
                topology                 TEXT NOT NULL DEFAULT 'PARALLEL',
                chemistry                TEXT NOT NULL,
                averagingWindowMin       INTEGER NOT NULL DEFAULT 5,
                cellHighV                REAL,
                cellLowV                 REAL,
                cellDeltaMv              INTEGER,
                temperatureWarnC         REAL,
                temperatureHighC         REAL,
                socLowPercent            INTEGER,
                socCutoffPercent         INTEGER,
                disconnectNotify         INTEGER NOT NULL DEFAULT 1,
                chargeCompleteNotify     INTEGER NOT NULL DEFAULT 1,
                createdAt                TEXT NOT NULL,
                lastConnectedAt          TEXT,
                isPinned                 INTEGER NOT NULL DEFAULT 0,
                dashboardStyle           TEXT,
                secondaryGauge           TEXT
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
            CREATE TABLE PackRow (
                vehicleId   TEXT NOT NULL,
                packIndex   INTEGER NOT NULL,
                label       TEXT NOT NULL,
                bmsType     TEXT NOT NULL,
                bmsAddress  TEXT NOT NULL,
                cellCount   INTEGER,
                canId       INTEGER,
                aliasGroup  TEXT,
                PRIMARY KEY (vehicleId, packIndex),
                FOREIGN KEY (vehicleId) REFERENCES VehicleRow(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            0
        )
        driver.execute(null, "CREATE INDEX PackRow_vehicle ON PackRow(vehicleId, packIndex)", 0)
        driver.execute(
            null,
            """
            CREATE TABLE ControllerRow (
                vehicleId              TEXT NOT NULL,
                controllerIndex        INTEGER NOT NULL,
                label                  TEXT NOT NULL,
                controllerType         TEXT NOT NULL,
                address                TEXT NOT NULL,
                canId                  INTEGER,
                polePairs              INTEGER NOT NULL DEFAULT 15,
                wheelDiameterMm        INTEGER NOT NULL DEFAULT 0,
                gearRatio              REAL NOT NULL DEFAULT 1.0,
                providesDerivedBattery INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (vehicleId, controllerIndex),
                FOREIGN KEY (vehicleId) REFERENCES VehicleRow(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            0
        )
        driver.execute(null, "CREATE INDEX ControllerRow_vehicle ON ControllerRow(vehicleId, controllerIndex)", 0)
        driver.execute(
            null,
            """
            INSERT INTO VehicleRow(
                id, name, iconKey, topology, chemistry, averagingWindowMin,
                createdAt, lastConnectedAt, isPinned, dashboardStyle, secondaryGauge
            ) VALUES (
                'v5-1', 'Scooter', 'scooter', 'PARALLEL', 'LI_ION_NMC', 5,
                '2026-01-02T03:04:05Z', NULL, 1, 'CLASSIC', 'BATTERY'
            )
            """.trimIndent(),
            0
        )
        driver.execute(
            null,
            """
            INSERT INTO PackRow(vehicleId, packIndex, label, bmsType, bmsAddress, cellCount, canId, aliasGroup)
            VALUES ('v5-1', 0, 'ANT', 'ANT_BMS', 'AA:01', 20, NULL, 'ant-72v')
            """.trimIndent(),
            0
        )

        VoltyDatabase.Schema.migrate(driver, 5, VoltyDatabase.Schema.version)

        val repo = SqlDelightVehicleRepository(VoltyDatabaseProvider(driver))
        val back = assertNotNull(repo.get("v5-1"), "the v5 row must survive the upgrade")
        assertNull(
            back.yieldBmsToHeadUnit,
            "an upgraded vehicle has made no choice — NULL, not a fabricated true/false"
        )
        assertTrue(back.yieldsBmsToHeadUnit, "and it therefore follows the default, which is ON")
        // Everything else the v5 row carried must be untouched by the ALTER.
        assertEquals("Scooter", back.name)
        assertTrue(back.isPinned)
        assertEquals("ant-72v", back.packs.single().aliasGroup)
        assertEquals(20, back.packs.single().cellCount)

        // And the upgraded database still round-trips a write through the
        // NEW column — an ALTER that landed in the wrong table would pass every
        // assertion above and fail here.
        repo.upsert(back.copy(yieldBmsToHeadUnit = false))
        assertEquals(false, assertNotNull(repo.get("v5-1")).yieldBmsToHeadUnit)
    }
}
