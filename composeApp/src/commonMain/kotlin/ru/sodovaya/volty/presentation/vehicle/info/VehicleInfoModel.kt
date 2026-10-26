package ru.sodovaya.volty.presentation.vehicle.info

import ru.sodovaya.volty.domain.alert.AlertAvailability
import ru.sodovaya.volty.domain.alert.AlertUnavailableReason
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.reportsDuty
import ru.sodovaya.volty.domain.energy.CapacitySource
import ru.sodovaya.volty.domain.energy.CurrentSource
import ru.sodovaya.volty.domain.energy.EnergyRecord
import ru.sodovaya.volty.domain.energy.RangeDetail
import ru.sodovaya.volty.domain.energy.RangeUnearnedReason
import ru.sodovaya.volty.domain.model.BleDiagnosticLink
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.DeviceFactKey
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.SpeedUnknownReason
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.motionAlertRules
import ru.sodovaya.volty.domain.stats.VoltageSocEstimator
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import ru.sodovaya.volty.util.formatFixed
import kotlin.math.roundToInt

/**
 * The «Всё о ТС» screen as data: everything the app knows about one vehicle, protocol-agnostic.
 *
 * **No user-facing words live here.** A row carries an [InfoLabel] (what it is), an [InfoAbsence]
 * (why it has no value) and an [InfoChoice] (an enumerated value such as "the BMS computes the SoC");
 * the screen maps each to a string resource, so Russian and English live in `values/` and
 * `values-ru/`. What is left as text is data: numbers (already in the rider's [UnitSystem]), proper
 * nouns (a BMS brand, a controller type, a pack's own label, a BLE address) and the decoders' own
 * strings (fault codes). A numeric [InfoRow.value] is digits only; its [InfoRow.unit] names the unit, and the screen picks the localized word (SPEED, DISTANCE and CONSUMPTION numbers are already converted to the rider's [UnitSystem], so the word follows it).
 *
 * **Absent is never zero.** A row whose value is not known has `value == null` and says why in
 * [InfoRow.reason]; every live row is gated on the producer's own `has*` flag, so a placeholder
 * such as `dutyPercent = 37f` beside `hasDuty = false` is never rendered.
 */
data class VehicleInfo(val sections: List<InfoSection>)

/**
 * One block of the screen. [title] is an instance name, verbatim (a pack's label, a controller's
 * label, a link's address) - null for a section that exists once.
 */
data class InfoSection(val kind: InfoSectionKind, val title: String? = null, val rows: List<InfoRow>)

/**
 * One line. Exactly one of [value], [choice] or [reason] explains it, with these exceptions: a row
 * may carry both a [value] and a [choice] (the SoC and who computes it), and a [reason] always means
 * the value is null.
 *
 * [persisted]: the value comes from storage (what was learned in earlier connections), not from the
 * live link. [fact] and [index] identify a device-fact row ([InfoLabel.DEVICE_FACT]); [index] numbers
 * a repeat such as a branch.
 */
data class InfoRow(
    val label: InfoLabel,
    val value: String? = null,
    /** What [value] is measured in; null for a unitless number or text. The screen picks the localized word. */
    val unit: InfoUnit? = null,
    val reason: InfoAbsence? = null,
    val choice: InfoChoice? = null,
    val persisted: Boolean = false,
    val fact: DeviceFactKey? = null,
    val index: Int? = null,
)

/**
 * The unit of a numeric [InfoRow.value]. [SPEED], [DISTANCE] and [CONSUMPTION] numbers are already
 * converted to the rider's `UnitSystem` (km/h or mph, km or mi, Wh/km or Wh/mi); the screen picks the
 * word from that system. [KILOMETER] and [KMH] are fixed metric units (a protocol's own device facts).
 */
enum class InfoUnit {
    VOLT, MILLIVOLT, AMP, WATT, AMP_HOUR, WATT_HOUR, MILLIOHM, CELSIUS, PERCENT, HERTZ,
    SPEED, DISTANCE, CONSUMPTION, KILOMETER, KMH,
}

internal fun factUnit(symbol: String?): InfoUnit? = when (symbol) {
    "V" -> InfoUnit.VOLT
    "A" -> InfoUnit.AMP
    "%" -> InfoUnit.PERCENT
    "°C" -> InfoUnit.CELSIUS
    "km" -> InfoUnit.KILOMETER
    "km/h" -> InfoUnit.KMH
    else -> null
}

enum class InfoSectionKind { VEHICLE, CONNECTIONS, BATTERIES, CONTROLLERS, ENERGY, RANGE, ALERTS, DEVICE_FACTS }

enum class InfoLabel {
    // VEHICLE
    SOURCES, TOPOLOGY, CHEMISTRY, CELL_BOUNDS, SERIES_CELLS,

    // CONNECTIONS
    ADDRESS, PROTOCOL, DEVICE_NAME, MODEL, FIRMWARE, HARDWARE_CODE, FRAMES_PER_SECOND, RECONNECTS, VIA_HEAD_UNIT, LINK_STATE,

    // BATTERIES
    BMS_TYPE, PACK_CELL_COUNT, VOLTAGE, CURRENT, TEMPERATURES, CELL_MIN, CELL_MAX, CELL_DELTA, SOC, CAPACITY, CYCLES, CYCLE_CAPACITY,
    CHARGE_SWITCH, DISCHARGE_SWITCH, BMS_FAULTS,

    // CONTROLLERS
    CONTROLLER_TYPE, SPEED, DUTY, PHASE_CURRENT, BATTERY_CURRENT, INPUT_VOLTAGE, POWER, ERPM, MOTOR_TEMP, ESC_TEMP,
    CONSUMED_AH, CONSUMED_WH, REGEN_AH, REGEN_WH, ODOMETER, TRIP, CONTROLLER_FAULTS,

    // ENERGY
    ENERGY_SOC, USABLE_AH, NOMINAL_PACK_V, REMAINING_WH, CAPACITY_SOURCE, RESISTANCE, LEARNED_CAPACITY, LEARNED_RIDES,
    STORED_COUNTER, COUNTER_SOC, VOLTAGE_SOC, PACK_V, COMPENSATED_PACK_V, DISCHARGE_A, CURRENT_SOURCE, INTEGRATED_AH,
    INTEGRATED_WH, LEARN_DROP, USABLE_FRACTION, AT_REST,

    // RANGE
    TRIP_CONSUMPTION, RECENT_CONSUMPTION, BLENDED_CONSUMPTION, RANGE_TRIP_KM, RANGE_STATUS,

    // ALERTS (the value of an ACTIVE row is the 1-based level that is up)
    ALERT_DUTY, ALERT_SPEED, ALERT_MOTOR_TEMP, ALERT_ESC_TEMP,

    // DEVICE_FACTS
    DEVICE_FACT,
}

/** Why a row has no value. The screen turns each into words («колесо не отдаёт», «нет датчика», ...). */
enum class InfoAbsence {
    /** A live value, and no live link right now. */
    NOT_CONNECTED,
    /** Connected, but this pack/controller is offline. */
    OFFLINE,
    /** The device does not report it (its `has*` flag is false), or reports nothing of the kind. */
    NOT_REPORTED,
    /** The protocol cannot report it at all (a Kelly has no duty). */
    PROTOCOL_LACKS,
    /** No sensor is wired. */
    NO_SENSOR,
    /** The wheel geometry needed to turn eRPM into speed is not set. */
    NO_WHEEL_GEOMETRY,
    /** Controllers gave different reasons for no speed. */
    MIXED_CAUSES,
    /** Nothing to measure it with yet: connected but not yet seen. */
    NOT_YET,
    /** The app has not earned this yet (never learned, not enough drive). */
    NOT_EARNED,
    /** The vehicle has no motor controller. */
    NO_CONTROLLER,
    /** No speed source on any controller. */
    NO_SPEED_SOURCE,
    /** Last reading older than a few seconds: the link is up but silent, so it is unknown now. */
    STALE,
    /** Nothing is configured for it. */
    NOT_SET,
    /** The pack reports no state of charge yet. */
    SOC_UNKNOWN,

    // RANGE: why the estimate is withheld (mirrors RangeUnearnedReason)
    RANGE_NO_CAPACITY, RANGE_TOO_SHORT, RANGE_TOO_FEW_SAMPLES, RANGE_SOC_UNKNOWN, RANGE_NO_CONSUMPTION,
}

/** An enumerated row value. */
enum class InfoChoice {
    // who computes the state of charge
    SOC_BY_BMS, SOC_BY_MODEL, SOC_BY_VOLTAGE,
    // vehicle
    TOPOLOGY_PARALLEL, TOPOLOGY_SERIES,
    CHEMISTRY_LI_ION_NMC, CHEMISTRY_LIFEPO4, CHEMISTRY_LEAD_ACID,
    // where the series cell count came from
    SERIES_FROM_PACK, SERIES_FROM_PROTOCOL, SERIES_FROM_CONTROLLER, SERIES_FROM_STORED,
    // speed
    SPEED_REPORTED, SPEED_DERIVED,
    // switches / booleans
    ON, OFF, YES, NO,
    // faults
    NO_FAULTS,
    // energy
    CAPACITY_ENTERED, CAPACITY_REPORTED, CAPACITY_LEARNED, CAPACITY_NONE,
    CURRENT_FROM_CONTROLLER, CURRENT_FROM_PACKS, CURRENT_NONE,
    // range
    RANGE_EARNED,
    // links / alerts
    LINK_ONLINE, LINK_OFFLINE,
    ALERT_ARMED, ALERT_OFF, ALERT_ACTIVE,
}

/** A link's stats older than this are unknown (they refresh only on a notification). */
const val LINK_STATS_MAX_AGE_MS = 3_000L

/**
 * Builds the screen. Pure.
 *
 * @param connected a live link to this vehicle exists. When false, [data], [motion] and [links] are
 *   ignored: live rows say [InfoAbsence.NOT_CONNECTED], and only configuration and [stored] show.
 * @param stored the vehicle's persisted energy record (`VehicleEnergyStore.peek`), if any.
 * @param nowEpochMillis the clock the link-stats staleness is judged against.
 *
 * **Who computes the SoC:** a pack whose BMS type reports one -> the BMS; else, while the energy
 * model owns it -> the model; else a voltage estimate.
 *
 * WHEEL_ALERTS facts are the decoder's English labels, shown verbatim as data (not translated).
 */
fun vehicleInfoOf(
    vehicle: Vehicle,
    connected: Boolean,
    data: VehicleData?,
    motion: ControllerData?,
    links: List<BleDiagnosticLink>,
    stored: EnergyRecord?,
    range: RangeDetail?,
    availability: Map<MotionAlertKind, AlertAvailability>,
    units: UnitSystem,
    nowEpochMillis: Long,
): VehicleInfo {
    val live = connected
    val d = if (live) data else null
    val m = if (live) motion else null
    val l = if (live) links else emptyList()
    val ctx = Ctx(vehicle, live, d, m, l, stored, range, availability, units, nowEpochMillis)
    return VehicleInfo(
        buildList {
            add(ctx.vehicleSection())
            addAll(ctx.connectionSections())
            addAll(ctx.batterySections())
            addAll(ctx.controllerSections())
            add(ctx.energySection())
            add(ctx.rangeSection())
            add(ctx.alertsSection())
            addAll(ctx.factSections())
        }
    )
}

private class Ctx(
    val vehicle: Vehicle,
    val live: Boolean,
    val data: VehicleData?,
    val motion: ControllerData?,
    val links: List<BleDiagnosticLink>,
    val stored: EnergyRecord?,
    val range: RangeDetail?,
    val availability: Map<MotionAlertKind, AlertAvailability>,
    val units: UnitSystem,
    val now: Long,
) {
    private val noLive get() = if (live) InfoAbsence.NOT_YET else InfoAbsence.NOT_CONNECTED

    // ---- VEHICLE ----------------------------------------------------------------------------

    fun vehicleSection(): InfoSection {
        val sources = (vehicle.controllers.sortedBy { it.index }.map { it.controllerType.label } +
            vehicle.packs.sortedBy { it.index }.map { it.bmsType.label }).joinToString(" + ")
        val (low, high) = VoltageSocEstimator.boundsFor(vehicle.chemistry, vehicle.alertConfig)
        val rows = mutableListOf(
            InfoRow(InfoLabel.SOURCES, value = sources),
            InfoRow(InfoLabel.TOPOLOGY, choice = if (vehicle.topology == PackTopology.SERIES) InfoChoice.TOPOLOGY_SERIES else InfoChoice.TOPOLOGY_PARALLEL),
            InfoRow(InfoLabel.CHEMISTRY, choice = chemistryChoice(vehicle.chemistry)),
            InfoRow(InfoLabel.CELL_BOUNDS, value = "${formatFixed(low, 2)}-${formatFixed(high, 2)}", unit = InfoUnit.VOLT),
        )
        rows += seriesCellsRow()
        return InfoSection(InfoSectionKind.VEHICLE, rows = rows)
    }

    /** Same order the energy tracker resolves it: the pack's own count, the protocol's proof, the typed one. */
    private fun seriesCellsRow(): InfoRow {
        val known = vehicle.packs.map { it.cellCount?.takeIf { c -> c > 0 } }
        val fromPacks = when (vehicle.topology) {
            PackTopology.PARALLEL -> known.firstOrNull { it != null }
            PackTopology.SERIES -> if (known.isNotEmpty() && known.all { it != null }) known.sumOf { it!! } else null
        }
        if (fromPacks != null) return InfoRow(InfoLabel.SERIES_CELLS, value = fromPacks.toString(), choice = InfoChoice.SERIES_FROM_PACK)
        val proven = links.flatMap { it.facts }.firstOrNull { it.key == DeviceFactKey.SERIES_CELLS }?.value?.toIntOrNull()
        if (proven != null) return InfoRow(InfoLabel.SERIES_CELLS, value = proven.toString(), choice = InfoChoice.SERIES_FROM_PROTOCOL)
        val typed = vehicle.controllers.firstOrNull { it.providesDerivedBattery && it.derivedCellCount != null }?.derivedCellCount
        if (typed != null) return InfoRow(InfoLabel.SERIES_CELLS, value = typed.toString(), choice = InfoChoice.SERIES_FROM_CONTROLLER)
        if (stored != null) return InfoRow(InfoLabel.SERIES_CELLS, value = stored.seriesCells.toString(), choice = InfoChoice.SERIES_FROM_STORED, persisted = true)
        return InfoRow(InfoLabel.SERIES_CELLS, reason = InfoAbsence.NOT_SET)
    }

    // ---- CONNECTIONS ------------------------------------------------------------------------

    fun connectionSections(): List<InfoSection> {
        val addresses = (vehicle.controllers.map { it.address } + vehicle.packs.map { it.bmsAddress }).distinct()
        return addresses.map { address ->
            val link = links.firstOrNull { it.deviceAddress == address }
            val behindHead = vehicle.packs.any { it.bmsAddress == address && it.canId != null } ||
                vehicle.controllers.any { it.address == address && it.canId != null }
            val rows = mutableListOf(InfoRow(InfoLabel.ADDRESS, value = address))
            if (link == null) {
                rows += InfoRow(InfoLabel.LINK_STATE, reason = if (live) InfoAbsence.OFFLINE else InfoAbsence.NOT_CONNECTED)
            } else {
                val fresh = statsFresh(link)
                rows += InfoRow(InfoLabel.LINK_STATE, choice = InfoChoice.LINK_ONLINE)
                rows += InfoRow(InfoLabel.PROTOCOL, value = link.protocol)
                rows += textOrAbsent(InfoLabel.DEVICE_NAME, link.deviceName)
                rows += textOrAbsent(InfoLabel.MODEL, link.model)
                rows += textOrAbsent(InfoLabel.FIRMWARE, link.firmwareVersion)
                rows += textOrAbsent(InfoLabel.HARDWARE_CODE, link.hardwareCode)
                rows += when {
                    !fresh -> InfoRow(InfoLabel.FRAMES_PER_SECOND, reason = InfoAbsence.STALE)
                    link.framesPerSecond == null -> InfoRow(InfoLabel.FRAMES_PER_SECOND, reason = InfoAbsence.NOT_YET)
                    else -> InfoRow(InfoLabel.FRAMES_PER_SECOND, value = formatFixed(link.framesPerSecond!!, 1), unit = InfoUnit.HERTZ)
                }
                rows += InfoRow(InfoLabel.RECONNECTS, value = link.reconnects.toString())
            }
            rows += InfoRow(InfoLabel.VIA_HEAD_UNIT, choice = if (behindHead) InfoChoice.YES else InfoChoice.NO)
            InfoSection(InfoSectionKind.CONNECTIONS, title = address, rows = rows)
        }
    }

    private fun statsFresh(link: BleDiagnosticLink): Boolean {
        val at = link.statsAtEpochMillis ?: return false
        return now - at <= LINK_STATS_MAX_AGE_MS
    }

    private fun textOrAbsent(label: InfoLabel, text: String?) =
        if (text.isNullOrBlank()) InfoRow(label, reason = InfoAbsence.NOT_REPORTED) else InfoRow(label, value = text)

    // ---- BATTERIES --------------------------------------------------------------------------

    fun batterySections(): List<InfoSection> = vehicle.packs.sortedBy { it.index }.map { pack ->
        val state = data?.packs?.firstOrNull { it.pack.index == pack.index }
        val online = state?.takeIf { it.isOnline }
        val absent = if (!live) InfoAbsence.NOT_CONNECTED else InfoAbsence.OFFLINE
        val rows = mutableListOf(
            InfoRow(InfoLabel.BMS_TYPE, value = pack.bmsType.label),
            if (pack.cellCount != null) InfoRow(InfoLabel.PACK_CELL_COUNT, value = pack.cellCount.toString())
            else InfoRow(InfoLabel.PACK_CELL_COUNT, reason = InfoAbsence.NOT_SET),
        )
        rows += socRow(pack.bmsType.reportsStateOfCharge, online, absent)
        if (online == null) {
            for (label in listOf(
                InfoLabel.VOLTAGE, InfoLabel.CURRENT, InfoLabel.TEMPERATURES, InfoLabel.CELL_MIN, InfoLabel.CELL_MAX,
                InfoLabel.CELL_DELTA, InfoLabel.CAPACITY, InfoLabel.CYCLES, InfoLabel.CHARGE_SWITCH,
                InfoLabel.DISCHARGE_SWITCH, InfoLabel.BMS_FAULTS,
            ) + if (pack.bmsType == BmsType.ANT_BMS) listOf(InfoLabel.CYCLE_CAPACITY) else emptyList()) rows += InfoRow(label, reason = absent)
        } else {
            val b = online.data
            rows += InfoRow(InfoLabel.VOLTAGE, value = "${formatFixed(b.voltage, 2)}", unit = InfoUnit.VOLT)
            rows += if (b.hasCurrent) InfoRow(InfoLabel.CURRENT, value = "${formatFixed(b.current, 1)}", unit = InfoUnit.AMP)
            else InfoRow(InfoLabel.CURRENT, reason = InfoAbsence.NOT_REPORTED)
            rows += if (b.temperatures.isEmpty()) InfoRow(InfoLabel.TEMPERATURES, reason = InfoAbsence.NO_SENSOR)
            else InfoRow(InfoLabel.TEMPERATURES, value = b.temperatures.joinToString(", ") { formatFixed(it, 1) }, unit = InfoUnit.CELSIUS)
            if (b.cellVoltages.isEmpty()) {
                rows += InfoRow(InfoLabel.CELL_MIN, reason = InfoAbsence.NOT_REPORTED)
                rows += InfoRow(InfoLabel.CELL_MAX, reason = InfoAbsence.NOT_REPORTED)
                rows += InfoRow(InfoLabel.CELL_DELTA, reason = InfoAbsence.NOT_REPORTED)
            } else {
                val lo = b.cellVoltages.min(); val hi = b.cellVoltages.max()
                rows += InfoRow(InfoLabel.CELL_MIN, value = "${formatFixed(lo, 3)}", unit = InfoUnit.VOLT)
                rows += InfoRow(InfoLabel.CELL_MAX, value = "${formatFixed(hi, 3)}", unit = InfoUnit.VOLT)
                rows += InfoRow(InfoLabel.CELL_DELTA, value = "${((hi - lo) * 1000f).roundToInt()}", unit = InfoUnit.MILLIVOLT)
            }
            // BmsData has no has* flag for capacity or cycles, so a reported 0 and "never reported" look the
            // same on the sample. The value is shown as-is (a new pack really has 0 cycles) rather than
            // claiming the BMS is silent. The one thing the type does tell us: ANT has no cycle COUNTER,
            // only the cumulative Ah it has cycled, which gets its own label.
            rows += InfoRow(InfoLabel.CAPACITY, value = formatFixed(b.capacity, 1), unit = InfoUnit.AMP_HOUR)
            if (pack.bmsType == BmsType.ANT_BMS) {
                rows += InfoRow(InfoLabel.CYCLES, reason = InfoAbsence.PROTOCOL_LACKS)
                rows += InfoRow(InfoLabel.CYCLE_CAPACITY, value = formatFixed(b.cycleCapacityAh, 0), unit = InfoUnit.AMP_HOUR)
            } else {
                rows += InfoRow(InfoLabel.CYCLES, value = b.numCycles.toString())
            }
            rows += InfoRow(InfoLabel.CHARGE_SWITCH, choice = if (b.chargeEnabled) InfoChoice.ON else InfoChoice.OFF)
            rows += InfoRow(InfoLabel.DISCHARGE_SWITCH, choice = if (b.dischargeEnabled) InfoChoice.ON else InfoChoice.OFF)
            rows += if (b.bmsFaults.isEmpty()) InfoRow(InfoLabel.BMS_FAULTS, choice = InfoChoice.NO_FAULTS)
            else InfoRow(InfoLabel.BMS_FAULTS, value = b.bmsFaults.joinToString(", "))
        }
        InfoSection(InfoSectionKind.BATTERIES, title = pack.label, rows = rows)
    }

    /** The SoC row: the value (when known) and who computes it. */
    private fun socRow(bmsReports: Boolean, online: PackState?, absent: InfoAbsence): InfoRow {
        val who = when {
            bmsReports -> InfoChoice.SOC_BY_BMS
            data?.energy?.modelled == true || (data?.energy == null && stored != null) -> InfoChoice.SOC_BY_MODEL
            else -> InfoChoice.SOC_BY_VOLTAGE
        }
        if (online == null) return InfoRow(InfoLabel.SOC, reason = absent, choice = who)
        val b: BmsData = online.data
        return if (b.socKnown) InfoRow(InfoLabel.SOC, value = "${formatFixed(b.soc, 1)}", unit = InfoUnit.PERCENT, choice = who)
        else InfoRow(InfoLabel.SOC, reason = if (who == InfoChoice.SOC_BY_MODEL) InfoAbsence.NOT_EARNED else InfoAbsence.SOC_UNKNOWN, choice = who)
    }

    // ---- CONTROLLERS ------------------------------------------------------------------------

    fun controllerSections(): List<InfoSection> = vehicle.controllers.sortedBy { it.index }.map { controller ->
        val state: ControllerState? = data?.controllers?.firstOrNull { it.controller.index == controller.index }?.takeIf { it.isOnline }
        val absent = if (!live) InfoAbsence.NOT_CONNECTED else InfoAbsence.OFFLINE
        val rows = mutableListOf(InfoRow(InfoLabel.CONTROLLER_TYPE, value = controller.controllerType.label))
        if (state == null) {
            for (label in listOf(
                InfoLabel.SPEED, InfoLabel.DUTY, InfoLabel.PHASE_CURRENT, InfoLabel.BATTERY_CURRENT, InfoLabel.INPUT_VOLTAGE,
                InfoLabel.POWER, InfoLabel.ERPM, InfoLabel.MOTOR_TEMP, InfoLabel.ESC_TEMP, InfoLabel.CONSUMED_AH,
                InfoLabel.CONSUMED_WH, InfoLabel.REGEN_AH, InfoLabel.REGEN_WH, InfoLabel.ODOMETER, InfoLabel.TRIP,
                InfoLabel.CONTROLLER_FAULTS,
            )) rows += InfoRow(label, reason = absent)
        } else {
            val c = state.data
            rows += if (c.speedKnown) InfoRow(
                InfoLabel.SPEED, value = UnitFormatter.speed(c.speedKmh, units), unit = InfoUnit.SPEED,
                choice = if (c.speedSource == SpeedSource.DERIVED) InfoChoice.SPEED_DERIVED else InfoChoice.SPEED_REPORTED,
            ) else InfoRow(InfoLabel.SPEED, reason = when (c.speedUnknownReason) {
                SpeedUnknownReason.NO_WHEEL_GEOMETRY -> InfoAbsence.NO_WHEEL_GEOMETRY
                SpeedUnknownReason.MIXED -> InfoAbsence.MIXED_CAUSES
                SpeedUnknownReason.FIRMWARE_DID_NOT_REPORT, null -> InfoAbsence.NOT_REPORTED
            })
            rows += when {
                !controller.controllerType.reportsDuty -> InfoRow(InfoLabel.DUTY, reason = InfoAbsence.PROTOCOL_LACKS)
                !c.hasDuty -> InfoRow(InfoLabel.DUTY, reason = InfoAbsence.NOT_REPORTED)
                else -> InfoRow(InfoLabel.DUTY, value = "${formatFixed(c.dutyPercent, 1)}", unit = InfoUnit.PERCENT)
            }
            rows += InfoRow(InfoLabel.PHASE_CURRENT, value = "${formatFixed(c.motorCurrentA, 1)}", unit = InfoUnit.AMP)
            rows += if (c.hasBatteryCurrent) InfoRow(InfoLabel.BATTERY_CURRENT, value = "${formatFixed(c.batteryCurrentA, 1)}", unit = InfoUnit.AMP)
            else InfoRow(InfoLabel.BATTERY_CURRENT, reason = InfoAbsence.NOT_REPORTED)
            rows += if (c.hasInputVoltage) InfoRow(InfoLabel.INPUT_VOLTAGE, value = "${formatFixed(c.inputVoltageV, 1)}", unit = InfoUnit.VOLT)
            else InfoRow(InfoLabel.INPUT_VOLTAGE, reason = InfoAbsence.NOT_REPORTED)
            rows += if (c.hasPower) InfoRow(InfoLabel.POWER, value = "${c.powerW.roundToInt()}", unit = InfoUnit.WATT)
            else InfoRow(InfoLabel.POWER, reason = InfoAbsence.NOT_REPORTED)
            rows += InfoRow(InfoLabel.ERPM, value = c.eRpm.roundToInt().toString())
            rows += if (c.hasMotorTemp) InfoRow(InfoLabel.MOTOR_TEMP, value = "${formatFixed(c.motorTempC, 1)}", unit = InfoUnit.CELSIUS)
            else InfoRow(InfoLabel.MOTOR_TEMP, reason = InfoAbsence.NO_SENSOR)
            rows += if (c.hasEscTemp) InfoRow(InfoLabel.ESC_TEMP, value = "${formatFixed(c.escTempC, 1)}", unit = InfoUnit.CELSIUS)
            else InfoRow(InfoLabel.ESC_TEMP, reason = InfoAbsence.NO_SENSOR)
            if (c.hasEnergyCounters) {
                rows += InfoRow(InfoLabel.CONSUMED_AH, value = "${formatFixed(c.consumedAh, 2)}", unit = InfoUnit.AMP_HOUR)
                rows += InfoRow(InfoLabel.CONSUMED_WH, value = "${formatFixed(c.consumedWh, 1)}", unit = InfoUnit.WATT_HOUR)
                rows += InfoRow(InfoLabel.REGEN_AH, value = "${formatFixed(c.regenAh, 2)}", unit = InfoUnit.AMP_HOUR)
                rows += InfoRow(InfoLabel.REGEN_WH, value = "${formatFixed(c.regenWh, 1)}", unit = InfoUnit.WATT_HOUR)
            } else {
                for (label in listOf(InfoLabel.CONSUMED_AH, InfoLabel.CONSUMED_WH, InfoLabel.REGEN_AH, InfoLabel.REGEN_WH))
                    rows += InfoRow(label, reason = InfoAbsence.NOT_REPORTED)
            }
            if (c.hasDistance) {
                rows += InfoRow(InfoLabel.ODOMETER, value = UnitFormatter.distance(c.odometerKm, units), unit = InfoUnit.DISTANCE)
                rows += InfoRow(InfoLabel.TRIP, value = UnitFormatter.distance(c.tripKm, units, 2), unit = InfoUnit.DISTANCE)
            } else {
                rows += InfoRow(InfoLabel.ODOMETER, reason = InfoAbsence.NOT_REPORTED)
                rows += InfoRow(InfoLabel.TRIP, reason = InfoAbsence.NOT_REPORTED)
            }
            rows += if (c.faults.isEmpty()) InfoRow(InfoLabel.CONTROLLER_FAULTS, choice = InfoChoice.NO_FAULTS)
            else InfoRow(InfoLabel.CONTROLLER_FAULTS, value = c.faults.joinToString(", "))
        }
        InfoSection(InfoSectionKind.CONTROLLERS, title = controller.label, rows = rows)
    }

    // ---- ENERGY -----------------------------------------------------------------------------

    fun energySection(): InfoSection {
        val e = data?.energy
        val memory = stored?.memory
        val rows = mutableListOf<InfoRow>()
        val liveAbsent = if (!live) InfoAbsence.NOT_CONNECTED else InfoAbsence.NOT_YET
        fun liveRow(label: InfoLabel, v: String?, unit: InfoUnit) =
            if (e != null && v != null) InfoRow(label, value = v, unit = unit) else InfoRow(label, reason = liveAbsent)
        rows += liveRow(InfoLabel.ENERGY_SOC, e?.socPercent?.let { formatFixed(it, 1) }, InfoUnit.PERCENT)
        rows += liveRow(InfoLabel.USABLE_AH, e?.usableAh?.let { formatFixed(it, 1) }, InfoUnit.AMP_HOUR)
        rows += liveRow(InfoLabel.NOMINAL_PACK_V, e?.nominalPackV?.let { formatFixed(it, 1) }, InfoUnit.VOLT)
        rows += liveRow(InfoLabel.REMAINING_WH, e?.remainingWh?.let { it.roundToInt().toString() }, InfoUnit.WATT_HOUR)
        rows += if (e != null) InfoRow(InfoLabel.CAPACITY_SOURCE, choice = when (e.capacitySource) {
            CapacitySource.ENTERED -> InfoChoice.CAPACITY_ENTERED
            CapacitySource.REPORTED -> InfoChoice.CAPACITY_REPORTED
            CapacitySource.LEARNED -> InfoChoice.CAPACITY_LEARNED
            CapacitySource.NONE -> InfoChoice.CAPACITY_NONE
        }) else InfoRow(InfoLabel.CAPACITY_SOURCE, reason = liveAbsent)

        // Memory: what was learned lives on between connections, so the stored value fills in when
        // the live one is absent. [persisted] says which one the rider is looking at.
        val resistance = e?.resistanceOhm
        rows += when {
            resistance != null -> InfoRow(InfoLabel.RESISTANCE, value = milliOhm(resistance), unit = InfoUnit.MILLIOHM)
            memory?.resistanceOhm != null -> InfoRow(InfoLabel.RESISTANCE, value = milliOhm(memory.resistanceOhm), unit = InfoUnit.MILLIOHM, persisted = true)
            else -> InfoRow(InfoLabel.RESISTANCE, reason = InfoAbsence.NOT_EARNED)
        }
        val learned = e?.learned
        val learnedUsed = learned ?: memory?.learned
        val learnedPersisted = learned == null && memory?.learned != null
        rows += if (learnedUsed != null) InfoRow(InfoLabel.LEARNED_CAPACITY, value = "${formatFixed(learnedUsed.usableAh, 1)}", unit = InfoUnit.AMP_HOUR, persisted = learnedPersisted)
        else InfoRow(InfoLabel.LEARNED_CAPACITY, reason = InfoAbsence.NOT_EARNED)
        rows += if (learnedUsed != null) InfoRow(InfoLabel.LEARNED_RIDES, value = learnedUsed.rides.toString(), persisted = learnedPersisted)
        else InfoRow(InfoLabel.LEARNED_RIDES, reason = InfoAbsence.NOT_EARNED)
        // The persisted counter is shown only when no live one is (it is the value the next connect seeds from).
        memory?.counter?.takeIf { e?.detail?.counterSocPercent == null }?.let {
            rows += InfoRow(InfoLabel.STORED_COUNTER, value = "${formatFixed(it.percent, 1)}", unit = InfoUnit.PERCENT, persisted = true)
        }

        // The model's working exists only while the tracker owns the SoC; with a smart BMS there is none
        // and no row pretends otherwise.
        val detail = e?.detail
        if (detail != null) {
            rows += optional(InfoLabel.COUNTER_SOC, detail.counterSocPercent?.let { formatFixed(it, 1) }, InfoUnit.PERCENT)
            rows += optional(InfoLabel.VOLTAGE_SOC, detail.voltageSocPercent?.let { formatFixed(it, 1) }, InfoUnit.PERCENT)
            rows += optional(InfoLabel.PACK_V, detail.packV?.let { formatFixed(it, 1) }, InfoUnit.VOLT)
            rows += optional(InfoLabel.COMPENSATED_PACK_V, detail.compensatedPackV?.let { formatFixed(it, 1) }, InfoUnit.VOLT)
            rows += optional(InfoLabel.DISCHARGE_A, detail.dischargeA?.let { formatFixed(it, 1) }, InfoUnit.AMP)
            rows += InfoRow(InfoLabel.CURRENT_SOURCE, choice = when (detail.currentSource) {
                CurrentSource.CONTROLLER -> InfoChoice.CURRENT_FROM_CONTROLLER
                CurrentSource.PACKS -> InfoChoice.CURRENT_FROM_PACKS
                CurrentSource.NONE -> InfoChoice.CURRENT_NONE
            })
            rows += InfoRow(InfoLabel.INTEGRATED_AH, value = "${formatFixed(detail.integratedAh.toFloat(), 2)}", unit = InfoUnit.AMP_HOUR)
            rows += InfoRow(InfoLabel.INTEGRATED_WH, value = "${formatFixed(detail.integratedWh.toFloat(), 1)}", unit = InfoUnit.WATT_HOUR)
            rows += optional(InfoLabel.LEARN_DROP, detail.learnDropPercent?.let { formatFixed(it, 1) }, InfoUnit.PERCENT)
            rows += optional(InfoLabel.USABLE_FRACTION, detail.usableFraction?.let { formatFixed(it * 100f, 0) }, InfoUnit.PERCENT)
            rows += InfoRow(InfoLabel.AT_REST, choice = if (detail.atRest) InfoChoice.YES else InfoChoice.NO)
        } else if (!live) {
            for (label in listOf(InfoLabel.COUNTER_SOC, InfoLabel.VOLTAGE_SOC, InfoLabel.PACK_V, InfoLabel.COMPENSATED_PACK_V,
                InfoLabel.DISCHARGE_A, InfoLabel.INTEGRATED_AH, InfoLabel.INTEGRATED_WH, InfoLabel.LEARN_DROP, InfoLabel.AT_REST))
                rows += InfoRow(label, reason = InfoAbsence.NOT_CONNECTED)
        }
        return InfoSection(InfoSectionKind.ENERGY, rows = rows)
    }

    private fun optional(label: InfoLabel, v: String?, unit: InfoUnit) =
        if (v != null) InfoRow(label, value = v, unit = unit) else InfoRow(label, reason = InfoAbsence.NOT_EARNED)

    private fun milliOhm(ohm: Float) = formatFixed(ohm * 1000f, 1)

    // ---- RANGE ------------------------------------------------------------------------------

    fun rangeSection(): InfoSection {
        val r = range.takeIf { live }
        if (r == null) {
            return InfoSection(InfoSectionKind.RANGE, rows = listOf(
                InfoLabel.TRIP_CONSUMPTION, InfoLabel.RECENT_CONSUMPTION, InfoLabel.BLENDED_CONSUMPTION,
                InfoLabel.RANGE_TRIP_KM, InfoLabel.RANGE_STATUS,
            ).map { InfoRow(it, reason = noLive) })
        }
        val why = r.unearnedReason?.let(::rangeAbsence)
        fun cons(label: InfoLabel, whPerKm: Double?) = if (whPerKm == null) InfoRow(label, reason = why ?: InfoAbsence.NOT_EARNED)
        else InfoRow(label, value = formatFixed(UnitFormatter.consumptionValue(whPerKm.toFloat(), units), 1), unit = InfoUnit.CONSUMPTION)
        return InfoSection(InfoSectionKind.RANGE, rows = listOf(
            cons(InfoLabel.TRIP_CONSUMPTION, r.tripWhPerKm),
            cons(InfoLabel.RECENT_CONSUMPTION, r.recentWhPerKm),
            cons(InfoLabel.BLENDED_CONSUMPTION, r.blendedWhPerKm),
            if (r.tripKm == null) InfoRow(InfoLabel.RANGE_TRIP_KM, reason = why ?: InfoAbsence.NOT_EARNED)
            else InfoRow(InfoLabel.RANGE_TRIP_KM, value = UnitFormatter.distance(r.tripKm.toFloat(), units, 2), unit = InfoUnit.DISTANCE),
            if (why != null) InfoRow(InfoLabel.RANGE_STATUS, reason = why) else InfoRow(InfoLabel.RANGE_STATUS, choice = InfoChoice.RANGE_EARNED),
        ))
    }

    private fun rangeAbsence(r: RangeUnearnedReason) = when (r) {
        RangeUnearnedReason.NO_CAPACITY -> InfoAbsence.RANGE_NO_CAPACITY
        RangeUnearnedReason.TOO_SHORT -> InfoAbsence.RANGE_TOO_SHORT
        RangeUnearnedReason.TOO_FEW_SAMPLES -> InfoAbsence.RANGE_TOO_FEW_SAMPLES
        RangeUnearnedReason.SOC_UNKNOWN -> InfoAbsence.RANGE_SOC_UNKNOWN
        RangeUnearnedReason.NO_CONSUMPTION -> InfoAbsence.RANGE_NO_CONSUMPTION
    }

    // ---- ALERTS -----------------------------------------------------------------------------

    fun alertsSection(): InfoSection {
        val rules = vehicle.motionAlertRules.associateBy { it.kind }
        val rows = MotionAlertKind.entries.map { kind ->
            val label = when (kind) {
                MotionAlertKind.DUTY -> InfoLabel.ALERT_DUTY
                MotionAlertKind.SPEED -> InfoLabel.ALERT_SPEED
                MotionAlertKind.MOTOR_TEMP -> InfoLabel.ALERT_MOTOR_TEMP
                MotionAlertKind.ESC_TEMP -> InfoLabel.ALERT_ESC_TEMP
            }
            when (val a = availability[kind] ?: AlertAvailability.Unknown) {
                is AlertAvailability.Unavailable -> InfoRow(label, reason = alertAbsence(a.reason))
                AlertAvailability.Unknown -> InfoRow(label, reason = if (live) InfoAbsence.NOT_YET else InfoAbsence.NOT_CONNECTED)
                AlertAvailability.Available -> {
                    val rule = rules[kind]
                    if (rule == null || rule.isOff) InfoRow(label, choice = InfoChoice.ALERT_OFF)
                    else {
                        val reading = alertReading(kind)
                        val up = reading?.let { r ->
                            rule.levels.withIndex().filter { it.value.enabled && r >= it.value.thresholdValue }.maxByOrNull { it.index }
                        }
                        if (up != null) InfoRow(label, value = (up.index + 1).toString(), choice = InfoChoice.ALERT_ACTIVE)
                        else InfoRow(label, choice = InfoChoice.ALERT_ARMED)
                    }
                }
            }
        }
        return InfoSection(InfoSectionKind.ALERTS, rows = rows)
    }

    /** The live number a rule compares against, or null when the sample does not honestly carry it. */
    private fun alertReading(kind: MotionAlertKind): Float? {
        val c = motion ?: return null
        return when (kind) {
            MotionAlertKind.DUTY -> c.dutyPercent.takeIf { c.hasDuty }
            MotionAlertKind.SPEED -> c.speedKmh.takeIf { c.speedKnown }
            MotionAlertKind.MOTOR_TEMP -> c.motorTempC.takeIf { c.hasMotorTemp }
            MotionAlertKind.ESC_TEMP -> c.escTempC.takeIf { c.hasEscTemp }
        }
    }

    private fun alertAbsence(r: AlertUnavailableReason) = when (r) {
        AlertUnavailableReason.NoController -> InfoAbsence.NO_CONTROLLER
        is AlertUnavailableReason.ControllerReportsNoDuty -> InfoAbsence.PROTOCOL_LACKS
        AlertUnavailableReason.ControllerHasNotReportedDuty -> InfoAbsence.NOT_REPORTED
        AlertUnavailableReason.NoMotorTempSensor, AlertUnavailableReason.NoEscTempSensor -> InfoAbsence.NO_SENSOR
        AlertUnavailableReason.NoSpeedSource -> InfoAbsence.NO_SPEED_SOURCE
    }

    // ---- DEVICE FACTS -----------------------------------------------------------------------

    /** One section per link that has facts; a link with none contributes nothing. Stale facts are unknown. */
    fun factSections(): List<InfoSection> = links.filter { it.facts.isNotEmpty() }.map { link ->
        val fresh = statsFresh(link)
        InfoSection(InfoSectionKind.DEVICE_FACTS, title = link.deviceAddress, rows = link.facts.map { f ->
            if (fresh) InfoRow(InfoLabel.DEVICE_FACT, value = f.value, unit = factUnit(f.unit), fact = f.key, index = f.index)
            else InfoRow(InfoLabel.DEVICE_FACT, reason = InfoAbsence.STALE, fact = f.key, index = f.index)
        })
    }

    private fun chemistryChoice(c: Chemistry) = when (c) {
        Chemistry.LI_ION_NMC -> InfoChoice.CHEMISTRY_LI_ION_NMC
        Chemistry.LIFEPO4 -> InfoChoice.CHEMISTRY_LIFEPO4
        Chemistry.LEAD_ACID -> InfoChoice.CHEMISTRY_LEAD_ACID
    }
}
