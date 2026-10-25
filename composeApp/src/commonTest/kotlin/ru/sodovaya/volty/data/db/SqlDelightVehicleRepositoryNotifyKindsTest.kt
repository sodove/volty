package ru.sodovaya.volty.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.alert.NotifyKind
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.singlePackVehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepositoryNotifyKindsTest {

    private fun newInMemoryRepo(): SqlDelightVehicleRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        return SqlDelightVehicleRepository(VoltyDatabaseProvider(driver))
    }

    private fun vehicle(id: String, notifyKinds: Set<NotifyKind>?): Vehicle = singlePackVehicle(
        id = id, name = id, iconKey = "generic", bmsType = BmsType.JK_BMS, bmsAddress = "AA:$id",
        chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now()
    ).copy(notifyKinds = notifyKinds)

    @Test
    fun `never chosen stays null and an empty choice stays empty`() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle("never", null))
        repo.upsert(vehicle("none", emptySet()))
        assertNull(repo.get("never")!!.notifyKinds, "null means the defaults apply, and must survive a round trip")
        assertEquals(emptySet(), repo.get("none")!!.notifyKinds, "empty is an explicit 'notify nothing'")
    }

    @Test
    fun `every kind round trips`() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(vehicle("all", NotifyKind.entries.toSet()))
        repo.upsert(vehicle("some", setOf(NotifyKind.SPEED, NotifyKind.BMS_FAULT)))
        assertEquals(NotifyKind.entries.toSet(), repo.get("all")!!.notifyKinds)
        assertEquals(setOf(NotifyKind.SPEED, NotifyKind.BMS_FAULT), repo.get("some")!!.notifyKinds)
    }

    @Test
    fun `a name this build does not know is skipped not fatal`() {
        assertEquals(setOf(NotifyKind.DUTY), decodeNotifyKinds("DUTY,FROM_THE_FUTURE"))
        assertEquals(emptySet(), decodeNotifyKinds(""))
    }

    @Test
    fun `equal sets encode to the same text`() {
        assertEquals(
            encodeNotifyKinds(setOf(NotifyKind.SPEED, NotifyKind.MOTOR_TEMP)),
            encodeNotifyKinds(setOf(NotifyKind.MOTOR_TEMP, NotifyKind.SPEED))
        )
    }
}
