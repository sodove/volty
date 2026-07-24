package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class VehicleConnectionMotionTest {
    private var now = Instant.fromEpochMilliseconds(0)
    private fun conn(controllers: List<Controller>, latent: List<Controller> = emptyList()): VehicleConnection {
        var last: VehicleData? = null
        return VehicleConnection(
            packs = emptyList(),
            controllers = controllers,
            latentControllers = latent,
            topology = PackTopology.PARALLEL,
            onVehicleData = { last = it },
            clock = { now }
        )
    }
    private fun ctrl(i: Int) = Controller(i, "c$i", ControllerType.VESC, "A$i")

    @Test fun motion_sample_reaches_aggregate() {
        val c = conn(listOf(ctrl(0)))
        val vd = c.submitMotion(0, ControllerData(speedKmh = 25f, speedSource = SpeedSource.REPORTED, isConnected = true))
        assertEquals(25f, vd.motion.speedKmh)
        assertEquals(1, vd.controllers.size)
        assertTrue(vd.controllers[0].isOnline)
    }

    @Test fun latent_controller_materialises_on_first_sample() {
        val c = conn(controllers = emptyList(), latent = listOf(ctrl(1)))
        val before = c.snapshot()
        assertEquals(0, before.controllers.size)
        val vd = c.submitMotion(1, ControllerData(speedKmh = 5f, isConnected = true))
        assertEquals(1, vd.controllers.size)
        assertEquals(1, vd.controllers[0].controller.index)
    }

    @Test fun stale_controller_marked_offline_by_a_later_submit() {
        val c = conn(listOf(ctrl(0), ctrl(1)))
        c.submitMotion(0, ControllerData(speedKmh = 10f, isConnected = true))
        now += kotlin.time.Duration.parse("${BleConfig.packOfflineAfterMs + 100}ms")
        val vd = c.submitMotion(1, ControllerData(speedKmh = 11f, isConnected = true))
        val c0 = vd.controllers.first { it.controller.index == 0 }
        assertTrue(!c0.isOnline)
    }
}
