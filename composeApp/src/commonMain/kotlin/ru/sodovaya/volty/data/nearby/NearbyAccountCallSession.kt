package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.voice.*

/** Borrows authentication and its Job; owns only the call HTTP child and publication boundary. */
class NearbyAccountCallSession private constructor(
    val actorId: String, private val binding: NearbyAccountBinding?,
    private val authority: SocialAuthenticatedAccount?, private val accountJob: Job?,
    client: HttpClient, baseUrl: String, monotonicMillis: () -> Long,
) {
    constructor(credentials: NearbyAccountCredentials, client: HttpClient, baseUrl: String,
        monotonicMillis: () -> Long, accountJob: Job? = null) :
        this(credentials.actorId, NearbyAccountBinding(credentials, accountJob), null, accountJob, client, baseUrl, monotonicMillis)

    internal constructor(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String, monotonicMillis: () -> Long) :
        this(account.userId.value, null, account, account.job, client, baseUrl, monotonicMillis)

    private val retired = MutableStateFlow(false)
    private val http = DirectCallHttpPort(actorId, client, baseUrl, {currentBearer()}, monotonicMillis)
    val calls: DirectCallControlPort = object : DirectCallControlPort {
        override suspend fun create(call: String, callee: String, key: String, operation: VoiceOperationId) = guarded {
            http.create(call, callee, key, operation)
        }
        override suspend fun cancelOutgoing(call: String, callee: String, key: String, operation: VoiceOperationId) = guarded {
            http.cancelOutgoing(call, callee, key, operation)
        }
        override suspend fun status(call: String) = guarded {http.status(call)}
        override suspend fun incoming(after: String?, limit: Int) = guarded {http.incoming(after, limit)}
        override suspend fun accept(call: String, operation: VoiceOperationId, generation: VoiceGeneration, revision: Long) = guarded {
            http.accept(call, operation, generation, revision)
        }
        override suspend fun resume(call: String, operation: VoiceOperationId, generation: VoiceGeneration) = guarded {
            http.resume(call, operation, generation)
        }
        override suspend fun decline(call: String) = guarded {http.decline(call)}
        override suspend fun stop(call: String) = guarded {http.stop(call)}
    }

    fun refresh(credentials: NearbyAccountCredentials) {
        require(credentials.actorId == actorId)
        check(!retired.value && accountJob?.isActive != false) {RETIRED}
        checkNotNull(binding) {"Authenticated account owns token refresh"}.refresh(credentials)
    }
    fun retire() {
        retired.value = true
        binding?.retire()
        http.close()
    }
    private suspend fun <T> guarded(call: suspend () -> T): T {
        ensureLive()
        try {
            val result = call()
            ensureLive()
            return result
        } catch (cancelled: CancellationException) {throw cancelled}
          catch (failure: Exception) {ensureLive(); throw failure}
    }
    private suspend fun ensureLive() {
        currentCoroutineContext().ensureActive()
        if (retired.value || accountJob?.isActive == false || (binding != null && binding.bearer() == null))
            throw CancellationException(RETIRED)
    }
    private suspend fun currentBearer(): String? {
        ensureLive()
        val token = if (binding != null) binding.bearer() else authority?.bearer()
        ensureLive()
        return token
    }
    private companion object {const val RETIRED = "Nearby call account retired"}
}