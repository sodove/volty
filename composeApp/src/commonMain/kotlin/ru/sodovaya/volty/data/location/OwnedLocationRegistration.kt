package ru.sodovaya.volty.data.location

/**
 * Main-thread confined owner of one platform listener and its acknowledged request.
 * A failed remove retains the exact original owner, but fences its callbacks.
 * Desired consumer state is not evidence that native acquisition/release succeeded.
 */
internal class OwnedLocationRegistration<K : Any, L : Any>(
    private val create: (K) -> L,
    private val register: (K, L) -> Unit,
    private val unregister: (L) -> Unit,
) {
    var key: K? = null
        private set
    var active: Boolean = false
        private set
    private var listener: L? = null

    /** Returns whether acknowledged native ownership changed. Throws until cleanup ACK. */
    fun reconcile(request: K?, isCurrent: () -> Boolean = { true }): Boolean {
        // State publication may have synchronously retired this request and installed a successor.
        if (!isCurrent()) return false
        if (request != null && active && key == request) return false
        val previous = listener
        if (previous != null) {
            active = false
            unregister(previous)
            // Clear only after actual native release, never merely on desired-demand removal.
            listener = null
            key = null
        }
        if (request == null || !isCurrent()) return previous != null
        val candidate = create(request)
        // Even the FIRST positive call may partly commit and then throw.
        listener = candidate
        key = request
        try {
            register(request, candidate)
            active = true
        } catch (error: Throwable) {
            try { reconcile(null) }
            catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
        return true
    }
}
