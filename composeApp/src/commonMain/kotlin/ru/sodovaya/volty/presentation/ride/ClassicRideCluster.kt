package ru.sodovaya.volty.presentation.ride

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterLayout
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot
import ru.sodovaya.volty.presentation.ride.gauge.VescDialGauge
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
 * The Classic dashboard: a faithful port of VESC Tool's RT-Data screen — eight overlapping dials
 * laid out by [VescClusterLayout] per `mobile/RtDataSetup.qml`, each drawn by [VescDialGauge] (the
 * port of `mobile/CustomGauge.qml`) and bound to live state by [ClassicDialSpecs]. The alternative
 * a vehicle can pick to the Clean renderer in [RideDashboardScreen].
 *
 * This file owns exactly three things; everything else is delegated to something already tested:
 *
 *  1. **Label resolution.** [ClassicDialSpecs] stays Compose-free, so the localized captions are
 *     resolved here, from the exact same string keys Clean's own labels use (`ride_power`,
 *     `secondary_gauge_duty`, …) — so a Russian rider reads Russian dial faces on both styles.
 *  2. **Severity -> colour, animated.** `RtDataSetup.qml` puts a `Behavior on nibColor`
 *     (`ColorAnimation`, `Easing.InOutSine`) on every gauge whose nib can change colour (:362-368,
 *     :450-456, :480-486, :528-533), so a needle fades between states instead of snapping. The
 *     mapping itself is [severityColor] — the Clean renderer's, not a second definition.
 *  3. **The Battery overlay.** QML :312 hides that dial's own centre text and :317-361 draws its
 *     own content there instead.
 *
 * Emphasis (spec §7.2's Classic "Inner gauge") is NOT one of them: it is geometry, so it belongs
 * to [VescClusterLayout] and is passed straight through.
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
    // inner ring the way it does in Clean. The cue is SIZE plus front-most paint order, never
    // colour — see ClassicEmphasis and VescClusterGeometry.EMPHASIS_SIZE_FACTOR. Both halves live
    // in the layout, which is why nothing about emphasis is passed to a dial here.
    val emphasizedSlot = ClassicEmphasis.slotFor(state.secondary)

    VescClusterLayout(modifier = modifier, emphasized = emphasizedSlot) {
        specs.forEach { spec ->
            // Keyed so each dial keeps its own animation state across recompositions; without it
            // the animateColorAsState calls below would be positional and could swap dials.
            key(spec.slot) {
                val nibColor by animateColorAsState(
                    targetValue = severityColor(spec.severity),
                    animationSpec = tween(durationMillis = NIB_COLOR_ANIMATION_MS, easing = InOutSine),
                    label = "vescNibColor"
                )
                val dial = @Composable { dialModifier: Modifier ->
                    VescDialGauge(
                        value = spec.value,
                        range = spec.range,
                        typeText = spec.caption,
                        unitText = spec.unit,
                        nibColor = nibColor,
                        modifier = dialModifier,
                        precision = spec.precision,
                        centerTextVisible = spec.centerTextVisible,
                        tickmarkScale = spec.tickmarkScale,
                        tickmarkSuffix = spec.tickmarkSuffix,
                        valueTextOverride = spec.valueTextOverride
                    )
                }

                if (spec.slot == VescClusterSlot.BATTERY) {
                    BatteryDial(spec = spec, modifier = Modifier.clusterSlot(spec.slot), dial = dial)
                } else {
                    dial(Modifier.clusterSlot(spec.slot))
                }
            }
        }
    }
}

/**
 * `RtDataSetup.qml`'s `Behavior on nibColor` duration (:364, :452, :482). The `efficiencyGauge`'s
 * own copy of the same block uses 100 ms instead (:530); this uses 1000 ms for all four, both
 * because it is what three of the four say and because 100 ms is not a transition — it is a flick
 * between two colours, which is the thing the animation exists to avoid.
 */
private const val NIB_COLOR_ANIMATION_MS = 1000

/** `Easing.InOutSine` (QML :365, :453, :483, :531) as its standard cubic-bezier control points. */
private val InOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/**
 * The Battery dial and the content that replaces its hidden centre stack — QML :312 turns
 * `centerTextVisible` off and :317-361 anchors four `Text` items over the dial in its place.
 *
 * Two of those four are not ported: `rangeLabel`/`rangeValLabel` ("∞ KM RANGE") need the pack's
 * remaining watt-hours divided by a session average, which this screen's state does not carry.
 * What is kept is VESC's own type scale and offsets for the two that ARE available — the caption
 * at `gaugeSize2/18` and `-0.12` of the dial above centre (:321, :324), and the percentage, which
 * is promoted from VESC's small `battValLabel` (:354) into the big central `gaugeSize2/6.3` slot
 * (:332) the range number would have occupied, since it is now the only number on this dial.
 *
 * Every size here is a fraction of the dial's own measured width, like the rest of the gauge path
 * — no `dp`, no `sp`.
 */
@Composable
private fun BatteryDial(
    spec: VescDialSpec,
    modifier: Modifier,
    dial: @Composable (Modifier) -> Unit
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val size = maxWidth
        dial(Modifier.fillMaxSize())

        Text(
            text = spec.caption,
            fontSize = size.fractionSp(CAPTION_FONT_FRACTION),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.offset(y = size * CAPTION_OFFSET_FRACTION)
        )
        Text(
            text = spec.valueTextOverride.orEmpty(),
            fontSize = size.fractionSp(VALUE_FONT_FRACTION),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.offset(y = size * VALUE_OFFSET_FRACTION)
        )
    }
}

/** QML :321 — `font.pixelSize: gaugeSize2/18.0`. */
private const val CAPTION_FONT_FRACTION = 1f / 18f

/** QML :324 — `anchors.verticalCenterOffset: -gaugeSize2*0.12`. */
private const val CAPTION_OFFSET_FRACTION = -0.12f

/** QML :332 — `font.pixelSize: gaugeSize2/6.3` (the big, central number slot). */
private const val VALUE_FONT_FRACTION = 1f / 6.3f

/** QML :333 — `anchors.verticalCenterOffset: -0.015*gaugeSize2`. */
private const val VALUE_OFFSET_FRACTION = -0.015f

/**
 * A fraction of the dial's own width as a font size, bypassing the system font-scale multiplier
 * for the same reason [VescDialGauge] does: these glyphs are part of an instrument sized to its
 * own geometry, not body text that should grow with an accessibility setting. `toSp()` divides by
 * `density * fontScale` and the text renderer multiplies both back at draw time, so the two cancel.
 */
@Composable
private fun Dp.fractionSp(fraction: Float): TextUnit =
    with(LocalDensity.current) { (this@fractionSp.toPx() * fraction).coerceAtLeast(0f).toSp() }
