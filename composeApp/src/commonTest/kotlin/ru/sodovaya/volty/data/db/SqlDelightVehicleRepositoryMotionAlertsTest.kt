package ru.sodovaya.volty.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ru.sodovaya.volty.domain.alert.AlarmDefaults
import ru.sodovaya.volty.domain.alert.AlertLevel
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.motionAlertRules
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Part F Task 4: persisting the rider's motion alert levels.
 *
 * Two properties carry most of the weight here, and both are failure modes an
 * alarm feature cannot afford:
 *
 *  1. **"never configured" and "the rider switched everything off" must not
 *     collapse into each other.** Both write zero rows to `AlertLevelRow`, so
 *     the distinction lives in `VehicleRow.motionAlertsConfigured`. Resurrecting
 *     [AlarmDefaults] over a rider's deliberate silence is the loudest possible
 *     bug in a feature whose job is to make noise.
 *  2. **Reads sort by threshold, never by stored `position`.** [AlertRule]
 *     enforces ascending order with `require`, so a row set that disagrees with
 *     itself throws out of `get()` — on the launch path, before any screen. A
 *     hand-edited database or a restored backup must degrade, not crash.
 */
@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepositoryMotionAlertsTest {

    private fun newDriver(): SqlDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { VoltyDatabase.Schema.create(it) }

    private fun repoOn(driver: SqlDriver) = SqlDelightVehicleRepository(VoltyDatabaseProvider(driver))

    private fun newInMemoryRepo(): SqlDelightVehicleRepository = repoOn(newDriver())

    private fun scooter(id: String, alerts: List<AlertRule>?) = Vehicle(
        id = id, name = "Scooter", iconKey = "scooter",
        packs = listOf(Pack(0, "ANT", BmsType.ANT_BMS, "AA:01")),
        controllers = listOf(Controller(0, "Rear", ControllerType.VESC, "AA:0C")),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now(),
        motionAlerts = alerts
    )

    /** Every kind present, every one with no levels — the rider silenced the lot. */
    private fun everythingOff(): List<AlertRule> =
        MotionAlertKind.entries.map { AlertRule(it, emptyList()) }

    // ---------------------------------------------------------------- never configured

    @Test fun a_vehicle_that_was_never_configured_reads_back_as_defaults() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(scooter("v-fresh", alerts = null))

        val back = assertNotNull(repo.get("v-fresh"))
        assertNull(back.motionAlerts, "no answer was ever given, so storage must not invent one")
        // No spot-check on DUTY's two default thresholds follows: it would be
        // strictly implied by the equality above, so no implementation could
        // fail it while reaching it. An assertion that cannot fail is noise.
        assertEquals(AlarmDefaults.all(), back.motionAlertRules, "and the defaults resolve it")
    }

    // ---------------------------------------------------------------- deliberate silence

    @Test fun a_rider_who_deletes_every_level_stays_silent_across_a_reload() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(scooter("v-quiet", alerts = everythingOff()))

        val back = assertNotNull(repo.get("v-quiet"))
        assertNotNull(back.motionAlerts, "the rider answered — with silence. That is still an answer.")
        assertTrue(back.motionAlerts.all { it.isOff }, "every kind must come back off")
        // Likewise no follow-up on DUTY specifically: "all rules are off" already
        // entails "duty's levels are empty", so such an assertion could never be
        // reached in a failing state.
        assertTrue(
            back.motionAlertRules.all { it.isOff },
            "and resolving must not smuggle AlarmDefaults back over the top"
        )
    }

    @Test fun silencing_one_kind_does_not_disturb_the_others() = runTest {
        val repo = newInMemoryRepo()
        // Duty configured, ESC temp deliberately emptied — the two live in the
        // same table, told apart only by their rows existing or not.
        repo.upsert(
            scooter(
                "v-mixed",
                alerts = listOf(
                    AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(70f), AlertLevel(85f))),
                    AlertRule(MotionAlertKind.ESC_TEMP, emptyList())
                )
            )
        )

        val back = assertNotNull(assertNotNull(repo.get("v-mixed")).motionAlerts)
        assertEquals(
            listOf(70f, 85f),
            back.single { it.kind == MotionAlertKind.DUTY }.levels.map { it.thresholdValue }
        )
        assertTrue(back.single { it.kind == MotionAlertKind.ESC_TEMP }.isOff)
        // Kinds the caller never mentioned are off too, not defaulted: the
        // vehicle HAS been configured, so this table is the whole truth.
        assertTrue(back.single { it.kind == MotionAlertKind.MOTOR_TEMP }.isOff)
        assertEquals(MotionAlertKind.entries.size, back.size, "every kind is always present")
    }

    // ---------------------------------------------------------------- plain round trips

    @Test fun thresholds_and_mute_flags_round_trip() = runTest {
        val repo = newInMemoryRepo()
        val tuned = listOf(
            AlertRule(
                MotionAlertKind.SPEED,
                listOf(
                    AlertLevel(35f, enabled = true),
                    AlertLevel(45f, enabled = false),
                    AlertLevel(55f, enabled = true)
                )
            )
        )
        repo.upsert(scooter("v-speed", alerts = tuned))

        val speed = assertNotNull(repo.get("v-speed")).motionAlertRules
            .single { it.kind == MotionAlertKind.SPEED }
        assertEquals(listOf(35f, 45f, 55f), speed.levels.map { it.thresholdValue })
        assertEquals(
            listOf(true, false, true),
            speed.levels.map { it.enabled },
            "a muted middle step must come back muted, in place — not dropped, not promoted"
        )
    }

    @Test fun saving_the_defaults_explicitly_is_recorded_as_a_choice() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(scooter("v-accepted", alerts = AlarmDefaults.all()))

        val back = assertNotNull(repo.get("v-accepted"))
        assertNotNull(back.motionAlerts, "accepting the defaults is an answer, and is stored as one")
        assertEquals(AlarmDefaults.all(), back.motionAlerts)
    }

    @Test fun a_later_upsert_replaces_the_level_set_wholesale() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(scooter("v-edit", alerts = listOf(AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(60f), AlertLevel(70f), AlertLevel(80f))))))
        repo.upsert(scooter("v-edit", alerts = listOf(AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(95f))))))

        val duty = assertNotNull(repo.get("v-edit")).motionAlertRules
            .single { it.kind == MotionAlertKind.DUTY }
        assertEquals(
            listOf(95f),
            duty.levels.map { it.thresholdValue },
            "positions 1 and 2 from the previous save must not survive as stale rows"
        )
    }

    @Test fun deleting_a_vehicle_takes_its_levels_with_it() = runTest {
        val driver = newDriver()
        val repo = repoOn(driver)
        repo.upsert(scooter("v-gone", alerts = AlarmDefaults.all()))
        repo.delete("v-gone")
        // Foreign keys are OFF in SQLite unless PRAGMA foreign_keys is set, and
        // this app never sets it — so the declared cascade does nothing and the
        // repository deletes children by hand. Read the table directly: an
        // orphan row would later be adopted by a recycled vehicle id.
        assertEquals(
            emptyList(),
            VoltyDatabase(driver).alertLevelRowQueries.selectByVehicle("v-gone").executeAsList()
        )
    }

    @Test fun two_rules_for_one_kind_are_refused_before_they_can_be_half_saved() {
        // Storage keys levels by (vehicleId, kind, position), so the second
        // rule's levels would overwrite the first's position-by-position and
        // the vehicle would read back as something nobody asked for. Caught at
        // construction instead, where the caller can still see it.
        assertFailsWith<IllegalArgumentException> {
            scooter(
                "v-dup",
                alerts = listOf(
                    AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f))),
                    AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(90f)))
                )
            )
        }
    }

    // ---------------------------------------------------------------- hostile rows

    /**
     * The crash this task exists to prevent: `position` says one order,
     * `threshold` says another, and [AlertRule] only accepts the second.
     */
    @Test fun rows_whose_positions_contradict_their_thresholds_load_ascending() = runTest {
        val driver = newDriver()
        val repo = repoOn(driver)
        repo.upsert(scooter("v-bad", alerts = everythingOff()))
        // Hand-edited / restored-backup shape: descending thresholds at
        // ascending positions. Trusting `position` hands AlertRule [90, 80].
        insertLevel(driver, "v-bad", MotionAlertKind.DUTY, position = 0, threshold = 90.0, enabled = false)
        insertLevel(driver, "v-bad", MotionAlertKind.DUTY, position = 1, threshold = 80.0, enabled = true)

        val duty = assertNotNull(repo.get("v-bad")).motionAlertRules
            .single { it.kind == MotionAlertKind.DUTY }
        assertEquals(listOf(80f, 90f), duty.levels.map { it.thresholdValue })
        assertEquals(
            listOf(true, false),
            duty.levels.map { it.enabled },
            "each mute flag must travel with its own threshold, not stay at its old index"
        )
    }

    @Test fun equal_thresholds_keep_their_stored_order() = runTest {
        val driver = newDriver()
        val repo = repoOn(driver)
        repo.upsert(scooter("v-tie", alerts = everythingOff()))
        insertLevel(driver, "v-tie", MotionAlertKind.MOTOR_TEMP, position = 0, threshold = 100.0, enabled = false)
        insertLevel(driver, "v-tie", MotionAlertKind.MOTOR_TEMP, position = 1, threshold = 100.0, enabled = true)

        val levels = assertNotNull(repo.get("v-tie")).motionAlertRules
            .single { it.kind == MotionAlertKind.MOTOR_TEMP }.levels
        assertEquals(
            listOf(false, true),
            levels.map { it.enabled },
            "the sort is stable, so a tie keeps the rider's order and its mute flags"
        )
    }

    @Test fun a_non_finite_stored_threshold_is_dropped_instead_of_crashing() = runTest {
        val driver = newDriver()
        val repo = repoOn(driver)
        repo.upsert(scooter("v-inf", alerts = everythingOff()))
        insertLevel(driver, "v-inf", MotionAlertKind.ESC_TEMP, position = 0, threshold = 90.0, enabled = true)
        // SQLite stores 1e400 as REAL infinity. AlertLevel rejects it — an
        // infinite level can never fire and never release while isOff stays
        // false, i.e. an alarm the settings screen shows as armed and which is
        // permanently dead.
        insertLevel(driver, "v-inf", MotionAlertKind.ESC_TEMP, position = 1, threshold = Double.POSITIVE_INFINITY, enabled = true)

        val esc = assertNotNull(repo.get("v-inf")).motionAlertRules
            .single { it.kind == MotionAlertKind.ESC_TEMP }
        assertEquals(listOf(90f), esc.levels.map { it.thresholdValue })
    }

    @Test fun more_rows_than_MAX_LEVELS_keep_the_most_urgent() = runTest {
        val driver = newDriver()
        val repo = repoOn(driver)
        repo.upsert(scooter("v-many", alerts = everythingOff()))
        listOf(50.0, 60.0, 70.0, 80.0).forEachIndexed { i, t ->
            insertLevel(driver, "v-many", MotionAlertKind.DUTY, position = i.toLong(), threshold = t, enabled = true)
        }

        val duty = assertNotNull(repo.get("v-many")).motionAlertRules
            .single { it.kind == MotionAlertKind.DUTY }
        assertEquals(
            listOf(60f, 70f, 80f),
            duty.levels.map { it.thresholdValue },
            "AlertRule caps at ${AlertRule.MAX_LEVELS}; dropping from the top would throw away the loudest warning"
        )
    }

    @Test fun an_unrecognised_kind_string_is_ignored() = runTest {
        val driver = newDriver()
        val repo = repoOn(driver)
        repo.upsert(scooter("v-future", alerts = everythingOff()))
        driver.execute(
            null,
            "INSERT INTO AlertLevelRow(vehicleId, kind, position, threshold, enabled) " +
                "VALUES ('v-future', 'BRAKE_TEMP', 0, 120.0, 1)",
            0
        )

        val back = assertNotNull(repo.get("v-future"))
        assertEquals(
            MotionAlertKind.entries.size,
            assertNotNull(back.motionAlerts).size,
            "a row written by a newer version must not become a fifth rule"
        )
        assertTrue(assertNotNull(back.motionAlerts).all { it.isOff })
    }

    // ---------------------------------------------------------------- atomicity

    /**
     * The levels are written inside the vehicle's own transaction, so a failure
     * partway through must leave *nothing* behind — not a VehicleRow claiming
     * `motionAlertsConfigured = 1` over levels that were never written.
     *
     * The driver below fails the first `AlertLevelRow` insert, which is exactly
     * where a real failure would land (disk full, corruption) and is the last
     * write in the transaction, so everything before it is already staged.
     */
    @Test fun a_failure_writing_levels_rolls_the_whole_vehicle_back() = runTest {
        val driver = FailOnDriver(newDriver(), failWhenSqlContains = "INSERT OR REPLACE INTO AlertLevelRow")
        val repo = repoOn(driver)

        assertFailsWith<IllegalStateException> {
            repo.upsert(scooter("v-atomic", alerts = listOf(AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f))))))
        }

        driver.armed = false
        assertNull(
            repoOn(driver).get("v-atomic"),
            "the VehicleRow must have rolled back with the levels — a half-saved vehicle " +
                "would carry a configured flag over rows that do not exist"
        )
    }

    @Test fun a_failure_writing_levels_leaves_the_previous_save_intact() = runTest {
        val inner = newDriver()
        val driver = FailOnDriver(inner, failWhenSqlContains = "INSERT OR REPLACE INTO AlertLevelRow")
        driver.armed = false
        val repo = repoOn(driver)
        repo.upsert(scooter("v-keep", alerts = listOf(AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f))))))

        driver.armed = true
        assertFailsWith<IllegalStateException> {
            repo.upsert(scooter("v-keep", alerts = listOf(AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(95f))))))
        }

        driver.armed = false
        val duty = assertNotNull(repo.get("v-keep")).motionAlertRules
            .single { it.kind == MotionAlertKind.DUTY }
        assertEquals(
            listOf(80f),
            duty.levels.map { it.thresholdValue },
            "the delete-then-insert must roll back together, or a failed edit silences the alarm"
        )
    }

    /**
     * `delete()` is four statements, so it is one transaction. Process death
     * between them would strand child rows under no parent, and a recycled
     * vehicle id would then adopt a stranger's packs, controllers and alarm
     * thresholds — an alarm configured by somebody else's ride.
     */
    @Test fun a_failed_delete_leaves_no_orphaned_children() = runTest {
        val driver = FailOnDriver(newDriver(), failWhenSqlContains = "DELETE FROM VehicleRow")
        driver.armed = false
        val repo = repoOn(driver)
        repo.upsert(scooter("v-halfdead", alerts = AlarmDefaults.all()))

        driver.armed = true
        assertFailsWith<IllegalStateException> { repo.delete("v-halfdead") }

        driver.armed = false
        val db = VoltyDatabase(driver)
        assertTrue(
            db.alertLevelRowQueries.selectByVehicle("v-halfdead").executeAsList().isNotEmpty(),
            "the level rows must roll back with the vehicle row, not outlive it"
        )
        assertTrue(db.packRowQueries.selectByVehicle("v-halfdead").executeAsList().isNotEmpty())
        assertTrue(db.controllerRowQueries.selectByVehicle("v-halfdead").executeAsList().isNotEmpty())
        assertNotNull(repo.get("v-halfdead"), "and the vehicle itself is still there, intact")
    }

    // ---------------------------------------------------------------- migration

    /**
     * A database frozen at the v6 schema — Part C's shape, before this task —
     * carrying real vehicles, migrated with the real `VoltyDatabase.Schema.migrate`
     * and read back through the repository compiled against the CURRENT `.sq`.
     *
     * Every pre-existing vehicle has, by definition, never configured a motion
     * alert, so all of them must come up on [AlarmDefaults] — and none of them
     * may come up silenced, which is what a `DEFAULT 1` on
     * `motionAlertsConfigured` would have produced.
     */
    @Test fun a_v6_database_upgrades_with_every_vehicle_on_the_defaults() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Frozen history — the v6 schema. Do NOT update this DDL when the live
        // schema changes.
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
                secondaryGauge           TEXT,
                yieldBmsToHeadUnit       INTEGER
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
        listOf("v6-a", "v6-b").forEach { id ->
            driver.execute(
                null,
                """
                INSERT INTO VehicleRow(
                    id, name, iconKey, topology, chemistry, averagingWindowMin,
                    createdAt, lastConnectedAt, isPinned, dashboardStyle, secondaryGauge, yieldBmsToHeadUnit
                ) VALUES (
                    '$id', 'Scooter $id', 'scooter', 'PARALLEL', 'LI_ION_NMC', 5,
                    '2026-01-02T03:04:05Z', NULL, 0, NULL, 'DUTY', NULL
                )
                """.trimIndent(),
                0
            )
            driver.execute(
                null,
                """
                INSERT INTO ControllerRow(vehicleId, controllerIndex, label, controllerType, address, canId, polePairs, wheelDiameterMm, gearRatio, providesDerivedBattery)
                VALUES ('$id', 0, 'Rear', 'VESC', 'AA:0C', NULL, 15, 0, 1.0, 0)
                """.trimIndent(),
                0
            )
        }

        VoltyDatabase.Schema.migrate(driver, 6, VoltyDatabase.Schema.version)

        val repo = repoOn(driver)
        listOf("v6-a", "v6-b").forEach { id ->
            val back = assertNotNull(repo.get(id), "the v6 row must survive the upgrade")
            assertNull(back.motionAlerts, "an upgraded vehicle has configured nothing")
            assertEquals(AlarmDefaults.all(), back.motionAlertRules, "so it opens on the defaults")
            assertFalse(
                back.motionAlertRules.single { it.kind == MotionAlertKind.DUTY }.isOff,
                "and duty in particular must be armed, not silenced by a wrong column default"
            )
            assertEquals("Scooter $id", back.name, "the ALTER must not disturb the existing columns")
        }

        // The upgraded database must also accept a write through the new table —
        // a CREATE TABLE that landed only in .sq and not in 6.sqm would pass
        // every assertion above and fail here.
        val a = assertNotNull(repo.get("v6-a"))
        repo.upsert(a.copy(motionAlerts = listOf(AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(88f))))))
        assertEquals(
            listOf(88f),
            assertNotNull(repo.get("v6-a")).motionAlertRules
                .single { it.kind == MotionAlertKind.DUTY }.levels.map { it.thresholdValue }
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun insertLevel(
        driver: SqlDriver,
        vehicleId: String,
        kind: MotionAlertKind,
        position: Long,
        threshold: Double,
        enabled: Boolean
    ) {
        // Raw, bypassing the repository on purpose: these row sets are ones the
        // repository would never write, which is exactly why the reader must
        // survive them.
        driver.execute(
            null,
            "INSERT INTO AlertLevelRow(vehicleId, kind, position, threshold, enabled) " +
                "VALUES ('$vehicleId', '${kind.name}', $position, ${if (threshold.isInfinite()) "1e400" else threshold.toString()}, ${if (enabled) 1 else 0})",
            0
        )
    }

    /** A driver that fails the first statement matching [failWhenSqlContains] while [armed]. */
    private class FailOnDriver(
        private val delegate: SqlDriver,
        private val failWhenSqlContains: String
    ) : SqlDriver by delegate {
        var armed: Boolean = true

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?
        ) = if (armed && sql.contains(failWhenSqlContains)) {
            error("simulated write failure: $sql")
        } else {
            delegate.execute(identifier, sql, parameters, binders)
        }
    }
}
