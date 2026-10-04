package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.voice.*

/** Owns only read-only roster evidence. Account cancellation is not an audio stop ACK. */
internal class VoiceRosterAccountSession(
    private val account: SocialAuthenticatedAccount,
    client: HttpClient,
    baseUrl: String,
    private val tag: VoiceRosterTag,
    queuedDispatcher: CoroutineDispatcher,
    pulses: Flow<Unit>,
    monotonicMillis: () -> Long,
) {
    private val parent = account.scope()

    init {
        check(account.job.isActive) { RETIRED }
        invitationDraftId(account.userId.value)
        invitationDraftId(tag.channelId)
        nearbyHttpsApiBaseUrl(baseUrl)
        require(queuedDispatcher.isDispatchNeeded(parent.coroutineContext)) { "Queued serial dispatcher required" }
    }

    private val retired = MutableStateFlow(false)
    private val cleanupMutex = Mutex()
    private val cleanupFailure = MutableStateFlow(false)
    val cleanupFailed: StateFlow<Boolean> = cleanupFailure.asStateFlow()
    private val http = VoiceRosterHttpPort(client, baseUrl, {
        ensureLive()
        val token = account.bearer()
        ensureLive()
        token
    }, monotonicMillis)

    val port: VoiceRosterSnapshotPort = object : VoiceRosterSnapshotPort {
        override suspend fun snapshot(tag: VoiceRosterTag): VoiceRosterSnapshotReply {
            ensureLive()
            require(tag == this@VoiceRosterAccountSession.tag) { "Roster subscription does not match" }
            try {
                val reply = http.snapshot(tag)
                ensureLive()
                return reply
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureLive()
                throw failure
            }
        }
    }

    private val runtime = VoiceRosterRuntime(parent, queuedDispatcher, tag, port, pulses, monotonicMillis)
    val state = runtime.state
    private val guardian = parent.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            // An earlier revision waited only for account cancellation. Runtime-local
            // closure (e.g. access denied) must also revoke direct snapshot permission.
            state.first { it.retired }
        } finally {
            retired.value = true
            withContext(NonCancellable) { cleanup() }
        }
    }

    suspend fun refresh(): Boolean {
        currentCoroutineContext().ensureActive()
        if (retired.value || !account.job.isActive) return false
        return runtime.refresh()
    }

    suspend fun retire() {
        // Fence immediately, including when called from an already cancelled coroutine.
        retired.value = true
        guardian.cancel()
        withContext(NonCancellable) { cleanup() }
    }

    private suspend fun cleanup() = cleanupMutex.withLock {
        var failed = false
        try { http.close() } catch (_: Throwable) { failed = true }
        try { runtime.close() } catch (_: Throwable) { failed = true }
        cleanupFailure.value = failed
    }

    private suspend fun ensureLive() {
        currentCoroutineContext().ensureActive()
        if (retired.value || !account.job.isActive) throw CancellationException(RETIRED)
    }

    private companion object { const val RETIRED = "Authenticated roster retired" }
}
