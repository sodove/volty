package ru.sodovaya.volty.backend.nearby

/** Membership reads/create/join only: no voice, sharing or removal side effects. */
interface CommunityEndpointPort {
    fun catalog(actor: String, query: String, after: String?, limit: Int): List<CommunitySummary>
    fun memberships(actor: String, after: String?, limit: Int): List<CommunitySummary>
    fun detail(actor: String, community: String): CommunitySnapshot?
    fun create(actor: String, community: String, name: String, description: String, visibility: CommunityVisibility): CommunityWriteResult
    fun join(actor: String, community: String): CommunityWriteResult
}

class JdbcCommunityEndpointPort(private val repository: JdbcCommunityRepository,
                                private val now: () -> Long = System::currentTimeMillis) : CommunityEndpointPort {
    override fun catalog(actor: String, query: String, after: String?, limit: Int) = repository.catalog(actor, query, after, limit)
    override fun memberships(actor: String, after: String?, limit: Int) = repository.memberships(actor, after, limit)
    override fun detail(actor: String, community: String) = repository.detail(actor, community)
    override fun create(actor: String, community: String, name: String, description: String, visibility: CommunityVisibility) =
        repository.create(actor, community, name, description, visibility, now)
    override fun join(actor: String, community: String) = repository.joinPublic(actor, community, now)
}
