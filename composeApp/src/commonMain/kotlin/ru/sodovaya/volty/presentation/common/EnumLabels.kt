package ru.sodovaya.volty.presentation.common

import androidx.compose.runtime.Composable
import ru.sodovaya.volty.domain.alert.AlertAvailability
import ru.sodovaya.volty.domain.alert.AlertUnavailableReason
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.bmsTypeOrNull
import ru.sodovaya.volty.domain.model.primaryController
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.alert_availability_unknown
import volty.composeapp.generated.resources.alert_unavailable_duty_not_reported_yet
import volty.composeapp.generated.resources.alert_unavailable_no_controller
import volty.composeapp.generated.resources.alert_unavailable_no_duty
import volty.composeapp.generated.resources.alert_unavailable_no_esc_temp
import volty.composeapp.generated.resources.alert_unavailable_no_motor_temp
import volty.composeapp.generated.resources.alert_unavailable_no_speed
import volty.composeapp.generated.resources.bms_ant
import volty.composeapp.generated.resources.bms_begode
import volty.composeapp.generated.resources.bms_daly
import volty.composeapp.generated.resources.bms_jbd
import volty.composeapp.generated.resources.bms_jk
import volty.composeapp.generated.resources.bms_vesc
import volty.composeapp.generated.resources.chemistry_lead_acid
import volty.composeapp.generated.resources.chemistry_lifepo4
import volty.composeapp.generated.resources.chemistry_li_ion_nmc
import volty.composeapp.generated.resources.dashboard_style_classic
import volty.composeapp.generated.resources.dashboard_style_clean
import volty.composeapp.generated.resources.motion_alert_duty
import volty.composeapp.generated.resources.motion_alert_esc_temp
import volty.composeapp.generated.resources.motion_alert_motor_temp
import volty.composeapp.generated.resources.motion_alert_speed
import volty.composeapp.generated.resources.secondary_gauge_battery
import volty.composeapp.generated.resources.secondary_gauge_consumption
import volty.composeapp.generated.resources.secondary_gauge_current
import volty.composeapp.generated.resources.secondary_gauge_duty
import volty.composeapp.generated.resources.secondary_gauge_esc_temp
import volty.composeapp.generated.resources.secondary_gauge_motor_temp
import volty.composeapp.generated.resources.secondary_gauge_power
import volty.composeapp.generated.resources.unit_celsius
import volty.composeapp.generated.resources.unit_kmh
import volty.composeapp.generated.resources.unit_percent

@Composable
fun bmsTypeLabel(type: BmsType): String = stringResource(
    when (type) {
        BmsType.JK_BMS -> Res.string.bms_jk
        BmsType.JBD_BMS -> Res.string.bms_jbd
        BmsType.ANT_BMS -> Res.string.bms_ant
        BmsType.DALY_BMS -> Res.string.bms_daly
        BmsType.BEGODE -> Res.string.bms_begode
        BmsType.VESC_BMS -> Res.string.bms_vesc
    }
)

/**
 * Which of a vehicle's two possible names a subtitle leads with when the
 * vehicle has BOTH a motor controller and a BMS. This is a presentation
 * choice, not a fact about the vehicle: a screen showing motion telemetry
 * wants the controller named, while one showing cells, faults and chemistry
 * wants the BMS named. Either way the *other* source is the fallback, so a
 * vehicle with only one source reads the same under both preferences.
 */
enum class SourcePreference { CONTROLLER, BMS }

/**
 * The one label that names what a vehicle's telemetry comes from — the motor
 * controller (VESC / FarDriver / …) or the BMS, led by [prefer] and falling
 * back to whichever is present.
 *
 * Null when neither is available — no vehicle at all, or (not reachable today)
 * a vehicle with no sources. Callers must then OMIT the segment rather than
 * render "null", an empty chip, or a dash standing in for a real label; the
 * two status lines that need a non-null argument for a format string supply
 * their own em-dash placeholder at the call site.
 *
 * Single source of truth on purpose: this fallback used to be copy-pasted, and
 * divergent copies of exactly this kind produced the Clean/Classic parity bugs.
 * [prefer] parameterises the *order* of the one chain — it does not fork it.
 */
@Composable
fun vehicleSourceLabel(
    vehicle: Vehicle?,
    prefer: SourcePreference = SourcePreference.CONTROLLER
): String? {
    val controller = vehicle?.primaryController?.controllerType?.label
    val bms = vehicle?.bmsTypeOrNull?.let { bmsTypeLabel(it) }
    return when (prefer) {
        SourcePreference.CONTROLLER -> controller ?: bms
        SourcePreference.BMS -> bms ?: controller
    }
}

@Composable
fun chemistryLabel(chemistry: Chemistry): String = stringResource(
    when (chemistry) {
        Chemistry.LI_ION_NMC -> Res.string.chemistry_li_ion_nmc
        Chemistry.LIFEPO4 -> Res.string.chemistry_lifepo4
        Chemistry.LEAD_ACID -> Res.string.chemistry_lead_acid
    }
)

@Composable
fun dashboardStyleLabel(style: DashboardStyle): String = stringResource(
    when (style) {
        DashboardStyle.CLEAN -> Res.string.dashboard_style_clean
        DashboardStyle.CLASSIC -> Res.string.dashboard_style_classic
    }
)

/**
 * The rider-facing sentence for a motion alert the hardware cannot supply
 * (`F §10`). The reason text *is* the point — a greyed row with no explanation
 * is the version that invites "why can't I turn this on?".
 *
 * The mapping lives here, not in `domain/alert`, for the same reason every other
 * label in this file does: `domain/` imports nothing from Compose (verified — no
 * file under `domain/` imports `data` or resources), and the Russian wording
 * belongs in `values-ru/strings.xml` beside every other translated string rather
 * than as a literal in a pure module.
 */
@Composable
fun alertUnavailableReasonLabel(reason: AlertUnavailableReason): String = when (reason) {
    AlertUnavailableReason.NoController ->
        stringResource(Res.string.alert_unavailable_no_controller)
    is AlertUnavailableReason.ControllerReportsNoDuty ->
        stringResource(Res.string.alert_unavailable_no_duty, reason.type.label)
    AlertUnavailableReason.ControllerHasNotReportedDuty ->
        stringResource(Res.string.alert_unavailable_duty_not_reported_yet)
    AlertUnavailableReason.NoMotorTempSensor ->
        stringResource(Res.string.alert_unavailable_no_motor_temp)
    AlertUnavailableReason.NoEscTempSensor ->
        stringResource(Res.string.alert_unavailable_no_esc_temp)
    AlertUnavailableReason.NoSpeedSource ->
        stringResource(Res.string.alert_unavailable_no_speed)
}

/**
 * The explanatory line under a motion alert row, or null when the alert is
 * available and needs no explanation.
 *
 * [AlertAvailability.Unknown] gets its own wording on purpose: it must never
 * read as "your hardware lacks this sensor", because on a vehicle that has
 * simply never been connected that is a claim we have not earned.
 */
@Composable
fun alertAvailabilityNote(availability: AlertAvailability): String? = when (availability) {
    AlertAvailability.Available -> null
    AlertAvailability.Unknown -> stringResource(Res.string.alert_availability_unknown)
    is AlertAvailability.Unavailable -> alertUnavailableReasonLabel(availability.reason)
}

/** The rider-facing name of a motion alert kind, for the settings screen's row header. */
@Composable
fun motionAlertKindLabel(kind: MotionAlertKind): String = stringResource(
    when (kind) {
        MotionAlertKind.DUTY -> Res.string.motion_alert_duty
        MotionAlertKind.SPEED -> Res.string.motion_alert_speed
        MotionAlertKind.MOTOR_TEMP -> Res.string.motion_alert_motor_temp
        MotionAlertKind.ESC_TEMP -> Res.string.motion_alert_esc_temp
    }
)

/**
 * The unit a kind's threshold is entered in — the metric's own unit, as
 * [ru.sodovaya.volty.domain.alert.AlertLevel.thresholdValue] defines it.
 *
 * Speed is **km/h regardless of the app's unit system**: the stored threshold is
 * km/h (F §3's `speedLimitKmh`), and displaying mph while writing km/h would let
 * a rider set 30 meaning mph and get an alarm at 30 km/h.
 */
@Composable
fun motionAlertUnitLabel(kind: MotionAlertKind): String = stringResource(
    when (kind) {
        MotionAlertKind.DUTY -> Res.string.unit_percent
        MotionAlertKind.SPEED -> Res.string.unit_kmh
        MotionAlertKind.MOTOR_TEMP -> Res.string.unit_celsius
        MotionAlertKind.ESC_TEMP -> Res.string.unit_celsius
    }
)

@Composable
fun secondaryGaugeLabel(gauge: SecondaryGauge): String = stringResource(
    when (gauge) {
        SecondaryGauge.DUTY -> Res.string.secondary_gauge_duty
        SecondaryGauge.BATTERY -> Res.string.secondary_gauge_battery
        SecondaryGauge.POWER -> Res.string.secondary_gauge_power
        SecondaryGauge.CURRENT -> Res.string.secondary_gauge_current
        SecondaryGauge.MOTOR_TEMP -> Res.string.secondary_gauge_motor_temp
        SecondaryGauge.ESC_TEMP -> Res.string.secondary_gauge_esc_temp
        SecondaryGauge.CONSUMPTION -> Res.string.secondary_gauge_consumption
    }
)
