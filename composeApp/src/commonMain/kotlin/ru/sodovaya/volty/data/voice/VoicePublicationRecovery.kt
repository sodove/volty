package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The port owns one existing track. Restoration must never enable capture. */
internal interface VoicePublicationRecoveryPort {
    suspend fun restoreClosed(): Boolean
    fun hasOwnedPublication(): Boolean
}

/** Transport recovery stays with the SDK; this only restores SDK-skipped muted audio. */
internal class VoicePublicationRecovery(
    private val scope: CoroutineScope,
    private val port: VoicePublicationRecoveryPort,
    private val published: () -> Unit,
    private val failed: () -> Unit,
) {
    private val mutex = Mutex()
    private var stopped = false
    private var connected = false
    private var inReconnect = false
    private var awaitingPublication = false
    private var restoreMuted = false
    private var missing = false
    private var cycle = 0L
    private var lossVersion = 0L
    private var attempts = 0
    private var failureReported = false
    private var active: Job? = null

    suspend fun reconnecting() = mutex.withLock {
        if (stopped) return@withLock
        if (connected || !inReconnect) {
            cycle++
            // An interrupted closed restore can span successive full attempts.
            // A settled publication lets the next actual removal select SDK/app ownership anew.
            restoreMuted = restoreMuted && (missing || active != null)
            missing = restoreMuted
            attempts = 0
            failureReported = false
            active?.cancel()
        }
        connected = false
        inReconnect = true
        awaitingPublication = true
    }

    suspend fun connected() = mutex.withLock {
        if (stopped) return@withLock
        connected = true
        restoreIfNeeded()
    }

    suspend fun unpublished(wasMuted: Boolean) = mutex.withLock {
        if (stopped) return@withLock
        missing = true
        lossVersion++
        // Preserve the SDK-skipped mode after a first restored publication has
        // been unmuted by the driver: SDK's saved original is still muted.
        if (inReconnect && awaitingPublication && wasMuted) restoreMuted = true
        restoreIfNeeded()
    }

    /** False suppresses the SDK callback until closed restoration is acknowledged. */
    suspend fun sdkPublished(): Boolean = mutex.withLock {
        if (stopped || failureReported || active != null) false
        else { missing = false; awaitingPublication = false; true }
    }

    suspend fun close() = mutex.withLock {
        stopped = true
        active?.cancel()
    }

    /** Called only under mutex. No second job starts until the first actually retires. */
    private fun restoreIfNeeded() {
        if (stopped || !connected || !missing || !restoreMuted || failureReported || active != null) return
        if (attempts >= MAX_ATTEMPTS) {
            reportFailure()
            return
        }
        attempts++
        val ownedCycle = cycle
        val observedLoss = lossVersion
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val restored = withTimeoutOrNull(RESTORE_TIMEOUT_MILLIS) { port.restoreClosed() } == true
                currentCoroutineContext().ensureActive()
                mutex.withLock {
                    if (stopped || active !== job || cycle != ownedCycle || !connected) return@withLock
                    if (restored && port.hasOwnedPublication()) {
                        missing = false
                        awaitingPublication = false
                        published()
                    } else if (lossVersion == observedLoss) {
                        reportFailure()
                    }
                    // An actual removal during restore can coalesce one further
                    // closed attempt; elapsed time is never publication evidence.
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutex.withLock {
                    if (!stopped && active === job && cycle == ownedCycle && connected) reportFailure()
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (active === job) {
                            active = null
                            restoreIfNeeded()
                        }
                    }
                }
            }
        }
        active = job
        job.start()
    }

    private fun reportFailure() {
        if (!failureReported) {
            failureReported = true
            failed()
        }
    }

    private companion object {
        const val RESTORE_TIMEOUT_MILLIS = 5_000L
        const val MAX_ATTEMPTS = 2
    }
}
