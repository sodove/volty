package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.data.nearby.ChannelSharingHttpPort
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.ChannelSharingReply
import ru.sodovaya.volty.domain.nearby.ChannelSharingTag
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

/** Negative-only bounded recovery. Caller must serialize its live protection with admission. */
internal class ChannelSharingRevokeRecovery(private val client: HttpClient,private val baseUrl: String,
    private val queued: CoroutineDispatcher,private val clock: ()->Long,private val journal: ChannelSharingRevokeJournal) {
    private val base=nearbyHttpsApiBaseUrl(baseUrl)
    private val recovery=Mutex()
    suspend fun drain(account: SocialAuthenticatedAccount,protectedTags: Set<ChannelSharingTag>): Boolean {
        val protected=protectedTags.toSet()
        return withContext(queued) {recovery.withLock {
            invitationDraftId(account.userId.value)
            val parent=account.retirementParentScope()
            require(queued.isDispatchNeeded(parent.coroutineContext))
            ensureOriginal(account)
            val pending=journal.pending(account.userId.value,BATCH)
            ensureOriginal(account)
            require(pending.size<=BATCH && pending.all {it.actorId==account.userId.value}) {"Foreign or oversized recovery batch"}
            val original=ChannelSharingHttpPort(client,base,{
                ensureOriginal(account)
                account.bearer().also {ensureOriginal(account)}
            },clock)
            try {
                for(intent in pending) {
                    if(intent.tag in protected) continue
                    ensureOriginal(account)
                    val reply=original.revoke(intent.tag)
                    ensureOriginal(account)
                    if(reply !is ChannelSharingReply.Negative || reply.tag!=intent.tag) return@withLock false
                    val acknowledged=try {journal.acknowledge(intent,account.userId.value,reply)}
                        catch(cancelled: CancellationException) {throw cancelled}
                        catch(_: Exception) {false}
                    ensureOriginal(account)
                    if(!acknowledged) return@withLock false
                }
                val remaining=journal.pending(account.userId.value,BATCH)
                ensureOriginal(account)
                require(remaining.size<=BATCH && remaining.all {it.actorId==account.userId.value})
                // A full protected batch cannot prove that there are no rows behind it.
                remaining.size<BATCH && remaining.all {it.tag in protected}
            } finally {original.close()}
        }}
    }
    private suspend fun ensureOriginal(account: SocialAuthenticatedAccount) {
        currentCoroutineContext().ensureActive()
        val root=checkNotNull(account.retirementParentScope().coroutineContext[Job])
        if(root===account.job || !root.isActive || !account.job.isActive)
            throw CancellationException("Original recovery account retired")
    }
    private companion object {const val BATCH=64}
}
