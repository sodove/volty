package ru.sodovaya.volty.backend.nearby

/** Explicit channel mutations only; cleanup stays durable and outside HTTP/SQL network calls. */
interface ChannelCommandsEndpointPort {
    fun create(actor: String, community: String, channel: String, name: String): ChannelCreationResult
    fun delete(actor: String, operation: String, community: String, channel: String): ChannelDeletionResult
}
class JdbcChannelCommandsEndpointPort(
    private val creation: JdbcChannelCreationRepository,
    private val deletion: JdbcChannelDeletionRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ChannelCommandsEndpointPort {
    override fun create(actor: String, community: String, channel: String, name: String) = creation.create(actor, community, channel, name, now)
    override fun delete(actor: String, operation: String, community: String, channel: String) = deletion.deleteChannel(actor, operation, community, channel, now)
}
