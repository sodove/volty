package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

internal class VoiceForegroundTicket(val owner: Any, val id: String, private val onLost: () -> Unit) {
    internal fun lose() = onLost()
}

/** Serial Main authority only; platform startForeground must precede acknowledge.
 * Neither revocation nor readiness is a native/remote cleanup acknowledgement. */
internal class VoiceForegroundGate(private val permission: () -> Boolean, private val nextId: () -> String) {
    private val selected = MutableStateFlow<VoiceForegroundTicket?>(null)
    val current = selected.asStateFlow()
    private val acknowledged = MutableStateFlow<VoiceForegroundTicket?>(null)
    val ready = acknowledged.asStateFlow()
    private var incarnation: Any? = null
    private val issuedIds = mutableSetOf<String>()

    fun request(owner: Any, onLost: () -> Unit): VoiceForegroundTicket {
        val previous = selected.value
        if (previous?.owner === owner) return previous
        val id = nextId()
        invitationDraftId(id)
        require(issuedIds.add(id)) { "Foreground ticket cannot be reused" }
        revoke()
        return VoiceForegroundTicket(owner, id, onLost).also { selected.value = it }
    }

    fun allows(owner: Any): Boolean {
        val ticket = selected.value ?: return false
        return ticket.owner === owner && acknowledged.value === ticket && incarnation != null && permissionAvailable()
    }

    fun acknowledge(ticket: VoiceForegroundTicket, service: Any): Boolean {
        if (selected.value !== ticket || !permissionAvailable() || incarnation?.let {it !== service} == true) return false
        incarnation = service
        acknowledged.value = ticket
        return true
    }

    fun find(id: String): VoiceForegroundTicket? = selected.value?.takeIf {it.id == id}

    fun stop(ticket: VoiceForegroundTicket): Boolean {
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
