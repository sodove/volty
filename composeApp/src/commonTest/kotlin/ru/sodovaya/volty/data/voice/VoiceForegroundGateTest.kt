package ru.sodovaya.volty.data.voice

import kotlin.test.*

class VoiceForegroundGateTest {
    private fun id(n: Int) = "10000000-0000-0000-0000-${n.toString().padStart(12, '0')}"

    @Test fun pendingTicketAndMissingPermissionCannotAdmitCapture() {
        var permission = false
        val gate = VoiceForegroundGate({permission}, {id(1)})
        val owner = Any(); val service = Any()
        val ticket = gate.request(owner) {}
        assertFalse(gate.allows(owner))
        assertFalse(gate.acknowledge(ticket, service))
        permission = true
        assertFalse(gate.allows(owner))
        assertTrue(gate.acknowledge(ticket, service))
        assertTrue(gate.allows(owner))
        assertFalse(gate.allows(Any()))
        assertSame(ticket, gate.find(ticket.id))
    }

    @Test fun staleServiceAndNotificationCannotStopReplacementOwner() {
        var n = 0
        val gate = VoiceForegroundGate({true}, {id(++n)})
        val old = Any(); val fresh = Any(); val oldService = Any(); val newService = Any()
        var oldLost = 0; var newLost = 0
        val oldTicket = gate.request(old) {assertFalse(gate.allows(old)); oldLost++}
        assertTrue(gate.acknowledge(oldTicket, oldService))
        val ticket = gate.request(fresh) {assertFalse(gate.allows(fresh)); newLost++}
        assertEquals(1, oldLost)
        assertTrue(gate.acknowledge(ticket, newService))
        assertFalse(gate.acknowledge(oldTicket, oldService))
        assertFalse(gate.destroyed(oldService))
        assertFalse(gate.stop(oldTicket))
        assertNull(gate.find(oldTicket.id))
        assertTrue(gate.allows(fresh)); assertEquals(0, newLost)
        assertTrue(gate.destroyed(newService))
        assertFalse(gate.allows(fresh)); assertEquals(1, newLost)
    }

    @Test fun permissionLossFencesBeforeOriginalCleanupAndDuplicateRequestReusesTicket() {
        var permission = true; var ids = 0; var lost = 0
        val gate = VoiceForegroundGate({permission}, {id(++ids)})
        val owner = Any(); val service = Any()
        val ticket = gate.request(owner) {assertFalse(gate.allows(owner)); lost++}
        assertSame(ticket, gate.request(owner) {error("Must retain original cleanup")})
        assertEquals(1, ids)
        assertTrue(gate.acknowledge(ticket, service))
        permission = false
        assertFalse(gate.allows(owner))
        gate.refreshPermissions()
        assertEquals(1, lost)
        assertFalse(gate.stop(ticket))
        assertFalse(gate.destroyed(service))
    }

    @Test fun malformedOrReusedTicketCannotReplaceEarnedOwner() {
        var proposed = id(1)
        val gate = VoiceForegroundGate({true}, {proposed})
        val owner = Any(); val ticket = gate.request(owner) {}
        assertTrue(gate.acknowledge(ticket, Any()))
        proposed = "invalid"
        assertFailsWith<IllegalArgumentException> {gate.request(Any()) {}}
        assertTrue(gate.allows(owner))
        proposed = id(1)
        assertFailsWith<IllegalArgumentException> {gate.request(Any()) {}}
        assertTrue(gate.allows(owner))
    }
}
