package ru.sodovaya.volty.backend.nearby

data class ChannelSharingDecision(val consent: ChannelSharingConsent, val remainingMillis: Long)
interface ChannelSharingEndpointPort {
    fun start(actor: String, session: String, generation: String, mode: ChannelSharingMode, ttl: ChannelSharingTtl): ChannelSharingDecision?
    fun status(actor: String, session: String, generation: String): ChannelSharingDecision?
    fun revoke(actor: String, session: String, generation: String): ChannelSharingDecision?
}

/** Actual consent SQL supplies authority; response credit never extends its original TTL. */
internal class JdbcChannelSharingEndpoint(private val repository: JdbcChannelSharingRepository,
    private val now: () -> Long = System::currentTimeMillis) : ChannelSharingEndpointPort {
    override fun start(actor: String, session: String, generation: String, mode: ChannelSharingMode, ttl: ChannelSharingTtl) =
        positive(repository.start(actor, session, generation, mode, ttl, now))
    override fun status(actor: String, session: String, generation: String) = positive(repository.active(actor, session, generation, now))
    override fun revoke(actor: String, session: String, generation: String) =
        repository.revoke(actor, session, generation, now)?.let {ChannelSharingDecision(it, 0)}

    private fun positive(consent: ChannelSharingConsent?): ChannelSharingDecision? {
        consent ?: return null
        val at = now().also {require(it >= 0)}
        val deadline = consent.deadlineEpochMillis ?: return null
        if (deadline <= at) return null
        return ChannelSharingDecision(consent, deadline - at)
    }
}
