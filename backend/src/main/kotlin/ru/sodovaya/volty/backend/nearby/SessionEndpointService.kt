package ru.sodovaya.volty.backend.nearby

/** Session lookup is actor-scoped; delayed old-session requests never address the current claim. */
class SessionEndpointService(private val service: SessionService, private val repository: SessionRepository) : SessionEndpointPort {
    override fun join(actor: String, operation: String, destination: String, replace: Boolean) = service.join(actor, operation, destination, replace)
    override fun status(actor: String, operation: String) = service.status(actor, operation)
    override fun cancel(actor: String, operation: String) = service.stop(actor, operation)
    override fun heartbeat(actor: String, session: String): SessionAction? =
        repository.findSession(actor, session)?.let { service.heartbeat(actor, it.operation.operationId) }
    override fun disconnect(actor: String, session: String): SessionAction? =
        repository.findSession(actor, session)?.let { service.stop(actor, it.operation.operationId) }
}
