package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.flow.StateFlow

internal data class ChannelLocationForegroundState(val wanted: Boolean=false,val retired: Boolean=false,
    val nativeOwned: Boolean=false,val nativeInFlight: Int=0,val cleanupFailed: Boolean=false) {
    init {require(nativeInFlight>=0)}
}
internal interface ChannelLocationForegroundOwner {
    val locationForegroundState: StateFlow<ChannelLocationForegroundState>
    /** Nonblocking original root hide submission; never borrowed voice disconnect. */
    fun requestHide()
}
internal typealias ChannelLocationForegroundTicket=ForegroundTicket
internal class ChannelLocationForegroundAuthority(permission: ()->Boolean,nextId: ()->String) {
    private val gate=ForegroundGate(permission,nextId)
    val current=gate.current
    val ready=gate.ready
    fun request(owner: ChannelLocationForegroundOwner): ChannelLocationForegroundTicket {
        check(owner.locationForegroundState.value.let {it.wanted && !it.retired}) {"Location owner retired"}
        return gate.request(owner,owner::requestHide)
    }
    fun allows(owner: ChannelLocationForegroundOwner)=
        owner.locationForegroundState.value.let {it.wanted && !it.retired} && gate.allows(owner)
    fun find(id: String)=gate.find(id)
    fun acknowledge(ticket: ChannelLocationForegroundTicket,service: Any): Boolean {
        val owner=ticket.owner as? ChannelLocationForegroundOwner ?: return false
        if(owner.locationForegroundState.value.let {!it.wanted || it.retired}) return false
        return gate.acknowledge(ticket,service)
    }
    fun stop(ticket: ChannelLocationForegroundTicket)=gate.stop(ticket)
    fun destroyed(service: Any)=gate.destroyed(service)
    fun refreshPermissions()=gate.refreshPermissions()
}
internal interface ChannelLocationForegroundPort {
    val current: StateFlow<ChannelLocationForegroundTicket?>
    fun request(owner: ChannelLocationForegroundOwner): ChannelLocationForegroundTicket
    suspend fun awaitReady(ticket: ChannelLocationForegroundTicket): Boolean
    fun allows(owner: ChannelLocationForegroundOwner): Boolean
    fun stop(ticket: ChannelLocationForegroundTicket): Boolean
}
internal fun canReleaseChannelLocationForeground(demanded: Boolean,owners: List<ChannelLocationForegroundState>)=
    !demanded && owners.all {!it.wanted && !it.nativeOwned && it.nativeInFlight==0 && !it.cleanupFailed}
