package ru.sodovaya.volty.presentation.root.nearby

import kotlin.test.*

class NearbyPermissionGateTest {
    private val microphone = NearbyPermissionRequest(NearbyPermissionKind.MICROPHONE, "original-voice")
    private val replacement = NearbyPermissionRequest(NearbyPermissionKind.MICROPHONE, "new-voice")
    private val location = NearbyPermissionRequest(NearbyPermissionKind.LOCATION, "trip")

    @Test fun delayed_native_result_never_adopts_replacement_request() {
        val gate = NearbyPermissionGate()
        val issued = mutableListOf<NearbyPermissionRequest>()
        if (gate.reserve(microphone)) issued += microphone
        if (gate.reserve(replacement)) issued += replacement
        assertEquals(listOf(microphone), issued)
        assertTrue(gate.complete(true))
        assertEquals(NearbyPermissionDecision(microphone, true), gate.takeReady(true))
        assertNull(gate.takeReady(true))
        assertTrue(gate.reserve(replacement))
    }
    @Test fun microphone_and_location_dialogs_are_serial_and_denial_is_original() {
        val gate = NearbyPermissionGate()
        assertTrue(gate.reserve(microphone))
        assertFalse(gate.reserve(location))
        assertTrue(gate.complete(false))
        assertEquals(NearbyPermissionDecision(microphone, false), gate.takeReady(true))
        assertTrue(gate.reserve(location))
        assertTrue(gate.complete(true))
        assertEquals(NearbyPermissionDecision(location, true), gate.takeReady(true))
    }
    @Test fun result_waits_for_visible_activity_and_is_delivered_once() {
        val gate = NearbyPermissionGate()
        assertTrue(gate.reserve(location))
        assertTrue(gate.complete(true))
        assertNull(gate.takeReady(false))
        assertFalse(gate.reserve(replacement))
        assertEquals(NearbyPermissionDecision(location, true), gate.takeReady(true))
        assertFalse(gate.complete(false))
        assertNull(gate.takeReady(true))
    }
    @Test fun restored_native_dialog_keeps_original_identity_until_callback() {
        val gate = NearbyPermissionGate(NearbyPermissionSlot(microphone))
        assertFalse(gate.reserve(replacement))
        assertNull(gate.takeReady(true))
        assertTrue(gate.complete(false))
        assertEquals(NearbyPermissionDecision(microphone, false), gate.takeReady(true))
    }
    @Test fun completed_result_survives_recreation_without_second_native_launch() {
        val original = NearbyPermissionGate()
        assertTrue(original.reserve(location))
        assertTrue(original.complete(true))
        val restored = NearbyPermissionGate(original.slot)
        assertFalse(restored.reserve(location))
        assertFalse(restored.complete(false))
        assertEquals(NearbyPermissionDecision(location, true), restored.takeReady(true))
    }
    @Test fun disposal_discards_callback_without_starting_or_retargeting() {
        val gate = NearbyPermissionGate()
        assertTrue(gate.reserve(microphone))
        gate.close()
        assertNull(gate.slot)
        assertFalse(gate.complete(true))
        assertNull(gate.takeReady(true))
        assertFalse(gate.reserve(replacement))
    }
}
