package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PushInstallationSnapshot(val actorId: String, val intent: PushRegistrationIntent, val acknowledged: Boolean) {
    init {directCallId(actorId); require(validPushRegistrationIntent(intent)) {"Invalid push installation snapshot"}}
    override fun toString() = "PushInstallationSnapshot(<redacted>)"
}
/** Implementations atomically persist the entire private snapshot before returning from write. */
internal interface PushInstallationStore {
    suspend fun read(): PushInstallationSnapshot?
    suspend fun write(snapshot: PushInstallationSnapshot)
}
internal interface PushInstallationIdentityGenerator {
    fun uuid(): String
    fun capability(): String
}
internal class PushInstallationLedger(private val store: PushInstallationStore, private val generator: PushInstallationIdentityGenerator) {
    // One application singleton; fresh reads retain durable state even after a failed write.
    private val mutex = Mutex()
    suspend fun prepare(actorId: String, token: String?, renew: Boolean = false): PushInstallationSnapshot = mutex.withLock {
        directCallId(actorId)
        val previous = store.read()
        if (!renew && previous?.actorId == actorId && previous.intent.token == token) return@withLock previous
        check(previous?.intent?.generation != Long.MAX_VALUE) {"Push installation generation exhausted"}
        val installation = previous?.intent?.installationId ?: generator.uuid()
        val capability = previous?.intent?.installationKey ?: generator.capability()
        val binding = generator.uuid()
        require(binding != previous?.intent?.bindingId) {"Push binding identity repeated"}
        val intent = PushRegistrationIntent(installation, capability,
            (previous?.intent?.generation ?: 0L) + 1, binding, token)
        val next = PushInstallationSnapshot(actorId, intent, acknowledged = false)
        store.write(next)
        next
    }
    suspend fun acknowledge(expected: PushInstallationSnapshot): Boolean = mutex.withLock {
        val current = store.read() ?: return@withLock false
        if (!sameBinding(current, expected)) return@withLock false
        if (!current.acknowledged) store.write(PushInstallationSnapshot(current.actorId, current.intent, true))
        true
    }
    suspend fun current(): PushInstallationSnapshot? = mutex.withLock {store.read()}
    /** Check logout authority inside the same serial mutation as the durable tombstone. */
    suspend fun retireIf(noAccount: () -> Boolean): PushInstallationSnapshot? = mutex.withLock {
        val current = store.read() ?: return@withLock null
        if (!noAccount()) return@withLock null
        if (current.intent.token == null) return@withLock current
        check(current.intent.generation != Long.MAX_VALUE) {"Push installation generation exhausted"}
        val binding = generator.uuid()
        require(binding != current.intent.bindingId) {"Push binding identity repeated"}
        val intent = PushRegistrationIntent(current.intent.installationId, current.intent.installationKey,
            current.intent.generation + 1, binding, null)
        PushInstallationSnapshot(current.actorId, intent, false).also {store.write(it)}
    }
    private fun sameBinding(a: PushInstallationSnapshot, b: PushInstallationSnapshot) = a.actorId == b.actorId &&
        a.intent.installationId == b.intent.installationId && a.intent.installationKey == b.intent.installationKey &&
        a.intent.generation == b.intent.generation && a.intent.bindingId == b.intent.bindingId && a.intent.token == b.intent.token
}
