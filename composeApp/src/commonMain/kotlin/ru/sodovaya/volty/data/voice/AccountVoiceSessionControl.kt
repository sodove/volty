package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import ru.sodovaya.volty.data.nearby.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.voice.*

/** Typed admission shares one participant journal and lease sequence within an account. */
internal class AccountVoiceSessionControl private constructor(
    private val journal: VoiceSessionAccountControl,
    private val calls: DirectCallVoiceSessionControl,
) : VoiceSessionControlPort {
    fun registerOutgoing(call: String, callee: String, key: String, operation: VoiceOperationId) =
        calls.registerOutgoing(call, callee, key, operation)
    fun registerIncoming(call: DirectCallSnapshot, operation: VoiceOperationId) = calls.registerIncoming(call, operation)
    suspend fun incoming(after: String? = null, limit: Int = 20) = calls.incoming(after, limit)
    suspend fun callStatus(call: String) = calls.callStatus(call)

    private fun original(operation: VoiceOperationId): VoiceSessionControlPort = if (calls.owns(operation)) calls else journal
    private fun original(tag: VoiceSessionTag): VoiceSessionControlPort = if (calls.owns(tag)) calls else journal
    override fun rememberJoinIntent(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean) =
        original(operation).rememberJoinIntent(operation, generation, channel, replace)
    override suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean) =
        original(operation).join(operation, generation, channel, replace)
    override suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration) = original(operation).status(operation, generation)
    override suspend fun heartbeat(tag: VoiceSessionTag) = original(tag).heartbeat(tag)
    override suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration) = original(operation).cancel(operation, generation)
    override suspend fun disconnect(tag: VoiceSessionTag) = original(tag).disconnect(tag)
    fun retireAdmission() = calls.retireAdmission()
    fun close() = calls.close()

    companion object {
        suspend fun create(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String,
                           monotonicMillis: () -> Long): AccountVoiceSessionControl {
            val sequence = VoiceLeaseRequestSequence()
            val journal = VoiceSessionAccountControl.create(account, client, baseUrl, monotonicMillis, sequence)
            return try {
                AccountVoiceSessionControl(journal, DirectCallVoiceSessionControl.withJournal(account, client, baseUrl, monotonicMillis, sequence, journal))
            } catch (failure: Throwable) {
                try {journal.close()} catch (cleanup: Throwable) {failure.addSuppressed(cleanup)}
                throw failure
            }
        }
    }
}
