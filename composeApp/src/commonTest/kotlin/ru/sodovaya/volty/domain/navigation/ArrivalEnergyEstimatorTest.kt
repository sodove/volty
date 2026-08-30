package ru.sodovaya.volty.domain.navigation

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.stats.PackAggregator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ArrivalEnergyEstimatorTest {
    private val sampleTime = Instant.fromEpochSeconds(1_700_000_000L)
    private val nowMillis = sampleTime.toEpochMilliseconds() + 1_000L

    @Test
    fun missing_route_wins_before_any_telemetry_claim() {
        val result = ArrivalEnergyEstimator.estimate(knownEvidence(), null, nowMillis)

        assertEquals(ArrivalSocUnknownReason.NO_ROUTE, assertIs<ArrivalSocEstimate.Unknown>(result).reason)
    }

    @Test
    fun partial_battery_is_not_masked_by_a_plausible_aggregate() {
        val packs = listOf(
            pack(0, online = true, soc = 80f, capacity = 20f),
            pack(1, online = false, soc = 80f, capacity = 20f),
        )
        val evidence = knownEvidence(vehicleData = PackAggregator.build(packs, PackTopology.PARALLEL))

        assertEquals(
            ArrivalSocUnknownReason.PACKS_PARTIAL,
            assertIs<ArrivalSocEstimate.Unknown>(ArrivalEnergyEstimator.estimate(evidence, 1_000.0, nowMillis)).reason,
        )
    }

    @Test
    fun disconnected_battery_has_no_arrival_soc() {
        val evidence = NavigationEnergyEvidence(
            vehicleData = VehicleData(),
            motion = ControllerData(powerW = 4_200f, hasPower = false),
            consumption = earnedConsumption(),
        )

        assertEquals(
            ArrivalSocUnknownReason.BMS_DISCONNECTED,
            assertIs<ArrivalSocEstimate.Unknown>(ArrivalEnergyEstimator.estimate(evidence, 1_000.0, nowMillis)).reason,
        )
    }

    @Test
    fun soc_unknown_is_not_recovered_from_the_numeric_placeholder() {
        val evidence = knownEvidence(
            vehicleData = PackAggregator.build(
                listOf(pack(0, online = true, soc = 80f, capacity = 20f, socKnown = false)),
                PackTopology.PARALLEL,
            ),
        )

        assertEquals(
            ArrivalSocUnknownReason.SOC_UNEARNED,
            assertIs<ArrivalSocEstimate.Unknown>(ArrivalEnergyEstimator.estimate(evidence, 1_000.0, nowMillis)).reason,
        )
    }

    @Test
    fun every_capacity_contributor_must_be_positive() {
        val evidence = knownEvidence(
            vehicleData = PackAggregator.build(
                listOf(pack(0, online = true, soc = 80f, capacity = 0f)),
                PackTopology.PARALLEL,
            ),
        )

        assertEquals(
            ArrivalSocUnknownReason.CAPACITY_UNEARNED,
            assertIs<ArrivalSocEstimate.Unknown>(ArrivalEnergyEstimator.estimate(evidence, 1_000.0, nowMillis)).reason,
        )
    }

    @Test
    fun stale_bms_and_nan_are_rejected_without_a_numeric_fallback() {
        val stale = knownEvidence(
            vehicleData = PackAggregator.build(
                listOf(pack(0, online = true, soc = 80f, capacity = 20f, timestamp = sampleTime)),
                PackTopology.PARALLEL,
            ),
        )
        val staleResult = ArrivalEnergyEstimator.estimate(
            stale,
            1_000.0,
            sampleTime.toEpochMilliseconds() + 5_001L,
        )
        assertEquals(
            ArrivalSocUnknownReason.TELEMETRY_STALE,
            assertIs<ArrivalSocEstimate.Unknown>(staleResult).reason,
        )

        val nan = knownEvidence(
            vehicleData = PackAggregator.build(
                listOf(pack(0, online = true, soc = Float.NaN, capacity = 20f)),
                PackTopology.PARALLEL,
            ),
        )
        assertEquals(
            ArrivalSocUnknownReason.SOC_UNEARNED,
            assertIs<ArrivalSocEstimate.Unknown>(ArrivalEnergyEstimator.estimate(nan, 1_000.0, nowMillis)).reason,
        )
    }

    @Test
    fun consumption_needs_positive_earned_evidence_and_all_minimums() {
        val route = 1_000.0
        val thresholds = listOf(
            earnedConsumption(distanceKm = 1.99) to ArrivalSocUnknownReason.CONSUMPTION_UNEARNED,
            earnedConsumption(durationMillis = 299_999L) to ArrivalSocUnknownReason.CONSUMPTION_UNEARNED,
            earnedConsumption(measuredSampleCount = 19) to ArrivalSocUnknownReason.CONSUMPTION_UNEARNED,
            null to ArrivalSocUnknownReason.CONSUMPTION_UNEARNED,
            earnedConsumption(whPerKm = 0.0) to ArrivalSocUnknownReason.CONSUMPTION_UNEARNED,
            earnedConsumption(whPerKm = -1.0) to ArrivalSocUnknownReason.CONSUMPTION_UNEARNED,
        )

        thresholds.forEach { (consumption, reason) ->
            val evidence = knownEvidence(consumption = consumption)
            assertEquals(
                reason,
                assertIs<ArrivalSocEstimate.Unknown>(ArrivalEnergyEstimator.estimate(evidence, route, nowMillis)).reason,
            )
        }
    }

    @Test
    fun parallel_and_series_use_topology_aware_capacity_times_voltage() {
        val parallel = knownEvidence(
            vehicleData = PackAggregator.build(
                listOf(
                    pack(0, online = true, soc = 80f, capacity = 20f, voltage = 50f),
                    pack(1, online = true, soc = 80f, capacity = 20f, voltage = 50f),
                ),
                PackTopology.PARALLEL,
            ),
        )
        val series = knownEvidence(
            vehicleData = PackAggregator.build(
                listOf(
                    pack(0, online = true, soc = 80f, capacity = 20f, voltage = 25f),
                    pack(1, online = true, soc = 80f, capacity = 20f, voltage = 25f),
                ),
                PackTopology.SERIES,
            ),
        )

        val parallelResult = assertIs<ArrivalSocEstimate.Known>(
            ArrivalEnergyEstimator.estimate(parallel, 10_000.0, nowMillis),
        )
        val seriesResult = assertIs<ArrivalSocEstimate.Known>(
            ArrivalEnergyEstimator.estimate(series, 10_000.0, nowMillis),
        )

        assertEquals(68, parallelResult.percent)
        assertEquals(55, seriesResult.percent)
        assertTrue(parallelResult.approximate)
        assertTrue(seriesResult.approximate)
    }

    @Test
    fun known_estimate_clamps_only_after_all_gates_pass() {
        val result = ArrivalEnergyEstimator.estimate(
            knownEvidence(consumption = earnedConsumption(whPerKm = 10_000.0)),
            10_000.0,
            nowMillis,
        )

        assertEquals(0, assertIs<ArrivalSocEstimate.Known>(result).percent)
    }

    private fun knownEvidence(
        vehicleData: VehicleData = PackAggregator.build(
            listOf(pack(0, online = true, soc = 80f, capacity = 20f)),
            PackTopology.PARALLEL,
        ),
        consumption: ConsumptionEvidence? = earnedConsumption(),
    ) = NavigationEnergyEvidence(
        vehicleData = vehicleData,
        motion = ControllerData(isConnected = true),
        consumption = consumption,
    )

    private fun earnedConsumption(
        whPerKm: Double = 25.0,
        distanceKm: Double = 10.0,
        durationMillis: Long = 300_000L,
        measuredSampleCount: Int = 20,
    ) = ConsumptionEvidence(
        whPerKm = whPerKm,
        distanceKm = distanceKm,
        durationMillis = durationMillis,
        measuredSampleCount = measuredSampleCount,
        provenance = ConsumptionProvenance.CONTROLLER_COUNTERS,
    )

    private fun pack(
        index: Int,
        online: Boolean,
        soc: Float,
        capacity: Float,
        voltage: Float = 50f,
        socKnown: Boolean = true,
        timestamp: Instant = sampleTime,
    ) = PackState(
        pack = Pack(index, "P$index", BmsType.JK_BMS, "AA:0$index"),
        data = BmsData(
            voltage = voltage,
            soc = soc,
            socKnown = socKnown,
            capacity = capacity,
            isConnected = online,
            timestamp = timestamp,
        ),
        isOnline = online,
    )
}
