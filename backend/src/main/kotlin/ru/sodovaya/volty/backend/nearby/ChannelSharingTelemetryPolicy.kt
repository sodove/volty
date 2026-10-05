package ru.sodovaya.volty.backend.nearby

import ru.sodovaya.volty.backend.SharedTelemetryDto
import ru.sodovaya.volty.backend.TelemetryNumberDto
import ru.sodovaya.volty.backend.TelemetryFaultsDto

internal object ChannelSharingTelemetryPolicy {
    fun sanitize(mode: ChannelSharingMode, value: SharedTelemetryDto?): SharedTelemetryDto? {
        if (mode == ChannelSharingMode.LOCATION) {require(value == null); return null}
        requireNotNull(value)
        require(value.schemaVersion == 1 && value.profile == mode.name)
        val full = mode == ChannelSharingMode.FULL
        return value.copy(
            speedKmh = value.speedKmh.earned(), batterySocFraction = value.batterySocFraction.earned(), powerW = value.powerW.earned(),
            packVoltageV = value.packVoltageV.allowed(full), batteryCurrentA = value.batteryCurrentA.allowed(full),
            escTempC = value.escTempC.allowed(full), motorTempC = value.motorTempC.allowed(full),
            cellMinV = value.cellMinV.allowed(full), cellMaxV = value.cellMaxV.allowed(full), cellDeltaV = value.cellDeltaV.allowed(full),
            faults = if (full) value.faults.earned() else TelemetryFaultsDto(false, false),
        )
    }
    private fun TelemetryNumberDto.allowed(include: Boolean) = if (include) earned() else TelemetryNumberDto(false, false)
    private fun TelemetryNumberDto.earned(): TelemetryNumberDto = when {
        !supported -> TelemetryNumberDto(false, false)
        !known -> TelemetryNumberDto(true, false)
        else -> {
            require(value != null && value.isFinite())
            this
        }
    }
    private fun TelemetryFaultsDto.earned(): TelemetryFaultsDto = when {
        !supported -> TelemetryFaultsDto(false, false)
        !known -> TelemetryFaultsDto(true, false)
        else -> {
            require(values != null && values.size <= MAX_FAULTS)
            require(values.all {it.length in 1..MAX_FAULT_LENGTH && it.none(Char::isISOControl)})
            copy(values = values.toList())
        }
    }
    private const val MAX_FAULTS = 16
    private const val MAX_FAULT_LENGTH = 256
}
