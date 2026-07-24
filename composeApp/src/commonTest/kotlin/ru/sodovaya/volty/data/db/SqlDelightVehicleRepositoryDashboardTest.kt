package ru.sodovaya.volty.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepositoryDashboardTest {

    private fun newInMemoryRepo(): SqlDelightVehicleRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        return SqlDelightVehicleRepository(VoltyDatabaseProvider(driver))
    }

    @Test fun dashboard_config_round_trips() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(
            Vehicle(
                id = "v1", name = "Wheel", iconKey = "generic",
                packs = listOf(Pack(0, "P", BmsType.ANT_BMS, "A")),
                controllers = listOf(Controller(0, "C", ControllerType.VESC, "C0")),
                chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now(),
                dashboardStyle = DashboardStyle.CLASSIC,
                secondaryGauge = SecondaryGauge.DUTY
            )
        )
        val back = repo.get("v1")!!
        assertEquals(DashboardStyle.CLASSIC, back.dashboardStyle)
        assertEquals(SecondaryGauge.DUTY, back.secondaryGauge)
    }

    @Test fun a_vehicle_saved_without_a_style_follows_the_app_default() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(
            Vehicle(
                id = "v2", name = "Scooter", iconKey = "generic",
                packs = listOf(Pack(0, "P", BmsType.ANT_BMS, "A")),
                chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now()
            )
        )
        val back = repo.get("v2")!!
        assertNull(back.dashboardStyle)                       // null = follow app default
        assertEquals(SecondaryGauge.DUTY, back.secondaryGauge) // enum default
    }
}
