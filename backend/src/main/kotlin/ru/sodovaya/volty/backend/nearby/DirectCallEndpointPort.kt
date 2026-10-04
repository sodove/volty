package ru.sodovaya.volty.backend.nearby

interface DirectCallEndpointPort {
    fun create(actor: String, callee: String, call: String, key: String, operation: String): DirectCallDecision
    fun status(actor: String, call: String): DirectCallDecision
    fun incoming(actor: String, limit: Int, cursor: String?): IncomingCallPage
    fun accept(actor: String, call: String, operation: String, revision: Long): DirectCallAction
    fun resume(actor: String, call: String, operation: String): DirectCallAction
    fun decline(actor: String, call: String): DirectCallDecision
    fun stop(actor: String, call: String): DirectCallDecision
    fun cancelOutgoing(actor: String, callee: String, call: String, key: String, operation: String): DirectCallDecision
}

class DirectCallServiceEndpointPort(private val service: DirectCallService) : DirectCallEndpointPort {
    override fun create(actor: String, callee: String, call: String, key: String, operation: String) = service.create(actor, callee, call, key, operation)
    override fun status(actor: String, call: String) = service.status(actor, call)
    override fun incoming(actor: String, limit: Int, cursor: String?) = service.incoming(actor, limit, cursor)
    override fun accept(actor: String, call: String, operation: String, revision: Long) = service.accept(actor, call, operation, revision)
    override fun resume(actor: String, call: String, operation: String) = service.resume(actor, call, operation)
    override fun decline(actor: String, call: String) = service.decline(actor, call)
    override fun stop(actor: String, call: String) = service.stop(actor, call)
    override fun cancelOutgoing(actor: String, callee: String, call: String, key: String, operation: String) = service.cancelOutgoing(actor, callee, call, key, operation)
}
