package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.location.LocationConsumer
import ru.sodovaya.volty.domain.location.RideLocationState
import ru.sodovaya.volty.domain.location.RideLocationStatus

/** App-owned recording observer; activation and native service demand are separate.
 * Both actual native foreground and provider acquisition must be acknowledged for this recording.
 * Capture is fenced separately from the original owner's native cleanup acknowledgement.
 */
internal class HistoryGpsRecorder(
    scope: CoroutineScope,
    controller: RideRecordingController,
    locations: StateFlow<RideLocationState>,
    foreground: HistoryLocationForegroundAuthority,
    readTick: () -> HistoryTick,
    maxAgeMillis: Long = 10_000L,
) {
    init { require(maxAgeMillis in 0 until Long.MAX_VALUE) }

    private val job = scope.launch {
        // BLE loss/reconnect changes intent, not the GPS recording lifetime.
        controller.state.map { it.intent.ride }.distinctUntilChanged().collectLatest { ride ->
            if (ride == null) return@collectLatest
            var lastFix: HistoryGpsFix? = null
            var gap: HistoryTick? = null

            suspend fun markGap(now: HistoryTick) {
                currentCoroutineContext().ensureActive()
                if (gap == null && controller.offerGpsGap(ride.id, now) == HistoryOffer.ACCEPTED) gap = now
            }

            combine(locations, foreground.state, controller.gpsAdmissionChanges) { state, native, _ ->
                val captured = controller.captureGpsRecording()?.takeIf { it.rideId == ride.id }
                // Retraction: consumer intent alone cannot prove native foreground/provider ACK.
                val status = if (captured != null && native.captureReady &&
                    LocationConsumer.HISTORY_RECORDING in state.demands && foreground.canCapture(captured)) state.status
                    else RideLocationStatus.NotRequested
                // Reopening controller intake must retry an unavailable status rejected during
                // its fence, even when the provider and foreground status did not change again.
                HistoryGpsInput(status, captured != null)
            }.distinctUntilChanged().collectLatest location@{ input ->
                val status = input.status
                val captured = controller.captureGpsRecording() ?: return@location
                if (captured.rideId != ride.id) return@location
                try {
                    val now = readTick()
                    currentCoroutineContext().ensureActive()
                    if (historyAxisEpochMillis(ride.started, now) == null) return@location
                    val previous = lastFix
                    val raw = (status as? RideLocationStatus.Available)?.fix
                    val capturedElapsed = raw?.elapsedRealtimeMillis
                    // Retraction: freshness-first admission treated a delayed stale provider
                    // coordinate as an outage even while a newer accepted fix was fresh.
                    val fix = if (previous != null && capturedElapsed != null &&
                        capturedElapsed < previous.tick.elapsedMillis) previous
                    else raw?.let { historyGpsFix(ride.started, now, it, maxAgeMillis) }
                    if (fix == null) {
                        markGap(now)
                        return@location
                    }
                    val openGap = gap
                    // A delayed coordinate from before the outage cannot resume its route.
                    if (openGap != null && fix.tick.elapsedMillis <= openGap.elapsedMillis) return@location
                    if (previous == null || fix.tick.elapsedMillis >= previous.tick.elapsedMillis) {
                        if (fix != previous) {
                            // Input projection can be overtaken by native revocation. Serialize
                            // the nonblocking offer with that exact recording's capture fence.
                            if (foreground.offer(captured) { controller.offerGps(ride.id, fix) } != HistoryOffer.ACCEPTED) return@location
                            lastFix = fix
                            gap = null
                        }
                    }
                    val accepted = lastFix ?: return@location
                    if (gap != null) return@location
                    // Older provider events do not replace the last accepted fix or move its expiry.
                    val age = now.elapsedMillis - accepted.tick.elapsedMillis
                    if (age < 0L) return@location
                    if (age > maxAgeMillis) {
                        markGap(now)
                        return@location
                    }
                    delay(maxAgeMillis - age + 1L)
                    val expiredAt = readTick()
                    currentCoroutineContext().ensureActive()
                    if (historyAxisEpochMillis(ride.started, expiredAt) != null &&
                        expiredAt.elapsedMillis - accepted.tick.elapsedMillis > maxAgeMillis) markGap(expiredAt)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    // A failed optional observer cannot take down its application scope.
                    currentCoroutineContext().ensureActive()
                    controller.reportGpsObservationFailure(captured)
                }
            }
        }
    }

    fun close() { job.cancel() }
    suspend fun closeAndJoin() { job.cancelAndJoin() }
}

private data class HistoryGpsInput(val status: RideLocationStatus, val admissionOpen: Boolean)
