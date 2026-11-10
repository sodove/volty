package ru.sodovaya.volty.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.energy.CapacityCandidate
import ru.sodovaya.volty.domain.energy.CapacityLearnDecision
import ru.sodovaya.volty.domain.energy.EnergyMemory
import ru.sodovaya.volty.domain.energy.EnergyRecord
import ru.sodovaya.volty.domain.energy.LearnedCapacity
import ru.sodovaya.volty.domain.energy.PersistedCharge
import ru.sodovaya.volty.domain.model.BatteryCapacity
import ru.sodovaya.volty.domain.model.CapacityUnit
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.nameplateAh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.io.File
import java.nio.file.Files

class SqlDelightVehicleEnergyStoreTest {

    private fun newStore(): SqlDelightVehicleEnergyStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        return SqlDelightVehicleEnergyStore(VoltyDatabaseProvider(driver))
    }

    private val memory = EnergyMemory(
        learned = LearnedCapacity(usableAh = 17.5f, weight = 120f, rides = 3),
        resistanceOhm = 0.045f,
        counter = PersistedCharge(percent = 80.6f, atMillis = 1_700_000_000_000L)
    )

    @Test
    fun nameplateAh_converts_watt_hours_through_nominal_pack_voltage() {
        val ah = BatteryCapacity(1800f, CapacityUnit.WH).nameplateAh(24, Chemistry.LI_ION_NMC)!!
        assertEquals(20.27f, ah, 0.01f)
        assertEquals(20f, BatteryCapacity(20f, CapacityUnit.AH).nameplateAh(0, Chemistry.LI_ION_NMC))
        assertNull(BatteryCapacity(1800f, CapacityUnit.WH).nameplateAh(0, Chemistry.LI_ION_NMC))
        assertNull(BatteryCapacity(1800f, CapacityUnit.WH).nameplateAh(-3, Chemistry.LI_ION_NMC))
    }

    @Test
    fun energy_memory_round_trips() = runTest {
        val store = newStore()
        store.save("v1", 24, Chemistry.LI_ION_NMC, memory)
        assertEquals(memory, store.load("v1", 24, Chemistry.LI_ION_NMC))
    }

    @Test
    fun upgradeFrom29PreservesExistingLearningAndPersistsRecoveryEvidence() = runTest {
        val source = assertNotNull(listOf(
            File("src/commonMain/sqldelight/databases/29.db"),
            File("composeApp/src/commonMain/sqldelight/databases/29.db"),
        ).firstOrNull { it.isFile })
        val copy = Files.createTempFile("volty-energy-v29-", ".db").toFile()
        try {
            source.copyTo(copy, overwrite = true)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${copy.absolutePath}")
            try {
                driver.execute(null, """
                    INSERT INTO VehicleEnergyRow VALUES
                    ('v1', 24, 'LI_ION_NMC', 17.5, 120, 3, 0.045, 80.6, 1700000000000)
                """.trimIndent(), 0)
                VoltyDatabase.Schema.migrate(driver, 29, VoltyDatabase.Schema.version)
                val provider = VoltyDatabaseProvider(driver)
                val store = SqlDelightVehicleEnergyStore(provider)
                assertEquals(memory, store.load("v1", 24, Chemistry.LI_ION_NMC))

                val pending = memory.copy(
                    capacityCandidate = CapacityCandidate(LearnedCapacity(36f, 48f, 2), 35f, 37f),
                    lastCapacityDecision = CapacityLearnDecision.DIVERGENT,
                )
                store.save("v1", 24, Chemistry.LI_ION_NMC, pending)
                assertEquals(pending, SqlDelightVehicleEnergyStore(provider).peek("v1")?.memory)
                store.reset("v1")
                assertNull(store.peek("v1"))
            } finally { driver.close() }
        } finally { copy.delete() }
    }

    @Test
    fun an_unknown_vehicle_loads_empty_memory() = runTest {
        assertEquals(EnergyMemory(null, null, null), newStore().load("nope", 24, Chemistry.LI_ION_NMC))
    }

    @Test
    fun partial_memory_round_trips_with_nulls_kept_null() = runTest {
        val store = newStore()
        val partial = EnergyMemory(null, 0.05f, null)
        store.save("v1", 24, Chemistry.LI_ION_NMC, partial)
        assertEquals(partial, store.load("v1", 24, Chemistry.LI_ION_NMC))
    }

    @Test
    fun cellCountChangeForgetsLearned() = runTest {
        val store = newStore()
        store.save("v1", 24, Chemistry.LI_ION_NMC, memory)
        assertEquals(EnergyMemory(null, null, null), store.load("v1", 20, Chemistry.LI_ION_NMC))
        // The stale row was deleted, not merely hidden: the original shape finds nothing either.
        assertEquals(EnergyMemory(null, null, null), store.load("v1", 24, Chemistry.LI_ION_NMC))
    }

    @Test
    fun chemistryChangeForgetsLearned() = runTest {
        val store = newStore()
        store.save("v1", 24, Chemistry.LI_ION_NMC, memory)
        assertEquals(EnergyMemory(null, null, null), store.load("v1", 24, Chemistry.LIFEPO4))
        assertEquals(EnergyMemory(null, null, null), store.load("v1", 24, Chemistry.LI_ION_NMC))
    }

    /**
     * The store's API takes no cell bounds at all, so a bounds change cannot reach the key:
     * saving and loading with the same cells and chemistry round-trips regardless of what the
     * vehicle's AlertConfig says (deliberately not routed through the vehicle repository).
     */
    @Test
    fun boundsAreNotPartOfTheKey() = runTest {
        val store = newStore()
        store.save("v1", 24, Chemistry.LI_ION_NMC, memory)
        assertEquals(memory, store.load("v1", 24, Chemistry.LI_ION_NMC))
        assertEquals(memory, store.load("v1", 24, Chemistry.LI_ION_NMC))
    }

    @Test
    fun resetDeletes() = runTest {
        val store = newStore()
        store.save("v1", 24, Chemistry.LI_ION_NMC, memory)
        store.save("v2", 24, Chemistry.LI_ION_NMC, memory)
        store.reset("v1")
        assertEquals(EnergyMemory(null, null, null), store.load("v1", 24, Chemistry.LI_ION_NMC))
        assertEquals(memory, store.load("v2", 24, Chemistry.LI_ION_NMC))
    }

    @Test
    fun non_finite_values_are_stored_as_not_earned() = runTest {
        val store = newStore()
        store.save(
            "v1", 24, Chemistry.LI_ION_NMC,
            EnergyMemory(
                learned = LearnedCapacity(Float.NaN, 10f, 2),
                resistanceOhm = Float.POSITIVE_INFINITY,
                counter = PersistedCharge(Float.NaN, 5L)
            )
        )
        assertEquals(EnergyMemory(null, null, null), store.load("v1", 24, Chemistry.LI_ION_NMC))
    }

    @Test
    fun peek_returns_the_row_under_its_own_key_and_deletes_nothing() = runTest {
        val store = newStore()
        assertNull(store.peek("nope"))
        store.save("v1", 24, Chemistry.LI_ION_NMC, memory)
        assertEquals(EnergyRecord(memory, 24, Chemistry.LI_ION_NMC), store.peek("v1"))
        // Peeking twice finds it twice: a read that deleted would fail here.
        assertEquals(EnergyRecord(memory, 24, Chemistry.LI_ION_NMC), store.peek("v1"))
        // And the row is still there for the tracker's own keyed load.
        assertEquals(memory, store.load("v1", 24, Chemistry.LI_ION_NMC))
    }
}
