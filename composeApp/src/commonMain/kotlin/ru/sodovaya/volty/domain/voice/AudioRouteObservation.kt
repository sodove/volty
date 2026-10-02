package ru.sodovaya.volty.domain.voice

data class AudioDeviceRef(val id: String, val type: String)

enum class AudioRouteProvenance { STREAM_ROUTING, RECORDING_CONFIGURATION, COMMUNICATION_DEVICE }

/** Null means unobserved. Communication-device evidence does not prove stream routing. */
data class RouteObservation(
    val device: AudioDeviceRef? = null,
    val provenance: AudioRouteProvenance? = null,
    val observedAtMillis: Long? = null,
)

data class AudioRouteState(
    val desiredInput: AudioDeviceRef? = null,
    val desiredOutput: AudioDeviceRef? = null,
    val selectedOutput: AudioDeviceRef? = null,
    val observedInput: RouteObservation = RouteObservation(),
    val observedOutput: RouteObservation = RouteObservation(),
) {
    fun withSelectedOutput(device: AudioDeviceRef?): AudioRouteState = copy(selectedOutput = device)
}
