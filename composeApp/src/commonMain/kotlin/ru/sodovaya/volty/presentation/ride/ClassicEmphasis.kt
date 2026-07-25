package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot

/**
 * Spec B §7.2: the rider's "Inner gauge" choice on Vehicle Edit drives the hero's inner ring in
 * Clean; "in Classic it emphasises the chosen dial." Pulled out as its own pure function (rather
 * than inlined where [ClassicRideCluster] renders) so the actual contract — which dial is picked
 * out for which choice — is unit tested without a Compose harness.
 *
 * Each [SecondaryGauge] maps onto the one [VescClusterSlot] that already shows that exact metric,
 * i.e. this is the same data [SecondaryGaugeMapper] surfaces for Clean's inner ring, just reread
 * off the Classic cluster's own slot for it — never a second, Classic-only definition of what
 * "duty" or "battery" means.
 *
 * How the emphasis is DRAWN is deliberately not decided here, but it is constrained: it must not
 * be a colour. In this renderer the needle's colour is the severity signal (see
 * [ClassicDialSpecs]), so colouring the chosen dial would make a rider's display preference look
 * like a warning. [ru.sodovaya.volty.presentation.ride.gauge.VescDialMetrics.EMPHASIS_BEZEL_MULTIPLIER]
 * is the cue instead: a heavier bezel, which is pure geometry.
 */
object ClassicEmphasis {
    fun slotFor(gauge: SecondaryGauge): VescClusterSlot = when (gauge) {
        SecondaryGauge.DUTY -> VescClusterSlot.DUTY
        SecondaryGauge.BATTERY -> VescClusterSlot.BATTERY
        SecondaryGauge.POWER -> VescClusterSlot.POWER
        SecondaryGauge.CURRENT -> VescClusterSlot.CURRENT
        SecondaryGauge.MOTOR_TEMP -> VescClusterSlot.MOTOR_TEMP
        SecondaryGauge.ESC_TEMP -> VescClusterSlot.ESC_TEMP
        SecondaryGauge.CONSUMPTION -> VescClusterSlot.CONSUMPTION
    }
}
