package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount

internal interface PushTokenSource {
    fun setActive(active: Boolean) {}
    suspend fun current(): String?
    suspend fun renew(): String?
}
/** Process-owned metadata lane. Exact accounts own requests; no media or borrowed-root ownership. */
internal class PushRegistrationOwner(private val accounts: StateFlow<SocialAuthenticatedAccount?>,
    root: CoroutineScope, private val queued: CoroutineDispatcher, private val ticks: Flow<Unit>, private val hints: Flow<Unit>,
    private val ledger: PushInstallationLedger, private val tokens: PushTokenSource,
    private val clients: (SocialAuthenticatedAccount) -> PushRegistrationClient,
    private val registered: suspend (SocialAuthenticatedAccount) -> Unit) {
    private class Entry(val account: SocialAuthenticatedAccount, val client: PushRegistrationClient) {
        var runner: Job? = null
        var firstBinding = true
    }
    private val parent = requireNotNull(root.coroutineContext[Job]) {"Push registration requires application Job"}
    private val lifetime = SupervisorJob(parent)
    private val scope = CoroutineScope(root.coroutineContext + lifetime + queued)
    private var selected: Entry? = null
    private var started = false
    private var closed = false
    private var selection = 0L
    init {require(parent.isActive); require(queued.isDispatchNeeded(root.coroutineContext)) {"Queued serial dispatcher required"}}

    fun start() {
        if (started || closed || !lifetime.isActive) return
        started = true
        scope.launch {try {awaitCancellation()} finally {close()}}
        scope.launch {
            accounts.collect {account ->
                val previous = selected
                selected = null; previous?.runner?.cancel(); previous?.client?.retire()
                val epoch = ++selection
                setActive(account != null && account.job.isActive)
                if (account == null) {
                    // Initial null is not logout: repository startup may still be restoring credentials.
                    if (previous != null) scope.launch {
                        try {ledger.retireIf {liveRoot() && selection == epoch && accounts.value == null}}
                        catch (cancelled: CancellationException) {throw cancelled}
                        catch (_: Exception) { /* Later startup/registration reads the last durable state. */ }
                    }
                } else if (account.job.isActive && liveRoot()) {
                    val entry = Entry(account, clients(account)); selected = entry
                    entry.runner = CoroutineScope(account.scope().coroutineContext + queued).launch {
                        reconcile(entry)
                        merge(ticks, hints).collect {reconcile(entry)}
                    }
                }
            }
        }
    }

    private suspend fun reconcile(entry: Entry) {
        try {
            ensureLive(entry)
            val token = withTimeoutOrNull(10_000) {tokens.current()} ?: return
            ensureLive(entry)
            val snapshot = ledger.prepare(entry.account.userId.value, token, renew = entry.firstBinding)
            entry.firstBinding = false
            ensureLive(entry)
            val result = entry.client.submit(snapshot.intent)
            ensureLive(entry)
            when (result) {
                PushRegistrationResult.APPLIED -> {
                    if (ledger.acknowledge(snapshot)) {ensureLive(entry); registered(entry.account)}
                }
                PushRegistrationResult.STALE -> {
                    // A refreshed JWT has a new issued-at; the server correctly refuses same-version replay.
                    ledger.prepare(entry.account.userId.value, token, renew = true)
                    ensureLive(entry)
                }
                PushRegistrationResult.TOKEN_CONFLICT -> {
                    val replacement = withTimeoutOrNull(10_000) {tokens.renew()}
                    ensureLive(entry)
                    if (replacement != null && replacement != token) {
                        ledger.prepare(entry.account.userId.value, replacement, renew = true)
                        ensureLive(entry)
                    }
                }
                else -> Unit // No acknowledgement: next hint/pulse retries the durable intent.
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive || !live(entry)) throw cancelled
        } catch (_: Exception) { /* Private bounded retry; no SDK/HTTP/storage chains escape or log. */ }
    }
    private fun liveRoot() = !closed && lifetime.isActive
    private fun setActive(active: Boolean) {try {tokens.setActive(active)} catch (_: Exception) { /* SDK availability cannot kill ownership. */ }}
    private fun live(entry: Entry) = liveRoot() && selected === entry && entry.account.job.isActive && accounts.value === entry.account
    private suspend fun ensureLive(entry: Entry) {
        currentCoroutineContext().ensureActive()
        if (!live(entry)) throw CancellationException("Push account retired")
    }
    suspend fun close() = withContext(NonCancellable + queued) {
        if (closed) return@withContext
        closed = true
        val entry = selected; selected = null
        entry?.runner?.cancel(); entry?.client?.retire()
        setActive(false)
        lifetime.cancel()
    }
}
