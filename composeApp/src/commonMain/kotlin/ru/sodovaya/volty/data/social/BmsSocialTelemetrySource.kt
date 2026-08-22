package ru.sodovaya.volty.data.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.social.EarnedTelemetry
import ru.sodovaya.volty.domain.social.SocialTelemetrySource
import ru.sodovaya.volty.domain.social.TelemetryFaults
import ru.sodovaya.volty.domain.social.TelemetryNumber

/**
 * The only producer crossing the social boundary: it folds the existing live
 * BMS/controller state into earned flags and never includes vehicle/BLE ids.
 */
class BmsSocialTelemetrySource(bmsRepository: BmsRepository) : SocialTelemetrySource {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    override val latest: StateFlow<EarnedTelemetry?> = combine(
        bmsRepository.activeData,
        bmsRepository.activeMotion,
    ) { battery, motion -> map(battery, motion) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun map(battery: BmsData, motion: ControllerData): EarnedTelemetry {
        val cells = battery.cellVoltages
        val cellsKnown = battery.isConnected && cells.isNotEmpty()
        val min = cells.minOrNull()
        val max = cells.maxOrNull()
        return EarnedTelemetry(
            speedKmh = TelemetryNumber.of(motion.speedKmh.toDouble(), motion.isConnected && motion.speedKnown),
            batterySocFraction = TelemetryNumber.of(battery.soc.toDouble(), battery.isConnected && battery.socKnown),
            packVoltageV = TelemetryNumber.of(battery.voltage.toDouble(), battery.isConnected),
            batteryCurrentA = TelemetryNumber.of(battery.current.toDouble(), battery.isConnected && battery.hasCurrent),
            powerW = TelemetryNumber.of(battery.power.toDouble(), battery.isConnected && battery.hasPower),
            escTempC = TelemetryNumber.of(motion.escTempC.toDouble(), motion.isConnected && motion.hasEscTemp),
            motorTempC = TelemetryNumber.of(motion.motorTempC.toDouble(), motion.isConnected && motion.hasMotorTemp),
            cellMinV = TelemetryNumber.of(min?.toDouble(), cellsKnown),
            cellMaxV = TelemetryNumber.of(max?.toDouble(), cellsKnown),
            cellDeltaV = TelemetryNumber.of((max?.minus(min ?: max) ?: 0f).toDouble(), cellsKnown),
            faults = if (battery.isConnected || motion.isConnected) {
                TelemetryFaults.known((battery.bmsFaults + motion.faults).distinct())
            } else {
                TelemetryFaults.unknown()
            },
        )
    }
}

private fun TelemetryNumber.Companion.of(value: Double?, known: Boolean): TelemetryNumber = when {
    !known -> TelemetryNumber.unknown()
    value == null || !value.isFinite() -> TelemetryNumber.unknown()
    else -> TelemetryNumber.known(value)
}
