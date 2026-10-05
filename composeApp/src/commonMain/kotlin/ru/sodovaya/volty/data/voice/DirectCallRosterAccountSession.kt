package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.voice.*

/** Read permission belongs to one captured account/subscription, separate from media cleanup. */
internal class DirectCallRosterAccountSession private constructor(
    private val account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String,
    private val tag: DirectCallRosterTag, monotonicMillis: () -> Long,
) : DirectCallRosterPort {
    private val retired = MutableStateFlow(false)
    private val closed = MutableStateFlow(false)
    private val cleanupFailure = MutableStateFlow(false)
    val cleanupFailed = cleanupFailure.asStateFlow()
    private val http = DirectCallRosterHttpPort(client, baseUrl, account.userId.value, {
        ensureLive()
        account.bearer().also {ensureLive()}
    }, monotonicMillis)
    private val guardian = account.scope().launch(start = CoroutineStart.UNDISPATCHED) {
        try {awaitCancellation()} finally {fenceAndClose()}
    }

    override suspend fun snapshot(tag: DirectCallRosterTag): DirectCallRosterReply {
        ensureLive()
        require(tag == this.tag) {"Call roster subscription does not match"}
        return try {
            http.snapshot(tag).also {ensureLive()}
        } catch (cancelled: CancellationException) {throw cancelled}
          catch (failure: Exception) {ensureLive(); throw failure}
    }
    fun retire() {
        fenceAndClose()
        guardian.cancel()
    }
    private fun fenceAndClose() {
        retired.value = true
        if (closed.compareAndSet(false, true)) {
            try {http.close()} catch (_: Throwable) {cleanupFailure.value = true}
        }
    }
    private suspend fun ensureLive() {
        currentCoroutineContext().ensureActive()
        if (retired.value || !account.job.isActive) throw CancellationException("Call roster account retired")
    }
    companion object {
        fun create(account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String,
                   tag: DirectCallRosterTag, monotonicMillis: () -> Long): DirectCallRosterAccountSession {
            check(account.job.isActive) {"Call roster account retired"}
            nearbyHttpsApiBaseUrl(baseUrl)
            invitationDraftId(account.userId.value); invitationDraftId(tag.callId); invitationDraftId(tag.peerId)
            require(tag.peerId != account.userId.value)
            return DirectCallRosterAccountSession(account, client, baseUrl, tag, monotonicMillis)
        }
    }
}
