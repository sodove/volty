package ru.sodovaya.volty.presentation.ride

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.sodovaya.volty.presentation.ride.gauge.ClusterLayout
import ru.sodovaya.volty.presentation.ride.gauge.DialGauge
import ru.sodovaya.volty.presentation.ride.gauge.rememberDialColors
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.ride_battery
import volty.composeapp.generated.resources.ride_consumption
import volty.composeapp.generated.resources.ride_dial_speed
import volty.composeapp.generated.resources.ride_esc_temp
import volty.composeapp.generated.resources.ride_motor_temp
import volty.composeapp.generated.resources.ride_power
import volty.composeapp.generated.resources.secondary_gauge_current
import volty.composeapp.generated.resources.secondary_gauge_duty

/**
 * The Classic VESC dashboard: eight overlapping skeuomorphic dials, laid out by
 * [ClusterLayout] per `ClusterPlacement`'s slots and bound to live state via
 * [ClassicDialSpecs]. The alternative a vehicle can pick to the Clean renderer
 * (hero speedo + metric cluster + consumption card) in [RideDashboardScreen].
 *
 * Colour follows the same semantic mapping the Clean renderer uses — reuses
 * [severityColor] rather than a second definition, so a WARN duty dial turns
 * amber and a CRITICAL one red exactly as they do on the Clean side.
 *
 * Labels are resolved here, not inside [ClassicDialSpecs], which stays Compose-free: this reuses
 * the exact same string keys Clean's own labels resolve from (`ride_power`, `ride_battery`,
 * `secondary_gauge_duty`, …), uppercased to keep the skeuomorphic all-caps face Classic already
 * shipped with, so a Russian rider reads Russian dial faces on both renderers for the same data.
 */
@Composable
fun ClassicRideCluster(
    state: RideDashboardComponent.State,
    maxSpeedKmh: Float,
    modifier: Modifier = Modifier
) {
    val labels = ClassicDialLabels(
        current = stringResource(Res.string.secondary_gauge_current).uppercase(),
        power = stringResource(Res.string.ride_power).uppercase(),
        duty = stringResource(Res.string.secondary_gauge_duty).uppercase(),
        speed = stringResource(Res.string.ride_dial_speed).uppercase(),
        battery = stringResource(Res.string.ride_battery).uppercase(),
        esc = stringResource(Res.string.ride_esc_temp).uppercase(),
        consumption = stringResource(Res.string.ride_consumption).uppercase(),
        motor = stringResource(Res.string.ride_motor_temp).uppercase()
    )

    val specs = ClassicDialSpecs.build(
        motion = state.motion,
        battery = state.battery,
        units = state.units,
        maxSpeedKmh = maxSpeedKmh,
        labels = labels
    )

    // Spec §7.2: the "Inner gauge" picker emphasises a dial in Classic rather than driving an
    // inner ring the way it does in Clean — see ClassicEmphasis for the mapping.
    val emphasizedSlot = ClassicEmphasis.slotFor(state.secondary)

    ClusterLayout(modifier = modifier) {
        specs.forEach { spec ->
            DialGauge(
                value = spec.value,
                scale = spec.scale,
                label = spec.label,
                unit = spec.unit,
                valueText = spec.valueText,
                modifier = Modifier.slot(spec.slot),
                colors = rememberDialColors(accent = severityColor(spec.severity)),
                dangerFrom = spec.dangerFrom,
                emphasized = spec.slot == emphasizedSlot
            )
        }
    }
}
