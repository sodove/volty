package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Completes cleanup for a batch whose native ownership has already been detached. */
internal suspend fun <T> completeDetachedSessionCleanup(
    sessions: List<T>,
    teardown: suspend (T) -> Unit,
    retire: suspend (T) -> Unit = {},
) = withContext(NonCancellable) {
    var failure: Throwable? = null
    suspend fun attempt(action: suspend () -> Unit) {
        try { action() }
        catch (error: Throwable) {
            val first = failure
            if (first == null) failure = error
            else if (first !== error) first.addSuppressed(error)
        }
    }
    // An earlier sequential loop abandoned later detached owners at its first failure.
    // Every original native and recording negative must be attempted before reporting it.
    sessions.forEach { session ->
        attempt { teardown(session) }
        attempt { retire(session) }
    }
    failure?.let { throw it }
}
