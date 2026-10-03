package ru.sodovaya.volty.backend.nearby

/** Explicit, idempotent removal; adapters do not wait for SFU work inside SQL. */
interface CommunityRemovalEndpointPort {
    fun leave(actor: String, operation: String, community: String, successor: String?): CommunityDepartureResult
    fun delete(actor: String, operation: String, community: String): CommunityDeletionResult
}

class JdbcCommunityRemovalEndpointPort(
    private val membership: JdbcCommunityMembershipRepository,
    private val deletion: JdbcCommunityDeletionRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : CommunityRemovalEndpointPort {
    override fun leave(actor: String, operation: String, community: String, successor: String?) =
        membership.leave(actor, operation, community, successor, now)
    override fun delete(actor: String, operation: String, community: String) =
        deletion.deleteCommunity(actor, operation, community, now)
}
