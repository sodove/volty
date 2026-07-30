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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The storage half of `G §9.2`: the two learned dial widths (`7.sqm`) round-trip, and the targeted
 * writer that the ride dashboard uses mid-ride touches nothing else.
 */
@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepositoryGaugePeaksTest {

    private fun newInMemoryRepo(): SqlDelightVehicleRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        return SqlDelightVehicleRepository(VoltyDatabaseProvider(driver))
    }

    /** The same, with the raw queries beside it for the downgrade-tolerance test below. */
    private fun newInMemoryProvider(): Pair<SqlDelightVehicleRepository, VoltyDatabaseProvider> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        return SqlDelightVehicleRepository(provider) to provider
    }

    private fun vehicle(
        id: String = "v1",
        currentA: Float = 0f,
        powerW: Float = 0f
    ) = Vehicle(
        id = id, name = "Wheel", iconKey = "generic",
        packs = listOf(Pack(0, "P", BmsType.BEGODE, "WH:01", cellCount = 20)),
        controllers = listOf(Controller(0, "C", ControllerType.BEGODE, "WH:01")),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now(),
        motionAlerts = listOf(
            AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f), AlertLevel(90f)))
        ),
        gaugePeakCurrentA = currentA,
        gaugePeakPowerW = powerW
    )

    /** Values no default could produce, so a dropped column cannot pass by coincidence. */
    @Test fun the_learned_peaks_round_trip_through_upsert() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle(currentA = 137.5f, powerW = 6421.25f))
        val back = repo.get("v1")!!
        assertEquals(137.5f, back.gaugePeakCurrentA)
        assertEquals(6421.25f, back.gaugePeakPowerW)
    }

    /**
     * `7.sqm`'s `DEFAULT 0` read back through the domain: a vehicle nobody has ridden reports zero,
     * NOT a null and not a crash. Zero is the honest seed (`§9.2` item 5).
     */
    @Test fun an_unridden_vehicle_reads_back_as_zero() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle())
        val back = repo.get("v1")!!
        assertEquals(0f, back.gaugePeakCurrentA)
        assertEquals(0f, back.gaugePeakPowerW)
    }

    @Test fun updateGaugePeaks_writes_both_columns() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle())
        repo.updateGaugePeaks("v1", currentA = 24f, powerW = 1800f)
        val back = repo.get("v1")!!
        assertEquals(24f, back.gaugePeakCurrentA)
        assertEquals(1800f, back.gaugePeakPowerW)
    }

    /**
     * **Why `updateGaugePeaks` is its own statement and not an `upsert`.** It runs while the rider is
     * riding, from a snapshot the dashboard may have loaded minutes ago; an upsert would replay that
     * snapshot's packs, controllers and alert levels over rows the live connection can have moved
     * underneath it (`KableBmsRepository.maybePersistPacks` appends a Begode's second pack branch
     * mid-ride, which is exactly when this screen is open).
     *
     * So: append a second pack behind the repository's back, then write the peaks. The pack must
     * still be there — and every other column untouched, asserted by whole-object equality against
     * the vehicle the write was *supposed* to produce.
     */
    @Test fun updateGaugePeaks_leaves_every_other_column_and_the_child_rows_alone() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle())
        // The mid-ride append, through the repository's own API so it is a real stored state.
        val withSecondBranch = repo.get("v1")!!.let { stored ->
            stored.copy(packs = stored.packs + Pack(1, "Pack 2", BmsType.BEGODE, "WH:01"))
        }
        repo.upsert(withSecondBranch)

        repo.updateGaugePeaks("v1", currentA = 24f, powerW = 1800f)

        val back = repo.get("v1")!!
        assertEquals(
            withSecondBranch.copy(gaugePeakCurrentA = 24f, gaugePeakPowerW = 1800f),
            back,
            "the peak write must change exactly two fields"
        )
        assertEquals(2, back.packs.size, "the branch persisted mid-ride must survive a peak write")
    }

    /**
     * **The reason the override is not just a faster spelling of the inherited default.**
     *
     * `upsert` replaces the alert-level rows *wholesale from the in-memory list*, and `toRules()` is
     * deliberately read-TOLERANT: it skips rows it cannot represent (an unrecognised `kind`, levels
     * past `MAX_LEVELS`) and leaves them in the table, so a newer version's data survives being read
     * by an older build. `SqlDelightVehicleRepository.upsert` says so itself, and adds that those rows
     * then live "exactly until the rider touches any vehicle setting".
     *
     * A gauge-peak write happens while the rider is *riding*. It is not them touching a setting, and
     * it must not be the thing that destroys those rows — which is exactly what the inherited
     * `get` + `copy` + `upsert` default would make it. Found by mutation sweep: with only the
     * child-row test above, replacing the targeted `UPDATE` with `super.updateGaugePeaks(...)` passed
     * everything, because the default re-reads fresh state and so gets the *representable* children
     * right. This is the difference that remains.
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
        assertEquals(24f, repo.get("v1")!!.gaugePeakCurrentA, "...and it still wrote the peak")
    }

    /** An id nobody stored is a no-op, not an insert of a source-less vehicle. */
    @Test fun updateGaugePeaks_on_an_unknown_id_creates_nothing() = runTest {
        val repo = newInMemoryRepo()
        repo.updateGaugePeaks("ghost", currentA = 24f, powerW = 1800f)
        assertEquals(null, repo.get("ghost"))
    }

    /** Two vehicles, one write — the `WHERE id` clause, which a missing one would silently drop. */
    @Test fun updateGaugePeaks_touches_only_the_named_vehicle() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle(id = "v1"))
        repo.upsert(vehicle(id = "v2", currentA = 90f, powerW = 4000f))
        repo.updateGaugePeaks("v1", currentA = 24f, powerW = 1800f)
        assertEquals(24f, repo.get("v1")!!.gaugePeakCurrentA)
        assertEquals(90f, repo.get("v2")!!.gaugePeakCurrentA)
        assertEquals(4000f, repo.get("v2")!!.gaugePeakPowerW)
    }
}
