package ru.sodovaya.volty.domain.navigation

import ru.sodovaya.volty.domain.model.ControllerData
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Bounds the motion ring buffer to the current session. The buffer survives a reconnect, but
 * `tripKm` restarts with the protocol, so a vehicle or connection change starts a new session at
 * the current motion's timestamp. Stateful: one instance per consumer. Shared by navigation's and
 * the range source's evidence so both cut the session the same way.
 */
@OptIn(ExperimentalTime::class)
class SessionSamples {
    private var sessionKey: String? = null
    private var sessionStart: Instant? = null

    fun of(
        vehicleId: String?,
        connected: Boolean,
        motion: ControllerData,
        samples: List<ControllerData>,
    ): List<ControllerData> {
        val currentSessionKey = "${vehicleId ?: "none"}:$connected"
        if (currentSessionKey != sessionKey) {
            sessionStart = if (sessionKey == null) {
                samples.minOfOrNull { it.timestamp } ?: motion.timestamp
            } else {
                motion.timestamp
            }
            sessionKey = currentSessionKey
        }
        return samples.filter { sample -> sessionStart?.let { sample.timestamp >= it } ?: true }
    }
}
