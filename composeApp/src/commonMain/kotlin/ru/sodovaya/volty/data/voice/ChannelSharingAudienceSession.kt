package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

/** Automatic current-channel reads. Owns HTTP readers/timers, never voice, consent or location. */
internal class ChannelSharingAudienceSession private constructor(
    private val account: SocialAuthenticatedAccount, private val runtime: AuthenticatedVoiceRuntime,
    val original: VoiceSessionTag,
    private val control: ChannelSharingAccountControl,
    private val reader: ChannelSharingAudienceOwner, private val queued: CoroutineDispatcher,
) {
    private val lifetime = SupervisorJob(checkNotNull(account.retirementParentScope().coroutineContext[Job]))
    private val scope = CoroutineScope(account.retirementParentScope().coroutineContext + lifetime + queued)
    private val output = MutableStateFlow(ChannelSharingAudienceView())
    val state = output.asStateFlow()
    private var closed = false
    fun belongsTo(account: SocialAuthenticatedAccount, runtime: AuthenticatedVoiceRuntime, original: VoiceSessionTag) =
        this.account === account && this.runtime === runtime && this.original == original

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        scope.launch {
            combine(reader.state, reader.retired) { view, retired -> view to retired }.collect { (view, retired) ->
                if (retired) close()
                else if (!closed && account.job.isActive) output.value = view
            }
        }
        scope.launch {
            try {
                while (isActive) {
                    reader.refresh().await()
                    delay(READ_INTERVAL_MILLIS)
                }
            } finally { close() }
        }
    }

    suspend fun close(): Unit = withContext(NonCancellable + queued) {
        if (closed) return@withContext
        closed = true
        output.value = ChannelSharingAudienceView()
        lifetime.cancel()
        try { reader.close() } finally { control.close() }
    }

    companion object {
        private const val READ_INTERVAL_MILLIS = 2_000L
        suspend fun create(account: SocialAuthenticatedAccount, runtime: AuthenticatedVoiceRuntime,
            original: VoiceSessionTag, client: HttpClient, baseUrl: String,
            queued: CoroutineDispatcher, clock: () -> Long): ChannelSharingAudienceSession = withContext(queued) {
            currentCoroutineContext().ensureActive()
            val control = ChannelSharingAccountControl.create(account, runtime, original, client, baseUrl, queued, clock)
            var reader: ChannelSharingAudienceOwner? = null
            try {
                val created = ChannelSharingAudienceOwner.create(account, runtime, original, control, queued, clock)
                reader = created
                currentCoroutineContext().ensureActive()
                check(account.job.isActive && runtime.canShare(original)) { "Original channel session retired" }
                ChannelSharingAudienceSession(account, runtime, original, control, created, queued)
            } catch (failure: Throwable) {
                withContext(NonCancellable) { try { reader?.close() } finally { control.close() } }
                throw failure
            }
        }
    }
}
