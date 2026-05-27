package com.volty.app.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Guard rail: the `guest:` id sentinel must round-trip through [Vehicle.isGuest]
 * for every code path that builds a transient vehicle, otherwise the saved-
 * vehicle store could silently capture a guest connection.
 */
@OptIn(ExperimentalTime::class)
class VehicleGuestTest {

    private fun vehicle(id: String) = Vehicle(
        id = id,
        name = "test",
        iconKey = "battery",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:BB:CC:DD:EE:FF",
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now()
    )

    @Test
    fun `guest prefix marks a vehicle as guest`() {
        val v = vehicle("guest:AA:BB:CC:DD:EE:FF")
        assertTrue(v.isGuest)
    }

    @Test
    fun `saved-vehicle uuid is not flagged guest`() {
        val v = vehicle("9f2a4b6c-1234-5678-9abc-def012345678")
        assertFalse(v.isGuest)
    }

    @Test
    fun `empty id is not flagged guest`() {
        val v = vehicle("")
        assertFalse(v.isGuest)
    }

    @Test
    fun `guest sentinel constant matches isGuest predicate`() {
        val v = vehicle(GUEST_VEHICLE_ID_PREFIX + "anything")
        assertTrue(v.isGuest)
    }
}
