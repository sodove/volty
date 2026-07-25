package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.presentation.ride.gauge.ClusterSlot

/**
 * Spec B §7.2: the rider's "Inner gauge" choice on Vehicle Edit drives the hero's inner ring in
 * Clean; "in Classic it emphasises the chosen dial." [ClassicRideCluster] never read
 * [RideDashboardComponent.State.secondary] at all, so the picker was a silent no-op for a Classic
 * vehicle — this is the mapping that fixes that, pulled out as its own pure function (rather than
 * inlined where [ClassicRideCluster] renders) so the actual contract — which dial lights up for
 * which choice — is unit tested without a Compose harness.
 *
 * Each [SecondaryGauge] maps onto the one [ClusterSlot] that already shows that exact metric,
 * i.e. this is the same data [SecondaryGaugeMapper] surfaces for Clean's inner ring, just reread
 * off the Classic cluster's own slot for it — never a second, Classic-only definition of what
 * "duty" or "battery" means.
 */
object ClassicEmphasis {
    fun slotFor(gauge: SecondaryGauge): ClusterSlot = when (gauge) {
        SecondaryGauge.DUTY -> ClusterSlot.TOP_RIGHT
        SecondaryGauge.BATTERY -> ClusterSlot.HERO_INSET
        SecondaryGauge.POWER -> ClusterSlot.TOP_CENTRE
        SecondaryGauge.CURRENT -> ClusterSlot.TOP_LEFT
        SecondaryGauge.MOTOR_TEMP -> ClusterSlot.BOTTOM_RIGHT
        SecondaryGauge.ESC_TEMP -> ClusterSlot.BOTTOM_LEFT
        SecondaryGauge.CONSUMPTION -> ClusterSlot.BOTTOM_CENTRE
    }
}
