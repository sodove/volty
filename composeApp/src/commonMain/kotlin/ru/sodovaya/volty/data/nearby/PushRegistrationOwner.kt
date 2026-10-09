package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    private enum class Attempt { READY, REPAIR, UNAVAILABLE }
    private class Entry(val account: SocialAuthenticatedAccount, val client: PushRegistrationClient) {
        var runner: Job? = null
        var firstBinding = true
        var coldWakeRequested = false
        val initial = CompletableDeferred<Boolean>()
        var wake: CompletableDeferred<Boolean>? = null
        val signals = Channel<CompletableDeferred<Boolean>>(Channel.CONFLATED)
        fun release() {
            initial.complete(false)
            wake?.complete(false); wake = null
            signals.close()
        }
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
                selected = null; previous?.runner?.cancel(); previous?.client?.retire(); previous?.release()
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
                        try {
                            var ready = reconcile(entry)
                            // Accepted cold wakes own one retry even if their receiver is cancelled.
                            if (!ready && entry.coldWakeRequested) ready = reconcile(entry)
                            entry.initial.complete(ready)
                            merge(ticks.map { null }, hints.map { null }, entry.signals.receiveAsFlow().map { it as CompletableDeferred<Boolean>? })
                                .collect {request ->
                                    if (request != null && entry.wake !== request) return@collect
                                    val ready = reconcile(entry)
                                    if (request != null && entry.wake === request) {
                                        entry.wake = null
                                        request.complete(ready)
                                    }
                                }
                        } finally {entry.release()}
                    }
                }
            }
        }
    }

    /** A receiver borrows account-owned work; it cannot end process protection at queue submission. */
    suspend fun wake(account: SocialAuthenticatedAccount): Boolean {
        val borrowed = withContext(queued) {
            val entry = selected?.takeIf {it.account === account && live(it)} ?: return@withContext null
            val ticket = if (!entry.initial.isCompleted) {
                entry.coldWakeRequested = true
                entry.initial
            } else requestWake(entry)
            entry to ticket
        } ?: return false
        val ready = borrowed.second.await()
        return ready && withContext(queued) {live(borrowed.first)}
    }
    private fun requestWake(entry: Entry) = entry.wake
        ?: CompletableDeferred<Boolean>().also {entry.wake = it; entry.signals.trySend(it)}

    private suspend fun reconcile(entry: Entry): Boolean {
        repeat(3) {
            when (attempt(entry)) {
                Attempt.READY -> return true
                Attempt.UNAVAILABLE -> return false
                Attempt.REPAIR -> Unit
            }
        }
        return false
    }
    private suspend fun attempt(entry: Entry): Attempt {
        try {
            ensureLive(entry)
            val token = withTimeoutOrNull(10_000) {tokens.current()} ?: return Attempt.UNAVAILABLE
            ensureLive(entry)
            val snapshot = ledger.prepare(entry.account.userId.value, token, renew = entry.firstBinding)
            entry.firstBinding = false
            ensureLive(entry)
            val result = entry.client.submit(snapshot.intent)
            ensureLive(entry)
            when (result) {
                PushRegistrationResult.APPLIED -> {
                    if (ledger.acknowledge(snapshot)) {
                        ensureLive(entry); registered(entry.account); ensureLive(entry)
                        return Attempt.READY
                    }
                }
                PushRegistrationResult.STALE -> {
                    // A refreshed JWT has a new issued-at; the server correctly refuses same-version replay.
                    ledger.prepare(entry.account.userId.value, token, renew = true)
                    ensureLive(entry)
                    return Attempt.REPAIR
                }
                PushRegistrationResult.TOKEN_CONFLICT -> {
                    val replacement = withTimeoutOrNull(10_000) {tokens.renew()}
                    ensureLive(entry)
                    if (replacement != null && replacement != token) {
                        ledger.prepare(entry.account.userId.value, replacement, renew = true)
                        ensureLive(entry)
                        return Attempt.REPAIR
                    }
                }
                else -> Unit // No acknowledgement: next hint/pulse retries the durable intent.
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive || !live(entry)) throw cancelled
        } catch (_: Exception) { /* Private bounded retry; no SDK/HTTP/storage chains escape or log. */ }
        return Attempt.UNAVAILABLE
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
        entry?.runner?.cancel(); entry?.client?.retire(); entry?.release()
        setActive(false)
        lifetime.cancel()
    }
}
