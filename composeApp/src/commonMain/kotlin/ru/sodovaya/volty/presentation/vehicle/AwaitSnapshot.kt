package ru.sodovaya.volty.presentation.vehicle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Runs one callback-style snapshot request and suspends for its answer; **null on any failure**, which is what the
 * route preview's KDoc promises.
 *
 * [start] builds AND starts the native snapshotter, handing it the two callbacks, and returns how to cancel it. It runs
 * inside the guard because MapLibre's `MapSnapshotter(context, options)` constructor can itself throw
 * `IllegalStateException: invalid native peer` (reproduced on an AVD when a vehicle card opened) - guarding only
 * `start()` left the construction to crash the composition. A late or repeated callback after the request settled is
 * ignored. [CancellationException] is never swallowed: the caller's own cancellation must reach it.
 */
internal suspend fun <T : Any> awaitSnapshotOrNull(
    start: (onResult: (T) -> Unit, onFailure: () -> Unit) -> (() -> Unit),
): T? = suspendCancellableCoroutine { continuation ->
    val cancel = try {
        start(
            { value -> if (continuation.isActive) continuation.resume(value) },
            { if (continuation.isActive) continuation.resume(null) },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (continuation.isActive) continuation.resume(null)
        return@suspendCancellableCoroutine
    }
    continuation.invokeOnCancellation { try { cancel() } catch (_: Exception) { /* a dead native peer has nothing left to cancel */ } }
}
