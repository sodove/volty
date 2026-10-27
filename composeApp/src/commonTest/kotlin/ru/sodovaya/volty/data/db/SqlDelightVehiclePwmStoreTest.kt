package ru.sodovaya.volty.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.PwmManual
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.pwm.AlarmOnset
import ru.sodovaya.volty.domain.pwm.PwmBasis
import ru.sodovaya.volty.domain.pwm.PwmCalibration
import ru.sodovaya.volty.domain.pwm.PwmCalibrationLearner
import ru.sodovaya.volty.domain.pwm.PwmCalibrationSource
import ru.sodovaya.volty.domain.pwm.PwmEvidence
import ru.sodovaya.volty.domain.pwm.PwmMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class SqlDelightVehiclePwmStoreTest {

    private class Db {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { VoltyDatabase.Schema.create(it) }
        val provider = VoltyDatabaseProvider(driver)
        val store = SqlDelightVehiclePwmStore(provider)
        val repo = SqlDelightVehicleRepository(provider)
        fun rows(id: String): Long = provider.database.vehiclePwmRowQueries.selectByVehicle(id).executeAsList().size.toLong()
    }

    private val onsets = listOf(
        AlarmOnset(68.4f, 96.8f, null, 1_700_000_000_000L),
        AlarmOnset(55.25f, 90.5f, 31.5f, 1_700_000_100_000L),
        AlarmOnset(70f, 97f, -12.25f, 1_700_000_200_000L)
    )

    private val riding = PwmCalibration(
        k = 1.1364f, r = 0.043f, points = 3, source = PwmCalibrationSource.RIDING,
        spread = 0.021f, basis = PwmBasis.ONSET, onsets = onsets
    )

    private fun vehicle(id: String): Vehicle = singlePackVehicle(
        id = id, name = id, iconKey = "generic", bmsType = BmsType.JK_BMS, bmsAddress = "AA:$id",
        chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now()
    )

    @Test
    fun `an unknown vehicle loads empty memory`() = runTest {
        assertEquals(PwmMemory(null, PwmEvidence.UNKNOWN), Db().store.load("nope"))
        assertEquals(PwmMemory.EMPTY, Db().store.load("nope"))
    }

    @Test
    fun `calibration with its onset history round trips exactly`() = runTest {
        val db = Db()
        val memory = PwmMemory(riding, PwmEvidence.NEVER_REPORTED)
        db.store.save("v1", memory)
        val back = db.store.load("v1")
        assertEquals(memory, back)
        assertEquals(onsets, back.calibration!!.onsets, "the history is what makes R learnable: it must survive")
        assertNull(back.calibration!!.onsets[0].phaseA, "an onset with no current stays without one, not 0")
    }

    @Test
    fun `a free spin calibration with no spread and no onsets round trips`() = runTest {
        val db = Db()
        val cal = PwmCalibrationLearner.fromFreeSpin(81.6f, 96.5f)!!
        db.store.save("v1", PwmMemory(cal, PwmEvidence.NEVER_REPORTED))
        assertEquals(cal, db.store.load("v1").calibration)
        assertNull(db.store.load("v1").calibration!!.spread)
    }

    @Test
    fun `evidence alone persists without a calibration`() = runTest {
        val db = Db()
        db.store.save("v1", PwmMemory(null, PwmEvidence.REPORTS))
        assertEquals(PwmMemory(null, PwmEvidence.REPORTS), db.store.load("v1"))
    }

    @Test
    fun `every source and basis round trips`() = runTest {
        val db = Db()
        for (source in PwmCalibrationSource.entries) for (basis in PwmBasis.entries) {
            db.store.save("v", PwmMemory(riding.copy(source = source, basis = basis), PwmEvidence.UNKNOWN))
            val cal = db.store.load("v").calibration!!
            assertEquals(source, cal.source); assertEquals(basis, cal.basis)
        }
    }

    @Test
    fun `non finite calibration values are stored as absent`() = runTest {
        val db = Db()
        db.store.save("a", PwmMemory(riding.copy(k = Float.NaN), PwmEvidence.NEVER_REPORTED))
        db.store.save("b", PwmMemory(riding.copy(r = Float.POSITIVE_INFINITY), PwmEvidence.NEVER_REPORTED))
        assertEquals(PwmMemory(null, PwmEvidence.NEVER_REPORTED), db.store.load("a"), "evidence is kept, the broken calibration is not")
        assertEquals(PwmMemory(null, PwmEvidence.NEVER_REPORTED), db.store.load("b"))
        // A non-finite spread is merely 'not measured'; the calibration itself is fine.
        db.store.save("c", PwmMemory(riding.copy(spread = Float.NaN), PwmEvidence.NEVER_REPORTED))
        assertNull(db.store.load("c").calibration!!.spread)
        assertEquals(riding.k, db.store.load("c").calibration!!.k)
    }

    @Test
    fun `non finite onsets are dropped on save`() = runTest {
        val db = Db()
        val dirty = riding.copy(onsets = onsets + AlarmOnset(Float.NaN, 96f, null, 5L) + AlarmOnset(60f, 96f, Float.NaN, 6L))
        db.store.save("v1", PwmMemory(dirty, PwmEvidence.NEVER_REPORTED))
        assertEquals(onsets, db.store.load("v1").calibration!!.onsets)
    }

    @Test
    fun `a corrupt onset text on load drops only the bad entries and non finite columns read as absent`() = runTest {
        val db = Db()
        val q = db.provider.database.vehiclePwmRowQueries
        q.upsert("v1", "NEVER_REPORTED", 1.2, 0.0, 2L, "RIDING", null, "ONSET", "68.4,96.8,-,100,b;garbage;NaN,96,-,5,b;70,97,12.5,200,b")
        assertEquals(
            listOf(AlarmOnset(68.4f, 96.8f, null, 100L), AlarmOnset(70f, 97f, 12.5f, 200L)),
            db.store.load("v1").calibration!!.onsets
        )
        q.upsert("v2", "NEVER_REPORTED", Double.NaN, 0.0, 2L, "RIDING", null, "ONSET", "")
        assertEquals(PwmMemory(null, PwmEvidence.NEVER_REPORTED), db.store.load("v2"))
    }

    @Test
    fun `legacy edge-sample onsets are dropped on load, an ONSET fit made only of them is gone and the evidence stays`() = runTest {
        val db = Db()
        val q = db.provider.database.vehiclePwmRowQueries
        q.upsert("v1", "NEVER_REPORTED", 1.089, 0.0, 2L, "RIDING", null, "ONSET", "70.8,96.4,-,100;68.4,96.8,12.5,200")
        assertEquals(PwmMemory(null, PwmEvidence.NEVER_REPORTED), db.store.load("v1"))
    }

    @Test
    fun `a mixed history keeps only the onsets marked as captured before the edge`() = runTest {
        val db = Db()
        val q = db.provider.database.vehiclePwmRowQueries
        q.upsert("v1", "NEVER_REPORTED", 1.1, 0.0, 3L, "RIDING", null, "ONSET", "70.8,96.4,-,100;65.1,96.5,-,200,b;68.4,96.8,12.5,300")
        assertEquals(listOf(AlarmOnset(65.1f, 96.5f, null, 200L)), db.store.load("v1").calibration!!.onsets)
    }

    @Test
    fun `a free spin calibration survives its legacy onsets being dropped`() = runTest {
        val db = Db()
        val q = db.provider.database.vehiclePwmRowQueries
        q.upsert("v1", "NEVER_REPORTED", 1.2, 0.0, 1L, "MANUAL", null, "FREE_SPIN", "70.8,96.4,-,100")
        val cal = db.store.load("v1").calibration!!
        assertEquals(PwmBasis.FREE_SPIN, cal.basis)
        assertEquals(emptyList(), cal.onsets)
    }

    @Test
    fun `an unknown evidence name this build does not know reads as UNKNOWN`() = runTest {
        val db = Db()
        db.provider.database.vehiclePwmRowQueries.upsert("v1", "FROM_THE_FUTURE", null, null, 0L, null, null, null, "")
        assertEquals(PwmMemory.EMPTY, db.store.load("v1"))
    }

    @Test
    fun `onsets beyond the bound are cut to the newest on save`() = runTest {
        val db = Db()
        val many = List(PwmCalibrationLearner.MAX_ONSETS + 5) { AlarmOnset(60f + it, 96f, null, it.toLong()) }
        db.store.save("v1", PwmMemory(riding.copy(onsets = many), PwmEvidence.NEVER_REPORTED))
        assertEquals(many.takeLast(PwmCalibrationLearner.MAX_ONSETS), db.store.load("v1").calibration!!.onsets)
    }

    @Test
    fun `reset deletes only that vehicle`() = runTest {
        val db = Db()
        db.store.save("v1", PwmMemory(riding, PwmEvidence.NEVER_REPORTED))
        db.store.save("v2", PwmMemory(riding, PwmEvidence.NEVER_REPORTED))
        db.store.reset("v1")
        assertEquals(PwmMemory.EMPTY, db.store.load("v1"))
        assertEquals(PwmMemory(riding, PwmEvidence.NEVER_REPORTED), db.store.load("v2"))
    }

    @Test
    fun `deleting a vehicle deletes its PWM row`() = runTest {
        val db = Db()
        db.repo.upsert(vehicle("v1")); db.repo.upsert(vehicle("v2"))
        db.store.save("v1", PwmMemory(riding, PwmEvidence.NEVER_REPORTED))
        db.store.save("v2", PwmMemory(riding, PwmEvidence.NEVER_REPORTED))
        db.repo.delete("v1")
        assertEquals(0L, db.rows("v1"))
        assertEquals(1L, db.rows("v2"))
    }

    @Test
    fun `saving the vehicle does not touch its PWM row`() = runTest {
        val db = Db()
        db.repo.upsert(vehicle("v1"))
        db.store.save("v1", PwmMemory(riding, PwmEvidence.NEVER_REPORTED))
        db.repo.upsert(vehicle("v1").copy(name = "renamed"))
        assertEquals(PwmMemory(riding, PwmEvidence.NEVER_REPORTED), db.store.load("v1"))
    }

    // --- Vehicle.pwmAlarmThresholdPercent / Vehicle.pwmManual ---

    @Test
    fun `vehicle pwm fields default and round trip`() = runTest {
        val db = Db()
        db.repo.upsert(vehicle("plain"))
        assertEquals(80, db.repo.get("plain")!!.pwmAlarmThresholdPercent)
        assertNull(db.repo.get("plain")!!.pwmManual)

        db.repo.upsert(vehicle("set").copy(pwmAlarmThresholdPercent = 90, pwmManual = PwmManual(81.5f, 96.5f)))
        val back = db.repo.get("set")!!
        assertEquals(90, back.pwmAlarmThresholdPercent)
        assertEquals(PwmManual(81.5f, 96.5f), back.pwmManual)
    }

    @Test
    fun `an old row with null pwm columns reads as the defaults`() = runTest {
        val db = Db()
        db.repo.upsert(vehicle("old"))
        // 26.sqm adds the columns as plain nullable ones: a pre-migration row is exactly NULL here.
        db.driver.execute(null, "UPDATE VehicleRow SET pwmAlarmThresholdPercent = NULL, pwmManualKmh = NULL, pwmManualVolts = NULL", 0)
        val v = db.repo.get("old")!!
        assertEquals(80, v.pwmAlarmThresholdPercent)
        assertNull(v.pwmManual)
    }

    @Test
    fun `a manual entry with a missing half or a non finite or non positive value reads as absent`() = runTest {
        val db = Db()
        db.repo.upsert(vehicle("v"))
        fun setRaw(kmh: String, volts: String) =
            db.driver.execute(null, "UPDATE VehicleRow SET pwmManualKmh = $kmh, pwmManualVolts = $volts", 0)
        setRaw("80.0", "NULL"); assertNull(db.repo.get("v")!!.pwmManual)
        setRaw("NULL", "96.0"); assertNull(db.repo.get("v")!!.pwmManual)
        setRaw("0.0", "96.0"); assertNull(db.repo.get("v")!!.pwmManual)
        setRaw("80.0", "-1.0"); assertNull(db.repo.get("v")!!.pwmManual)
        setRaw("80.0", "96.0"); assertEquals(PwmManual(80f, 96f), db.repo.get("v")!!.pwmManual)
    }

    @Test
    fun `an out of range threshold reads as the default`() = runTest {
        val db = Db()
        db.repo.upsert(vehicle("v"))
        for (bad in listOf(0, -5, 101, 5000)) {
            db.driver.execute(null, "UPDATE VehicleRow SET pwmAlarmThresholdPercent = $bad", 0)
            assertEquals(80, db.repo.get("v")!!.pwmAlarmThresholdPercent, "stored $bad")
        }
        db.driver.execute(null, "UPDATE VehicleRow SET pwmAlarmThresholdPercent = 65", 0)
        assertEquals(65, db.repo.get("v")!!.pwmAlarmThresholdPercent)
    }

    @Test
    fun `a REPORTS row survives saving UNKNOWN, and NEVER_REPORTED cannot lower it either`() = runTest {
        val db = Db()
        db.store.save("v1", PwmMemory(null, PwmEvidence.REPORTS))
        db.store.save("v1", PwmMemory(null, PwmEvidence.UNKNOWN))
        assertEquals(PwmEvidence.REPORTS, db.store.load("v1").evidence)
        db.store.save("v1", PwmMemory(riding, PwmEvidence.NEVER_REPORTED))
        assertEquals(PwmEvidence.REPORTS, db.store.load("v1").evidence, "the latch is one-way")
        assertEquals(riding, db.store.load("v1").calibration, "a calibration is still storable under REPORTS (kept, inert)")
    }

    @Test
    fun `evidence only moves up on save`() = runTest {
        val db = Db()
        db.store.save("v1", PwmMemory(null, PwmEvidence.NEVER_REPORTED))
        db.store.save("v1", PwmMemory(null, PwmEvidence.UNKNOWN))
        assertEquals(PwmEvidence.NEVER_REPORTED, db.store.load("v1").evidence)
        db.store.save("v1", PwmMemory(null, PwmEvidence.REPORTS))
        assertEquals(PwmEvidence.REPORTS, db.store.load("v1").evidence)
    }

    @Test
    fun `a calibration survives an evidence-only save`() = runTest {
        val db = Db()
        db.store.save("v1", PwmMemory(riding, PwmEvidence.NEVER_REPORTED))
        db.store.save("v1", PwmMemory(null, PwmEvidence.NEVER_REPORTED))
        assertEquals(PwmMemory(riding, PwmEvidence.NEVER_REPORTED), db.store.load("v1"))
        // A broken (non-finite) calibration is dropped, and must not wipe the stored good one either.
        db.store.save("v1", PwmMemory(riding.copy(k = Float.NaN), PwmEvidence.NEVER_REPORTED))
        assertEquals(riding, db.store.load("v1").calibration)
    }

    @Test
    fun `resetCalibration clears the calibration and keeps the evidence`() = runTest {
        val db = Db()
        db.store.save("v1", PwmMemory(riding, PwmEvidence.NEVER_REPORTED))
        db.store.resetCalibration("v1")
        assertEquals(PwmMemory(null, PwmEvidence.NEVER_REPORTED), db.store.load("v1"))
        db.store.resetCalibration("never-stored")
        assertEquals(0L, db.rows("never-stored"), "resetting a vehicle with no row creates none")
    }
}
