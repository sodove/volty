package ru.sodovaya.volty.domain.nearby

import kotlinx.serialization.Serializable
import ru.sodovaya.volty.domain.social.LocationSnapshot
import ru.sodovaya.volty.domain.social.SharedTelemetry
import ru.sodovaya.volty.domain.social.TelemetryNumber
import ru.sodovaya.volty.domain.social.TelemetryFaults

@Serializable
data class ChannelSharingPacket(val sequence: Long, val capturedAtEpochMillis: Long,
    val location: LocationSnapshot?, val telemetry: SharedTelemetry? = null)

internal const val CHANNEL_SHARING_SAMPLE_MILLIS = 15_000L
internal fun ChannelSharingPacket.validateCapture() {
    require(sequence >= 0 && capturedAtEpochMillis in 0..Long.MAX_VALUE - CHANNEL_SHARING_SAMPLE_MILLIS)
    location?.let {
        require(it.capturedAtEpochMillis in 0..capturedAtEpochMillis)
        require(it.staleAfterEpochMillis > it.capturedAtEpochMillis && it.staleAfterEpochMillis - it.capturedAtEpochMillis <= CHANNEL_SHARING_SAMPLE_MILLIS)
    }
}
internal fun ChannelSharingPacket.sanitized(mode: ChannelSharingMode): ChannelSharingPacket {
    validateCapture()
    if (mode == ChannelSharingMode.LOCATION) {require(telemetry == null); return this}
    val value = requireNotNull(telemetry)
    require(value.schemaVersion == 1 && value.profile.name == mode.name)
    val full = mode == ChannelSharingMode.FULL
    fun TelemetryNumber.earned(allowed: Boolean = true) = when {
        !allowed || !supported -> TelemetryNumber.unsupported()
        !known -> TelemetryNumber.unknown()
        else -> TelemetryNumber.known(requireNotNull(this.value))
    }
    val faults = when {
        !full || !value.faults.supported -> TelemetryFaults.unsupported()
        !value.faults.known -> TelemetryFaults.unknown()
        else -> {
            val list = requireNotNull(value.faults.values)
            require(list.size <= 16 && list.all {it.length in 1..256 && it.none(Char::isISOControl)})
            TelemetryFaults.known(list.toList())
        }
    }
    return copy(telemetry = value.copy(speedKmh=value.speedKmh.earned(),batterySocFraction=value.batterySocFraction.earned(),powerW=value.powerW.earned(),
        packVoltageV=value.packVoltageV.earned(full),batteryCurrentA=value.batteryCurrentA.earned(full),escTempC=value.escTempC.earned(full),
        motorTempC=value.motorTempC.earned(full),cellMinV=value.cellMinV.earned(full),cellMaxV=value.cellMaxV.earned(full),cellDeltaV=value.cellDeltaV.earned(full),faults=faults))
}

data class ObservedChannelSharingPeer(val actorId: String, val tag: ChannelSharingTag,
    val mode: ChannelSharingMode, val packet: ChannelSharingPacket, val deadlineMillis: Long, val displayName: String? = null)

sealed interface ChannelSharingPacketReply {
    data class Published(val tag: ChannelSharingTag, val sequence: Long, val requestOrdinal: Long,
        val requestStartedMillis: Long, val responseReceivedMillis: Long, val deadlineMillis: Long) : ChannelSharingPacketReply
    data class Audience(val channelId: String, val serverTimeEpochMillis: Long, val requestOrdinal: Long,
        val requestStartedMillis: Long, val responseReceivedMillis: Long, val peers: List<ObservedChannelSharingPeer>) : ChannelSharingPacketReply
    data class Failed(val reason: ChannelSharingFailure) : ChannelSharingPacketReply
}

/** Optional server profile metadata; it earns no position, sample freshness or voice presence. */
internal fun channelSharingPeerDisplayName(value: String?): String? = value?.takeIf {
    it.length in 2..80 && it.isNotBlank() && it.none(Char::isISOControl)
}
