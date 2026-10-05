package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.voice.*

/** Read lifetime for one earned call session; never owns the account or media cleanup. */
internal class AuthenticatedCallRosterOwner private constructor(
    val tag: DirectCallRosterTag,
    val ownSession: VoiceSessionTag,
    private val reader: DirectCallRosterAccountSession,
    private val runtime: DirectCallRosterRuntime,
    account: SocialAuthenticatedAccount,
) {
    val state = runtime.state
    val readInFlight = runtime.readInFlight
    val cleanupFailed = reader.cleanupFailed
    private val guardian = account.scope().launch(start = CoroutineStart.UNDISPATCHED) {
        try {runtime.state.first {it.retired}} finally {reader.retire()}
    }

    suspend fun refresh(): Boolean = runtime.refresh()

    suspend fun close() = withContext(NonCancellable) {
        try {runtime.close()} finally {
            reader.retire()
            guardian.cancelAndJoin()
        }
    }

    companion object {
        suspend fun create(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String,
            tag: DirectCallRosterTag, own: VoiceSessionTag, dispatcher: CoroutineDispatcher,
            pulses: Flow<Unit>, clock: () -> Long): AuthenticatedCallRosterOwner {
            currentCoroutineContext().ensureActive()
            check(account.job.isActive) {"Call roster account retired"}
            require(dispatcher.isDispatchNeeded(account.scope().coroutineContext)) {"Queued serial dispatcher required"}
            invitationDraftId(own.sessionId.value)
            val reader = DirectCallRosterAccountSession.create(account, client, baseUrl, tag, clock)
            try {
                val port = object : DirectCallRosterPort {
                    override suspend fun snapshot(tag: DirectCallRosterTag): DirectCallRosterReply {
                        val reply = reader.snapshot(tag)
                        if (reply is DirectCallRosterReply.Received &&
                            reply.observation.peers.singleOrNull {it.actorId == account.userId.value}?.sessionId != own.sessionId) {
                            return DirectCallRosterReply.Failed(VoiceRosterFailure.INVALID_RESPONSE)
                        }
                        return reply
                    }
                }
                val runtime = DirectCallRosterRuntime(account.scope(), dispatcher, tag, account.userId.value, port, pulses, clock)
                return AuthenticatedCallRosterOwner(tag, own, reader, runtime, account)
            } catch (failure: Throwable) {
                reader.retire()
                throw failure
            }
        }
    }
}
