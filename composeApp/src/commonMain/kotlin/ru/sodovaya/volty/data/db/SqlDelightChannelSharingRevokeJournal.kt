package ru.sodovaya.volty.data.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.data.voice.*
import ru.sodovaya.volty.domain.nearby.*
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

/** Borrows actual database. Inserts and exact original ACK deletion are transactional. */
internal class SqlDelightChannelSharingRevokeJournal(provider: VoltyDatabaseProvider,
    private val dispatcher: CoroutineDispatcher): ChannelSharingRevokeJournal {
    private val database=provider.database
    private val queries=database.channelSharingRevokeRowQueries
    override suspend fun record(intent: ChannelSharingRevokeIntent): ChannelSharingRevokeIntent = withContext(dispatcher) {
        database.transactionWithResult {
            queries.selectOriginal(intent.actorId,intent.tag.sessionId,intent.tag.generation).executeAsOneOrNull()?.let {
                return@transactionWithResult it.intent()
            }
            check(queries.countActor(intent.actorId).executeAsOne()<256) {"Original sharing cleanup queue full"}
            queries.insertOriginal(intent.actorId,intent.tag.sessionId,intent.tag.generation,intent.requestId)
            queries.selectOriginal(intent.actorId,intent.tag.sessionId,intent.tag.generation).executeAsOne().intent()
        }
    }
    override suspend fun pending(actorId: String,limit: Int): List<ChannelSharingRevokeIntent> = withContext(dispatcher) {
        invitationDraftId(actorId);require(limit in 1..64)
        queries.selectPending(actorId,limit.toLong()).executeAsList().map {it.intent()}
    }
    override suspend fun acknowledge(intent: ChannelSharingRevokeIntent,authenticatedActorId: String,
        reply: ChannelSharingReply): Boolean = withContext(dispatcher) {
        invitationDraftId(authenticatedActorId)
        if(authenticatedActorId!=intent.actorId || reply !is ChannelSharingReply.Negative || reply.tag!=intent.tag) return@withContext false
        database.transactionWithResult {
            val current=queries.selectOriginal(intent.actorId,intent.tag.sessionId,intent.tag.generation).executeAsOneOrNull()
            if(current?.requestId!=intent.requestId) false
            else {
                queries.deleteAcknowledged(intent.actorId,intent.tag.sessionId,intent.tag.generation,intent.requestId)
                queries.selectOriginal(intent.actorId,intent.tag.sessionId,intent.tag.generation).executeAsOneOrNull()==null
            }
        }
    }
    private fun ChannelSharingRevokeRow.intent()=ChannelSharingRevokeIntent(actorId,ChannelSharingTag(sessionId,generation),requestId)
}
