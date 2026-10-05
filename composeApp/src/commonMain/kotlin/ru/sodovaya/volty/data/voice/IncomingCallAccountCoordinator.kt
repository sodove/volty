package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount

/** Queued SERIAL cache. Waiters borrow root-owned construction and never own notification lifetime. */
internal class IncomingCallAccountCoordinator(private val queuedDispatcher: CoroutineDispatcher,
    private val registry: IncomingCallActionRegistry, private val factory: IncomingCallNotificationFactory) {
    private class Entry(val account: SocialAuthenticatedAccount, val runtime: AuthenticatedVoiceRuntime) {
        lateinit var result: Deferred<IncomingCallNotificationAttachment>
        var attachment: IncomingCallNotificationAttachment? = null
        var obsolete = false
        var failed = false
    }
    private val entries = mutableListOf<Entry>()
    private var selected: Entry? = null
    private var rootJob: Job? = null

    suspend fun acquire(account: SocialAuthenticatedAccount, runtime: AuthenticatedVoiceRuntime): IncomingCallNotificationAttachment {
        val result = withContext(queuedDispatcher) {
            currentCoroutineContext().ensureActive()
            require(runtime.belongsTo(account)) {"Incoming runtime belongs to another account"}
            check(account.job.isActive && !runtime.retired.value) {"Incoming account retired"}
            val parent = account.retirementParentScope()
            val root = requireNotNull(parent.coroutineContext[Job]) {"Incoming coordinator requires application Job"}
            check(root.isActive)
            require(root !== account.job && (rootJob == null || rootJob === root)) {"Incoming coordinator root does not match"}
            require(queuedDispatcher.isDispatchNeeded(parent.coroutineContext)) {"Queued serial dispatcher required"}
            rootJob = root
            val current = selected
            if (current != null && current.account === account && current.runtime === runtime && !current.obsolete && !current.failed &&
                current.attachment?.let {registry.matches(it.binding, account, it.owner)} != false) return@withContext current.result
            val predecessors = entries.toList()
            predecessors.forEach {it.obsolete = true}
            val entry = Entry(account, runtime)
            entries += entry; selected = entry
            val builderLifetime = SupervisorJob(root)
            val scope = CoroutineScope(parent.coroutineContext + builderLifetime + queuedDispatcher)
            val builder = scope.async(start = CoroutineStart.LAZY) {
                try {
                    for (older in predecessors) older.attachment?.let {release(it)}
                    val attachment = factory.create(account, runtime)
                    // A misbound factory cannot make us close another account's borrowed owner.
                    check(attachment.owner.belongsTo(account)) {"Incoming factory returned another account"}
                    entry.attachment = attachment
                    if (selected !== entry || entry.obsolete || !account.job.isActive || runtime.retired.value) release(attachment)
                    else if (!registry.matches(attachment.binding, account, attachment.owner)) {
                        release(attachment)
                        error("Incoming factory returned an obsolete binding")
                    }
                    attachment
                } catch (failure: Throwable) {
                    entry.failed = true
                    throw failure
                }
            }
            entry.result = builder
            builder.invokeOnCompletion {builderLifetime.complete()}
            builder.start()
            builder
        }
        return result.await()
    }

    suspend fun retireAll() = withContext(NonCancellable) {withContext(queuedDispatcher) {
        selected = null
        val retiring = entries.toList()
        retiring.forEach {it.obsolete = true}
        for (entry in retiring) entry.attachment?.let {release(it)}
        // Pending builders release their unique result later; never join held construction.
    }}

    private suspend fun release(attachment: IncomingCallNotificationAttachment) = withContext(NonCancellable) {
        try {registry.detach(attachment.binding)} finally {attachment.owner.close()}
    }
}
