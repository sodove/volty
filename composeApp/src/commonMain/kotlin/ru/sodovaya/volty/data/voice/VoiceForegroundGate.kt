package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

internal class ForegroundTicket(val owner: Any, val id: String, private val onLost: () -> Unit) {
    internal fun lose() = onLost()
}

/** Serial Main authority only; platform startForeground must precede acknowledge.
 * Neither revocation nor readiness is a native/remote cleanup acknowledgement. */
internal class ForegroundGate(private val permission: () -> Boolean, private val nextId: () -> String) {
    private val selected = MutableStateFlow<ForegroundTicket?>(null)
    val current = selected.asStateFlow()
    private val acknowledged = MutableStateFlow<ForegroundTicket?>(null)
    val ready = acknowledged.asStateFlow()
    private var incarnation: Any? = null
    private val issuedIds = mutableSetOf<String>()

    fun request(owner: Any, onLost: () -> Unit): ForegroundTicket {
        val previous = selected.value
        if (previous?.owner === owner) return previous
        val id = nextId()
        invitationDraftId(id)
        require(issuedIds.add(id)) { "Foreground ticket cannot be reused" }
        revoke()
        return ForegroundTicket(owner, id, onLost).also { selected.value = it }
    }

    fun allows(owner: Any): Boolean {
        val ticket = selected.value ?: return false
        return ticket.owner === owner && acknowledged.value === ticket && incarnation != null && permissionAvailable()
    }

    fun acknowledge(ticket: ForegroundTicket, service: Any): Boolean {
        if (selected.value !== ticket || !permissionAvailable() || incarnation?.let {it !== service} == true) return false
        incarnation = service
        acknowledged.value = ticket
        return true
    }

    fun find(id: String): ForegroundTicket? = selected.value?.takeIf {it.id == id}

    fun stop(ticket: ForegroundTicket): Boolean {
        if (selected.value !== ticket) return false
        revoke()
        return true
    }

    fun destroyed(service: Any): Boolean {
        if (incarnation !== service) return false
        revoke()
        return true
    }

    fun refreshPermissions() { if (!permissionAvailable()) revoke() }

    private fun revoke() {
        val previous = selected.value
        incarnation = null
        acknowledged.value = null
        selected.value = null
        // Original cleanup is nonblocking and runs only after every positive gate is fenced.
        previous?.lose()
    }

    private fun permissionAvailable(): Boolean = try { permission() } catch (_: Exception) { false }
}

// Compatibility names retain the existing voice authority while location uses its own instance.
internal typealias VoiceForegroundTicket = ForegroundTicket
internal typealias VoiceForegroundGate = ForegroundGate
