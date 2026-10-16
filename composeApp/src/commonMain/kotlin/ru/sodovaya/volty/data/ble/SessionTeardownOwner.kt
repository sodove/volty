package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Retires one physical attempt and serializes completion of its native cleanup. */
internal class SessionTeardownOwner(private val cleanup: suspend () -> Unit) {
    private val mutex = Mutex()
    @Volatile var retired = false
        private set
    private var completed = false

    suspend fun run() = withContext(NonCancellable) {
        mutex.withLock {
            if (completed) return@withLock
            // An earlier revision treated retirement as cleanup completion. Cancellation
            // during a native join then permanently suppressed disconnect/reset retries.
            retired = true
            cleanup()
            completed = true
        }
    }
}
