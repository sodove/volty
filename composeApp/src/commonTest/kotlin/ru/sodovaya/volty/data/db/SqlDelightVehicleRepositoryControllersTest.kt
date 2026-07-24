package ru.sodovaya.volty.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepositoryControllersTest {

    private fun newInMemoryRepo(): SqlDelightVehicleRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        return SqlDelightVehicleRepository(VoltyDatabaseProvider(driver))
    }

    @Test
    fun vehicle_with_controllers_round_trips() = runTest {
        val repo = newInMemoryRepo()
        val v = Vehicle(
            id = "v1", name = "Scooter", iconKey = "scooter",
            packs = listOf(Pack(0, "ANT", BmsType.ANT_BMS, "ANT0", cellCount = 20, aliasGroup = "batt")),
            controllers = listOf(
                Controller(
                    0, "uBox0", ControllerType.VESC, "UBOX0",
                    motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254, gearRatio = 1f)
                ),
                Controller(1, "uBox1", ControllerType.VESC, "UBOX1", providesDerivedBattery = false)
            ),
            chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now()
        )
        repo.upsert(v)
        val back = repo.get("v1")!!
        assertEquals(2, back.controllers.size)
        assertEquals("UBOX0", back.controllers[0].address)
        assertEquals(254, back.controllers[0].motor.wheelDiameterMm)
        assertEquals("batt", back.packs[0].aliasGroup)
    }
}
