package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

/** One queued serial owner. Pulses must be cold or broadcast, not distributed between readers.
 * Holds retired runtimes until a separate drain can dispose them. */
internal class VoiceAccountCoordinator(
    private val client: HttpClient,
    baseUrl: String,
    private val queuedDispatcher: CoroutineDispatcher,
    private val pulses: Flow<Unit>,
    private val monotonicMillis: () -> Long,
    private val nativeEnvironment: (AuthenticatedVoiceRuntime) -> Boolean,
) {
    private val root = nearbyHttpsApiBaseUrl(baseUrl)
    private class Entry(val account: SocialAuthenticatedAccount) {
        lateinit var result: Deferred<AuthenticatedVoiceRuntime>
        var runtime: AuthenticatedVoiceRuntime? = null
        var obsolete = false
        var failed = false
    }
    private val entries = mutableListOf<Entry>()
    private var current: Entry? = null
    private var rootJob: Job? = null

    suspend fun acquire(account: SocialAuthenticatedAccount, factory: VoiceSdkSessionFactory,
                        sharingDispatch: (VoiceSharingEffect) -> Unit): AuthenticatedVoiceRuntime {
        val result = withContext(queuedDispatcher) {
            check(account.job.isActive) { "Authenticated account retired" }
            invitationDraftId(account.userId.value)
            val parent = account.retirementParentScope()
            val job = requireNotNull(parent.coroutineContext[Job]) { "Voice coordinator requires application Job" }
            check(job.isActive) { "Voice application parent stopped" }
            require(queuedDispatcher.isDispatchNeeded(parent.coroutineContext)) { "Queued serial dispatcher required" }
            require(rootJob == null || rootJob === job) { "Voice coordinator repository root does not match" }
            rootJob = job
            val previous = current
            if (previous != null && previous.account === account && !previous.obsolete && !previous.failed &&
                previous.runtime?.retired?.value != true) return@withContext previous.result

            // Revoke older positive permission before any builder/token suspension.
            val predecessors = entries.toList()
            predecessors.forEach { it.obsolete = true }
            val entry = Entry(account)
            entries += entry
            current = entry
            val builderLifetime = SupervisorJob(job)
            val scope = CoroutineScope(parent.coroutineContext + builderLifetime + queuedDispatcher)
            val builder = scope.async(start = CoroutineStart.LAZY) {
                try {
                    // An earlier revision sampled the list when the builder ran. An older
                    // delayed builder then retired newer owners. Capture predecessors at selection.
                    for (older in predecessors) older.runtime?.retire()
                    val runtime = AuthenticatedVoiceRuntime.create(account, client, root, queuedDispatcher,
                        factory, pulses, monotonicMillis, sharingDispatch, { mayAllocate(entry) })
                    entry.runtime = runtime
                    if (current !== entry || entry.obsolete || !account.job.isActive) runtime.retire()
                    runtime
                } catch (failure: Throwable) {
                    entry.failed = true
                    throw failure
                }
            }
            entry.result = builder
            builder.invokeOnCompletion { builderLifetime.complete() }
            builder.start()
            builder
        }
        // Waiter cancellation cannot cancel the root-owned builder or lose its runtime.
        return result.await()
    }

    suspend fun retireAll() = withContext(NonCancellable + queuedDispatcher) {
        val retiring = entries.toList()
        current = null
        retiring.forEach { it.obsolete = true }
        for (entry in retiring) entry.runtime?.retire()
        // Pending builders retire their result when they return; never join held work here.
    }

    private fun mayAllocate(entry: Entry): Boolean {
        if (current !== entry || entry.obsolete || !entry.account.job.isActive) return false
        val runtime = entry.runtime ?: return false
        if (!try { nativeEnvironment(runtime) } catch (_: Exception) { false }) return false
        return entries.all { older ->
            if (older === entry) true else {
                // A pending factory has never delivered a runtime or admitted an SDK start.
                val runtime = older.runtime
                runtime == null || (runtime.retired.value && !runtime.retirementFailed.value &&
                    !runtime.state.value.wanted && !runtime.state.value.cleanupPending &&
                    runtime.failedLocalCleanup.value.isEmpty() && runtime.nativeInFlight.value == 0)
            }
        }
    }
}
