package ru.sodovaya.volty.backend.nearby

import javax.sql.DataSource

/** One typed authority graph. Borrowed database/SFU; no I/O during assembly. */
internal class NearbyBackendComponents private constructor(
    val communities: CommunityEndpointPort,
    val members: CommunityMembersEndpointPort,
    val removal: CommunityRemovalEndpointPort,
    val channels: ChannelCommandsEndpointPort,
    val invitations: InvitationEndpointPort,
    val sessions: SessionEndpointPort,
    val roster: RosterEndpointPort,
    val calls: DirectCallEndpointPort,
    val sharing: ChannelSharingEndpointPort,
    val packets: ChannelSharingPacketEndpointPort,
    val mediaWebhook: MediaWebhookEndpoint,
    private val voice: SessionService,
    private val webhooks: MediaWebhookReconciler,
) : NearbyMaintenancePort {
    override fun reconcileSession()=voice.reconcileNext()
    override fun reconcileWebhook()=webhooks.reconcileNext()
    companion object {
        fun create(source: DataSource,sfu: SfuLeaseClient,issuer: SessionCredentialIssuer,
            verifier: MediaWebhookVerifier,now: ()->Long,singletonOrigin: Boolean=false): NearbyBackendComponents {
            require(sfu is SfuConnectedLeaseClient) {"Nearby requires connected-participant lease authority"}
            val access=JdbcVoiceAccess(now)
            val repository=JdbcSessionRepository(source,access)
            val rooms=JdbcVoiceRoomResolver(source,access)
            val voice=SessionService(repository,sfu,{actor,destination -> rooms.resolve(actor,destination,VoiceDestinationKind.CHANNEL)},
                issuer,now,singletonOrigin,rooms::resolve)
            val roster=JdbcRosterRepository(source,now)
            val inbox=JdbcMediaWebhookInbox(source)
            val calls=DirectCallService(JdbcDirectCallRingingRepository(source),JdbcDirectCallMediaRepository(source),repository,voice,sfu,now)
            return NearbyBackendComponents(
                JdbcCommunityEndpointPort(JdbcCommunityRepository(source),now),
                JdbcCommunityMembersRepository(source),
                JdbcCommunityRemovalEndpointPort(JdbcCommunityMembershipRepository(source),JdbcCommunityDeletionRepository(source),now),
                JdbcChannelCommandsEndpointPort(JdbcChannelCreationRepository(source),JdbcChannelDeletionRepository(source),now),
                JdbcInvitationEndpointPort(JdbcInvitationRepository(source),JdbcInvitationAcceptanceRepository(source),JdbcInvitationListingRepository(source),now),
                SessionEndpointService(voice,repository),
                RosterEndpointPort {actor,channel -> roster.authorizedSnapshot(actor,channel,access)},
                DirectCallServiceEndpointPort(calls),
                JdbcChannelSharingEndpoint(JdbcChannelSharingRepository(repository),now),
                JdbcChannelSharingPacketEndpoint(JdbcChannelSharingPackets(repository),now),
                MediaWebhookEndpoint(verifier,inbox),voice,MediaWebhookReconciler(inbox,repository,voice),
            )
        }
    }
}

/** The preflight transaction ends before SFU inspection; admission checks authority again. */
private class JdbcVoiceRoomResolver(private val source: DataSource,private val access: JdbcVoiceAccess) {
    fun resolve(actor: String,destination: String,kind: VoiceDestinationKind): String? {
        communityCanonicalId(actor);communityCanonicalId(destination)
        val room=when(kind) {
            VoiceDestinationKind.CHANNEL -> "volty-channel-$destination"
            VoiceDestinationKind.DIRECT_CALL -> "volty-call-$destination"
        }
        return source.connection.use {c ->
            c.autoCommit=false
            // Direct-call policy takes FOR SHARE of accepted friendship: a read-only SQL
            // transaction would reject it. This bounded transaction issues no data writes.
            try {
                c.createStatement().use {
                    it.execute("SET LOCAL lock_timeout = '5s'")
                    it.execute("SET LOCAL statement_timeout = '10s'")
                    it.execute("SET LOCAL idle_in_transaction_session_timeout = '15s'")
                }
                val allowed=access.allows(c,actor,destination,room,kind)
                c.commit();room.takeIf {allowed}
            } catch(failure: Throwable) {
                try {c.rollback()} catch(cleanup: Throwable) {failure.addSuppressed(cleanup)}
                throw failure
            }
        }
    }
}
