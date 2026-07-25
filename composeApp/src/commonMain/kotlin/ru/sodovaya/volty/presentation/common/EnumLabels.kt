package ru.sodovaya.volty.presentation.common

import androidx.compose.runtime.Composable
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.bmsTypeOrNull
import ru.sodovaya.volty.domain.model.primaryController
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
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
import volty.composeapp.generated.resources.secondary_gauge_battery
import volty.composeapp.generated.resources.secondary_gauge_consumption
import volty.composeapp.generated.resources.secondary_gauge_current
import volty.composeapp.generated.resources.secondary_gauge_duty
import volty.composeapp.generated.resources.secondary_gauge_esc_temp
import volty.composeapp.generated.resources.secondary_gauge_motor_temp
import volty.composeapp.generated.resources.secondary_gauge_power

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
 * The one label that names what a vehicle's telemetry comes from: the motor
 * controller (VESC / FarDriver / …) when it has one, else its BMS.
 *
 * Null when neither is available — no vehicle at all, or (not reachable today)
 * a vehicle with no sources. Callers must then OMIT the segment rather than
 * render "null", an empty chip, or a dash standing in for a real label; the
 * two status lines that need a non-null argument for a format string supply
 * their own em-dash placeholder at the call site.
 *
 * Single source of truth on purpose: this fallback used to be copy-pasted, and
 * divergent copies of exactly this kind produced the Clean/Classic parity bugs.
 */
@Composable
fun vehicleSourceLabel(vehicle: Vehicle?): String? =
    vehicle?.primaryController?.controllerType?.label
        ?: vehicle?.bmsTypeOrNull?.let { bmsTypeLabel(it) }

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
