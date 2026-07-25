package ru.sodovaya.volty.presentation.ride

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.sodovaya.volty.presentation.ride.gauge.ClusterLayout
import ru.sodovaya.volty.presentation.ride.gauge.DialGauge
import ru.sodovaya.volty.presentation.ride.gauge.rememberDialColors

/**
 * The Classic VESC dashboard: eight overlapping skeuomorphic dials, laid out by
 * [ClusterLayout] per `ClusterPlacement`'s slots and bound to live state via
 * [ClassicDialSpecs]. The alternative a vehicle can pick to the Clean renderer
 * (hero speedo + metric cluster + consumption card) in [RideDashboardScreen].
 *
 * Colour follows the same semantic mapping the Clean renderer uses — reuses
 * [severityColor] rather than a second definition, so a WARN duty dial turns
 * amber and a CRITICAL one red exactly as they do on the Clean side.
 */
@Composable
fun ClassicRideCluster(
    state: RideDashboardComponent.State,
    maxSpeedKmh: Float,
    modifier: Modifier = Modifier
) {
    val specs = ClassicDialSpecs.build(
        motion = state.motion,
        battery = state.battery,
        units = state.units,
        maxSpeedKmh = maxSpeedKmh
    )

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
                dangerFrom = spec.dangerFrom
            )
        }
    }
}
