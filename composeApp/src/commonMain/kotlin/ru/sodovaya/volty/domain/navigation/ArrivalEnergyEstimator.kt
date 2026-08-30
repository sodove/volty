package ru.sodovaya.volty.domain.navigation

import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime

enum class ConsumptionProvenance {
    CONTROLLER_COUNTERS,
    POWER_INTEGRAL,
}

data class ConsumptionEvidence(
    val whPerKm: Double,
    val distanceKm: Double,
    val durationMillis: Long,
    val measuredSampleCount: Int,
    val provenance: ConsumptionProvenance,
)

data class NavigationEnergyEvidence(
    val vehicleData: ru.sodovaya.volty.domain.model.VehicleData,
    val motion: ru.sodovaya.volty.domain.model.ControllerData,
    val consumption: ConsumptionEvidence?,
)

enum class ArrivalSocUnknownReason {
    NO_ROUTE,
    BMS_DISCONNECTED,
    PACKS_PARTIAL,
    SOC_UNEARNED,
    CAPACITY_UNEARNED,
    TELEMETRY_STALE,
    CONSUMPTION_UNEARNED,
}

sealed interface ArrivalSocEstimate {
    data class Known(val percent: Int, val approximate: Boolean = true) : ArrivalSocEstimate
    data class Unknown(val reason: ArrivalSocUnknownReason) : ArrivalSocEstimate
}

@OptIn(ExperimentalTime::class)
object ArrivalEnergyEstimator {
    private const val MIN_CONSUMPTION_DISTANCE_KM = 2.0
    private const val MIN_CONSUMPTION_DURATION_MILLIS = 5 * 60 * 1_000L
    private const val MIN_CONSUMPTION_SAMPLES = 20
    private const val MAX_BMS_AGE_MILLIS = 5_000L

    fun estimate(
        evidence: NavigationEnergyEvidence,
        remainingDistanceMeters: Double?,
        nowEpochMillis: Long,
    ): ArrivalSocEstimate {
        if (remainingDistanceMeters == null ||
            !remainingDistanceMeters.isFinite() ||
            remainingDistanceMeters < 0.0
        ) {
            return ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.NO_ROUTE)
        }

        val vehicleData = evidence.vehicleData
        val packs = vehicleData.packs
        if (packs.isEmpty() || packs.any { !it.isOnline }) {
            return if (packs.any { !it.isOnline }) {
                ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.PACKS_PARTIAL)
            } else {
                ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.BMS_DISCONNECTED)
            }
        }
        if (vehicleData.isPartial) {
            return ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.PACKS_PARTIAL)
        }
        if (!vehicleData.aggregate.isConnected || packs.any { !it.data.isConnected }) {
            return ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.BMS_DISCONNECTED)
        }

        if (packs.any { pack ->
                val soc = pack.data.soc
                !pack.data.socKnown || !soc.isFinite() || soc !in 0f..100f
            } ||
            !vehicleData.aggregate.soc.isFinite() ||
            vehicleData.aggregate.soc !in 0f..100f
        ) {
            return ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.SOC_UNEARNED)
        }

        if (packs.any { pack ->
                val data = pack.data
                !data.capacity.isFinite() || data.capacity <= 0f ||
                    !data.voltage.isFinite() || data.voltage <= 0f
            } ||
            !vehicleData.aggregate.capacity.isFinite() ||
            vehicleData.aggregate.capacity <= 0f ||
            !vehicleData.aggregate.voltage.isFinite() ||
            vehicleData.aggregate.voltage <= 0f
        ) {
            return ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.CAPACITY_UNEARNED)
        }

        if (!isFresh(vehicleData.aggregate.timestamp.toEpochMilliseconds(), nowEpochMillis) ||
            packs.any { !isFresh(it.data.timestamp.toEpochMilliseconds(), nowEpochMillis) }
        ) {
            return ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.TELEMETRY_STALE)
        }

        val consumption = evidence.consumption
        if (consumption == null ||
            !consumption.whPerKm.isFinite() || consumption.whPerKm <= 0.0 ||
            !consumption.distanceKm.isFinite() || consumption.distanceKm < MIN_CONSUMPTION_DISTANCE_KM ||
            consumption.durationMillis < MIN_CONSUMPTION_DURATION_MILLIS ||
            consumption.measuredSampleCount < MIN_CONSUMPTION_SAMPLES
        ) {
            return ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.CONSUMPTION_UNEARNED)
        }

        val capacityEnergyWh = vehicleData.aggregate.capacity.toDouble() *
            vehicleData.aggregate.voltage.toDouble()
        if (!capacityEnergyWh.isFinite() || capacityEnergyWh <= 0.0) {
            return ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.CAPACITY_UNEARNED)
        }

        val consumedEnergyWh = remainingDistanceMeters / 1_000.0 * consumption.whPerKm
        val arrivalPercent = (
            vehicleData.aggregate.soc.toDouble() / 100.0 * capacityEnergyWh - consumedEnergyWh
            ) / capacityEnergyWh * 100.0
        return ArrivalSocEstimate.Known(
            percent = arrivalPercent.coerceIn(0.0, 100.0).roundToInt(),
            approximate = true,
        )
    }

    private fun isFresh(timestampMillis: Long, nowEpochMillis: Long): Boolean {
        if (timestampMillis > nowEpochMillis) return false
        return nowEpochMillis - timestampMillis <= MAX_BMS_AGE_MILLIS
    }
}
