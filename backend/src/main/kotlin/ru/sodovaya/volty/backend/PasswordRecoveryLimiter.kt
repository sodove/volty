package ru.sodovaya.volty.backend

/** One request per mailbox per minute, including unknown accounts. Bounded under random-email floods. */
internal class PasswordRecoveryLimiter {
    private val requests = mutableMapOf<String, Long>()

    @Synchronized
    fun allow(email: String): Boolean {
        val now = System.nanoTime() / 1_000_000
        requests.entries.removeIf { now - it.value >= 60_000 }
        val key = sha256(email)
        if (key in requests || requests.size >= 1024) return false
        requests[key] = now
        return true
    }
}
