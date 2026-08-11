package ru.sodovaya.volty.service

import android.media.AudioManager
import ru.sodovaya.volty.domain.audio.AutoVolumeLogic
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Applies a saved vehicle's speed-volume curve to Android's music stream.
 *
 * This is deliberately owned by the same foreground service as audible alarms:
 * it continues working with the screen off, but dies with the BLE session. The
 * common [AutoVolumeLogic] owns all curve/deadband decisions; this class only
 * supplies live telemetry and quantizes it through [AudioManager].
 */
@OptIn(ExperimentalTime::class)
class AutoVolumeController(
    private val repository: BmsRepository,
    private val vehicleRepository: VehicleRepository,
    private val audioManager: AudioManager,
    private val scope: CoroutineScope,
    private val isMusicPlaying: () -> Boolean = { audioManager.isMusicActive }
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            combine(
                repository.activeVehicle,
                vehicleRepository.vehicles,
                repository.activeMotion,
                repository.connectionState
            ) { activeVehicle, vehicles, motion, connection ->
                val vehicle = activeVehicle?.let { active ->
                    vehicles.firstOrNull { it.id == active.id } ?: active
                }
                Triple(vehicle, motion, connection)
            }
                .collect { (vehicle, motion, connection) ->
                    val settings = vehicle?.autoVolume
                    if (settings == null || !settings.enabled || connection !is ConnectionState.Connected ||
                        !motion.speedKnown || Clock.System.now() - motion.timestamp > 10.seconds ||
                        !isMusicPlaying()
                    ) {
                        logic = null
                        return@collect
                    }
                    val activeLogic = logicFor(vehicle.id, settings)
                    val decision = activeLogic.onSpeed(motion.speedKmh.coerceAtLeast(0f).toInt()) ?: return@collect
                    try {
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            decision.step.coerceIn(0, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
                            0
                        )
                    } catch (_: SecurityException) {
                        // A device policy may reject programmatic media-volume changes.
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        logic = null
    }

    private var logicKey: String? = null
    private var logic: AutoVolumeLogic? = null

    private fun logicFor(vehicleId: String, settings: ru.sodovaya.volty.domain.model.AutoVolumeSettings): AutoVolumeLogic {
        if (logic == null || logicKey != "$vehicleId:$settings") {
            logicKey = "$vehicleId:$settings"
            logic = AutoVolumeLogic(
                settings = settings,
                volumeSteps = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            )
        }
        return logic!!
    }
}
