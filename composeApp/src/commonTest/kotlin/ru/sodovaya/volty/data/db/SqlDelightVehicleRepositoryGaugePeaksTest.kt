package ru.sodovaya.volty.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.AlertLevel
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.withCellCount
import ru.sodovaya.volty.domain.repository.GaugePeaks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The storage half of `G §9.2` after Part I Task 9 moved the two learned dial widths off the vehicle
 * row and into `GaugePeakRow` (`8.sqm`).
 *
 * **What changed, and what did not.** The rule these tests protect is unchanged: a `Vehicle`
 * snapshot can be older than the last peak write, so writing a vehicle must never move a learned
 * range. What changed is how it is held. It used to be a `COALESCE` inside `upsert` preserving two
 * columns a caller could still *set* on the `Vehicle` it passed in — a setter that compiled and
 * silently did nothing. Now there is no such setter: the values are reached only through
 * [ru.sodovaya.volty.domain.repository.VehicleRepository.gaugePeaks] and
 * [ru.sodovaya.volty.domain.repository.VehicleRepository.updateGaugePeaks], and the preserve
 * behaviour follows from `upsert` having nothing to write rather than from a statement remembering
 * not to.
 *
 * That the two are **not reachable from [Vehicle]** is a compile-time property and is not asserted
 * here — `Vehicle` has no such member, so any test that named one would not build.
 */
@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepositoryGaugePeaksTest {

    private fun newInMemoryRepo(): SqlDelightVehicleRepository = newInMemoryProvider().first

    /** The same, with the raw queries beside it for the downgrade-tolerance test below. */
    private fun newInMemoryProvider(): Pair<SqlDelightVehicleRepository, VoltyDatabaseProvider> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        return SqlDelightVehicleRepository(provider) to provider
    }

    private fun vehicle(id: String = "v1") = Vehicle(
        id = id, name = "Wheel", iconKey = "generic",
        packs = listOf(Pack(0, "P", BmsType.BEGODE, "WH:01", cellCount = 20)),
        controllers = listOf(Controller(0, "C", ControllerType.BEGODE, "WH:01")),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now(),
        motionAlerts = listOf(
            AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f), AlertLevel(90f)))
        )
    )

    private suspend fun SqlDelightVehicleRepository.peaksOf(id: String): GaugePeaks? =
        gaugePeaks.first()[id]

    /** Values no default could produce, so a dropped write cannot pass by coincidence. */
    @Test fun the_learned_peaks_round_trip_through_their_own_writer() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle())
        repo.updateGaugePeaks("v1", currentA = 137.5f, powerW = 6421.25f)
        assertEquals(GaugePeaks(137.5f, 6421.25f), repo.peaksOf("v1"))
    }

    /**
     * **An `upsert` cannot move a learned range, and that is the fix for the whole class of bug.**
     *
     * Every caller of `upsert` holds a [Vehicle] *snapshot*, and a snapshot taken before the last
     * peak write carries the older number. `KableBmsRepository.maybePersistCellCount` and
     * `maybePersistPacks` both upsert from `_activeVehicle.value`, which is set at connect and never
     * updated by a peak write — so before this rule, a wheel that discovered its cell count mid-ride
     * silently threw away the ranges it had just learned.
     *
     * Since `8.sqm` there is nothing on a `Vehicle` for such a caller to carry, so the stale snapshot
     * here is what those callers actually produce today: a `Vehicle` read BEFORE the peak write, then
     * edited and written back. The assertion is the same one that used to guard the `COALESCE`.
     */
    @Test fun an_upsert_from_a_stale_snapshot_cannot_revert_the_learned_peaks() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle())
        val staleSnapshot = assertNotNull(repo.get("v1"))   // taken before the peak write
        repo.updateGaugePeaks("v1", currentA = 137f, powerW = 6421f)

        // ...and now the auto-fill writes its edit back from the snapshot it has been holding.
        repo.upsert(staleSnapshot.withCellCount(20))

        assertEquals(
            GaugePeaks(137f, 6421f), repo.peaksOf("v1"),
            "a stale snapshot must not revert a learned peak"
        )
        assertEquals(
            20, assertNotNull(repo.get("v1")).packs.first().cellCount,
            "...and the edit it came for still landed"
        )
    }

    /**
     * **A vehicle that has never been ridden has NO row here, and that absence is the answer.**
     *
     * The fixture is deliberately incoherent with what any producer emits: a fully-formed vehicle
     * whose learned range simply does not exist. `upsert` must not seed a zero row — a padded map
     * and an unpadded one read the same through `GaugePeaks.NONE`, but only the unpadded one can
     * tell a caller that nothing has been learned, and `8.sqm` copies across only the vehicles that
     * had learned something for exactly that reason.
     */
    @Test fun an_unridden_vehicle_has_no_stored_row_at_all() = runTest {
        val (repo, provider) = newInMemoryProvider()
        repo.upsert(vehicle())
        assertNull(repo.peaksOf("v1"), "an upsert must not seed a learned range")
        assertEquals(
            emptyList(),
            provider.database.gaugePeakRowQueries.selectAll().executeAsList(),
            "...and there must be no row in the table either"
        )
    }

    @Test fun updateGaugePeaks_writes_both_values() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle())
        repo.updateGaugePeaks("v1", currentA = 24f, powerW = 1800f)
        assertEquals(GaugePeaks(24f, 1800f), repo.peaksOf("v1"))
    }

    /**
     * The composer's clear (`§9.2` item 7), which is the one event that legitimately LOWERS a range.
     * A statement that only ever grew a value — or one that refused to write over an existing row —
     * would pass every test above and fail here.
     */
    @Test fun the_accessor_can_clear_a_learned_range_back_to_zero() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle())
        repo.updateGaugePeaks("v1", currentA = 137f, powerW = 6421f)
        repo.updateGaugePeaks("v1", currentA = 0f, powerW = 0f)
        assertEquals(
            GaugePeaks(0f, 0f), repo.peaksOf("v1"),
            "a cleared range is a stored zero, NOT a deleted row -- see GaugePeakRow.sq"
        )
    }

    /**
     * **Why `updateGaugePeaks` is its own statement in its own table and not an `upsert`.** It runs
     * while the rider is riding, from a snapshot the dashboard may have loaded minutes ago; an upsert
     * would replay that snapshot's packs, controllers and alert levels over rows the live connection
     * can have moved underneath it (`KableBmsRepository.maybePersistPacks` appends a Begode's second
     * pack branch mid-ride, which is exactly when this screen is open).
     *
     * So: append a second pack behind the repository's back, then write the peaks. The pack must
     * still be there — and the vehicle must be untouched, asserted by whole-object equality, which
     * is now total over the vehicle because the peaks are no longer part of it.
     */
    @Test fun updateGaugePeaks_leaves_the_vehicle_and_its_child_rows_alone() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle())
        // The mid-ride append, through the repository's own API so it is a real stored state.
        val withSecondBranch = assertNotNull(repo.get("v1")).let { stored ->
            stored.copy(packs = stored.packs + Pack(1, "Pack 2", BmsType.BEGODE, "WH:01"))
        }
        repo.upsert(withSecondBranch)

        repo.updateGaugePeaks("v1", currentA = 24f, powerW = 1800f)

        val back = assertNotNull(repo.get("v1"))
        assertEquals(withSecondBranch, back, "the peak write must change nothing about the vehicle")
        assertEquals(2, back.packs.size, "the branch persisted mid-ride must survive a peak write")
        assertEquals(GaugePeaks(24f, 1800f), repo.peaksOf("v1"), "...and it still wrote the peak")
    }

    /**
     * **The reason the peak write is not a `get` + `copy` + `upsert`.**
     *
     * `upsert` replaces the alert-level rows *wholesale from the in-memory list*, and `toRules()` is
     * deliberately read-TOLERANT: it skips rows it cannot represent (an unrecognised `kind`, levels
     * past `MAX_LEVELS`) and leaves them in the table, so a newer version's data survives being read
     * by an older build. `SqlDelightVehicleRepository.upsert` says so itself, and adds that those rows
     * then live "exactly until the rider touches any vehicle setting".
     *
     * A gauge-peak write happens while the rider is *riding*. It is not them touching a setting, and
     * it must not be the thing that destroys those rows — which is exactly what a `get` + `upsert`
     * implementation would make it. Found by mutation sweep: with only the child-row test above,
     * routing the write through `upsert` passed everything, because a re-read gets the
     * *representable* children right. This is the difference that remains.
     */
    @Test fun updateGaugePeaks_does_not_destroy_alert_rows_an_older_build_cannot_represent() = runTest {
        val (repo, provider) = newInMemoryProvider()
        repo.upsert(vehicle())
        // A row from a hypothetical newer version: `toRules()` cannot map this `kind`, so it is
        // skipped on read and left in the table.
        provider.database.alertLevelRowQueries.upsert(
            vehicleId = "v1", kind = "FUTURE_KIND", position = 0L, threshold = 12.0, enabled = 1L
        )
        assertEquals(
            3,
            provider.database.alertLevelRowQueries.selectByVehicle("v1").executeAsList().size,
            "two DUTY levels plus the unrepresentable one"
        )

        repo.updateGaugePeaks("v1", currentA = 24f, powerW = 1800f)

        val rows = provider.database.alertLevelRowQueries.selectByVehicle("v1").executeAsList()
        assertEquals(
            listOf("FUTURE_KIND"),
            rows.map { it.kind }.filter { it == "FUTURE_KIND" },
            "a mid-ride peak write must not be a wholesale rewrite of the alert levels"
        )
        assertEquals(3, rows.size)
        assertEquals(GaugePeaks(24f, 1800f), repo.peaksOf("v1"), "...and it still wrote the peak")
    }

    /**
     * **An id nobody stored creates nothing — a genuine regression test, not a carried-over one.**
     *
     * Before `8.sqm` this was free: the writer was an `UPDATE ... WHERE id`, and an unknown id
     * matched no row. `GaugePeakRow` has no row to update for a vehicle that has never been ridden,
     * so the writer is an INSERT — and an unguarded one would write a parentless row where the
     * UPDATE did nothing. `GaugePeakRow.sq`'s `WHERE EXISTS` is what keeps the old guarantee.
     *
     * The path is named, not hypothetical: `RideDashboardComponent.persistPeaksIfRungChanged` guards
     * non-guest and non-demo but **not persisted**, and takes its vehicle from `activeVehicle`, which
     * nothing disconnects when a vehicle is deleted (`SettingsComponent` just calls `delete`). A
     * rider who deletes the vehicle they are riding keeps a live dashboard, and its next rung
     * crossing arrives *after* the delete that could have cleaned up after it. Vehicle ids are
     * caller-supplied strings, so the row left behind would be adopted by whoever next reuses the
     * id — the same argument `deleteByVehicle`'s WHERE clause exists for.
     */
    @Test fun updateGaugePeaks_on_an_unknown_id_creates_nothing() = runTest {
        val (repo, provider) = newInMemoryProvider()
        repo.updateGaugePeaks("ghost", currentA = 24f, powerW = 1800f)
        assertNull(repo.peaksOf("ghost"))
        assertEquals(
            emptyList(),
            provider.database.gaugePeakRowQueries.selectAll().executeAsList(),
            "a learned range was stored for a vehicle that does not exist"
        )
    }

    /** The delete-mid-ride path exactly: the dashboard's next write lands after the delete. */
    @Test fun a_peak_write_after_the_vehicle_was_deleted_leaves_nothing_behind() = runTest {
        val (repo, provider) = newInMemoryProvider()
        repo.upsert(vehicle())
        repo.updateGaugePeaks("v1", currentA = 137f, powerW = 6421f)

        // The rider deletes the vehicle from Settings while the ride dashboard is still connected...
        repo.delete("v1")
        // ...and the dashboard, which nothing disconnected, crosses one more rung.
        repo.updateGaugePeaks("v1", currentA = 200f, powerW = 9000f)

        assertEquals(
            emptyList(),
            provider.database.gaugePeakRowQueries.selectAll().executeAsList(),
            "the deleted vehicle's dial range came back, with no parent row to own it"
        )
    }

    /** Two vehicles, one write — the `WHERE id` clause, which a missing one would silently drop. */
    @Test fun updateGaugePeaks_touches_only_the_named_vehicle() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle(id = "v1"))
        repo.upsert(vehicle(id = "v2"))
        repo.updateGaugePeaks("v2", currentA = 90f, powerW = 4000f)
        repo.updateGaugePeaks("v1", currentA = 24f, powerW = 1800f)
        assertEquals(GaugePeaks(24f, 1800f), repo.peaksOf("v1"))
        assertEquals(GaugePeaks(90f, 4000f), repo.peaksOf("v2"))
    }

    /**
     * **Deleting a vehicle deletes its learned range, and this is what proves it.**
     *
     * `GaugePeakRow` declares `ON DELETE CASCADE`, and that cascade **never fires**: SQLite enforces
     * foreign keys only when `PRAGMA foreign_keys = ON` is set per connection, and neither
     * `SqlDriverFactory` actual sets it (`AndroidSqliteDriver` and `JdbcSqliteDriver` are both
     * constructed stock). `SqlDelightVehicleRepository.delete` therefore removes the row explicitly,
     * in the same transaction as the vehicle — and a cascade relied upon instead would be a guard
     * indistinguishable from its absence, which this asserts against directly rather than trusting.
     *
     * It matters beyond tidiness: vehicle ids are caller-supplied strings, so a recycled id would
     * otherwise adopt a stranger's dial range and open the wheel's CURRENT dial at a scooter's 240 A.
     */
    @Test fun deleting_a_vehicle_deletes_its_learned_range() = runTest {
        val (repo, provider) = newInMemoryProvider()
        repo.upsert(vehicle(id = "v1"))
        repo.upsert(vehicle(id = "v2"))
        repo.updateGaugePeaks("v1", currentA = 137f, powerW = 6421f)
        repo.updateGaugePeaks("v2", currentA = 90f, powerW = 4000f)

        repo.delete("v1")

        assertEquals(
            listOf("v2"),
            provider.database.gaugePeakRowQueries.selectAll().executeAsList().map { it.vehicleId },
            "the learned range outlived the vehicle it describes"
        )
        assertEquals(
            GaugePeaks(90f, 4000f), repo.peaksOf("v2"),
            "...and the delete took only the named vehicle's range with it"
        )
    }

    // ---------------------------------------------------------------- migration

    /**
     * **A rider who has learned their dial ranges over weeks of riding must not lose them to a
     * refactor.** A database frozen at the v8 schema — the two columns still on `VehicleRow` —
     * migrated with the real `VoltyDatabase.Schema.migrate` and read back through the repository
     * compiled against the CURRENT `.sq`.
     *
     * **The fixture is deliberately incoherent with what a single producer emits**: three vehicles,
     * one that has learned both ranges, one that has learned only power, and one that has never been
     * ridden. The last is the one that matters — it must come back as NO row rather than a zeroed
     * one, because absence is what `GaugePeakRow` means by "nothing learned", and a migration that
     * copied every row would invent a second spelling of it.
     */
    @Test fun a_v8_database_upgrades_carrying_every_learned_range_across() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Frozen history — the v8 schema. Do NOT update this DDL when the live schema changes.
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
                yieldBmsToHeadUnit       INTEGER,
                motionAlertsConfigured   INTEGER NOT NULL DEFAULT 0,
                gaugePeakCurrentA        REAL NOT NULL DEFAULT 0,
                gaugePeakPowerW          REAL NOT NULL DEFAULT 0
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
            CREATE TABLE AlertLevelRow (
                vehicleId  TEXT NOT NULL,
                kind       TEXT NOT NULL,
                position   INTEGER NOT NULL,
                threshold  REAL NOT NULL,
                enabled    INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (vehicleId, kind, position),
                FOREIGN KEY (vehicleId) REFERENCES VehicleRow(id) ON DELETE CASCADE
            )
            """.trimIndent(),
            0
        )
        driver.execute(null, "CREATE INDEX AlertLevelRow_vehicle ON AlertLevelRow(vehicleId, kind, position)", 0)

        // id -> (currentA, powerW). The third has never been ridden.
        val v8 = mapOf(
            "v8-both" to (137.5 to 6421.25),
            "v8-power-only" to (0.0 to 4000.0),
            "v8-unridden" to (0.0 to 0.0)
        )
        v8.forEach { (id, peaks) ->
            driver.execute(
                null,
                """
                INSERT INTO VehicleRow(
                    id, name, iconKey, topology, chemistry, averagingWindowMin,
                    createdAt, lastConnectedAt, isPinned, dashboardStyle, secondaryGauge,
                    yieldBmsToHeadUnit, motionAlertsConfigured, gaugePeakCurrentA, gaugePeakPowerW
                ) VALUES (
                    '$id', 'Scooter $id', 'scooter', 'PARALLEL', 'LI_ION_NMC', 5,
                    '2026-01-02T03:04:05Z', NULL, 0, NULL, 'DUTY', NULL, 0, ${peaks.first}, ${peaks.second}
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

        VoltyDatabase.Schema.migrate(driver, 8, VoltyDatabase.Schema.version)

        val provider = VoltyDatabaseProvider(driver)
        val repo = SqlDelightVehicleRepository(provider)

        assertEquals(
            GaugePeaks(137.5f, 6421.25f), repo.peaksOf("v8-both"),
            "weeks of learning were dropped by the migration"
        )
        assertEquals(GaugePeaks(0f, 4000f), repo.peaksOf("v8-power-only"))
        assertNull(
            repo.peaksOf("v8-unridden"),
            "a vehicle that learned nothing must migrate to NO row, not to a zeroed one"
        )

        v8.keys.forEach { id ->
            val back = assertNotNull(repo.get(id), "the v8 vehicle must survive the rebuild")
            assertEquals("Scooter $id", back.name, "the table rebuild must not disturb the other columns")
            assertEquals("AA:0C", back.controllers.single().address, "nor the child rows")
        }

        // The index the rebuild dropped with the old table has to come back, or `selectAll`'s
        // ORDER BY degrades to a scan on every launch and nothing else would ever notice.
        assertEquals(
            1L,
            driver.executeQuery(
                null,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'VehicleRow_pinned_recent'",
                { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else null) },
                0
            ).value
        )

        // The upgraded database must also accept a write through the new table — a CREATE TABLE that
        // landed only in .sq and not in 8.sqm would pass every assertion above and fail here.
        repo.updateGaugePeaks("v8-unridden", currentA = 24f, powerW = 1800f)
        assertEquals(GaugePeaks(24f, 1800f), repo.peaksOf("v8-unridden"))
    }
}
