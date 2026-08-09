package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.VescProtocol
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionSessionWriteGuardTest {

    @Test
    fun `begode protocol is never allowed to write even without live ad evidence`() {
        assertFalse(shouldWriteProtocolCommand(BegodeProtocol(), liveBegodeAdvertisement = false))
    }

    @Test
    fun `live begode advertisement blocks a remembered command protocol`() {
        // This is the precedence failure: the address memory can select VESC,
        // but the fresh advertisement still identifies the wheel.
        assertFalse(shouldWriteProtocolCommand(VescProtocol(), liveBegodeAdvertisement = true))
    }

    @Test
    fun `a non begode protocol on a non begode advertisement may write`() {
        assertTrue(shouldWriteProtocolCommand(VescProtocol(), liveBegodeAdvertisement = false))
    }
}
