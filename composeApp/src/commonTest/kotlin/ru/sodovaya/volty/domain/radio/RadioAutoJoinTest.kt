package ru.sodovaya.volty.domain.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioAutoJoinTest {
    private fun ok(
        enabled: Boolean = true,
        state: RadioState = RadioState.Idle,
        visible: Boolean = true,
        permitted: Boolean = true,
        now: Long = 1_000,
        suppressedUntil: Long = 0,
        serverVoice: Boolean = false,
    ) = shouldAutoJoin(enabled, state, visible, permitted, now, suppressedUntil, serverVoice)

    @Test
    fun joinsOnlyWhenEnabledIdleVisiblePermitted() {
        assertTrue(ok())
        assertFalse(ok(enabled = false))
        assertFalse(ok(visible = false)) // R20: never from the background
        assertFalse(ok(permitted = false))
        assertFalse(ok(state = RadioState.Hub("c")))
        assertFalse(ok(state = RadioState.Starting("c")))
        assertFalse(ok(state = RadioState.Error("c", RadioError.NOT_FOUND)))
        assertFalse(ok(state = RadioState.Lost("c", 5)))
    }

    @Test
    fun autoJoinNeverWhileServerVoiceActive() {
        // Joining would disconnect the server voice the rider just started: a ping-pong (I1).
        assertFalse(ok(serverVoice = true))
        assertTrue(ok(serverVoice = false))
    }

    @Test
    fun manualLeaveSuppressesUntilDeadline() {
        assertFalse(ok(now = 1_000, suppressedUntil = 2_000))
        assertTrue(ok(now = 2_000, suppressedUntil = 2_000))
    }

    @Test
    fun permissionsPerSdk() {
        val mic = "android.permission.RECORD_AUDIO"
        val wifi = "android.permission.NEARBY_WIFI_DEVICES"
        val fine = "android.permission.ACCESS_FINE_LOCATION"
        assertEquals(emptyList(), radioRequiredPermissions(28, scanning = true))
        // Wi-Fi Direct itself needs fine location below Android 13, so it is not an auto-join extra there.
        assertEquals(listOf(mic, fine), radioRequiredPermissions(29, scanning = false))
        assertEquals(listOf(mic, fine), radioRequiredPermissions(32, scanning = true))
        assertEquals(listOf(mic, wifi), radioRequiredPermissions(33, scanning = false))
        assertEquals(listOf(mic, wifi, fine), radioRequiredPermissions(34, scanning = true))
    }
}
