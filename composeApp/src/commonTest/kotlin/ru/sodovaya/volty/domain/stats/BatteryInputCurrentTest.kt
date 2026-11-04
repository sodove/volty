package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class BatteryInputCurrentTest {
    private val now = 10_000L
    private fun battery(current: Float = -4f, source: BmsCurrentSource = BmsCurrentSource.BMS) =
        BmsData(current = current, currentSource = source, isConnected = true, timestamp = Instant.fromEpochMilliseconds(now))
    private fun motion(current: Float = 8f) =
        ControllerData(batteryCurrentA = current, isConnected = true, timestamp = Instant.fromEpochMilliseconds(now))

    @Test fun realBmsWinsEvenWhenItReportsZeroAndItsSignIsNormalized() {
        assertEquals(4f, batteryInputCurrent(motion(), battery(), now)?.dischargePositiveAmps)
        assertEquals(0f, batteryInputCurrent(motion(), battery(0f), now)?.dischargePositiveAmps)
        assertEquals(-3f, batteryInputCurrent(motion(), battery(3f), now)?.dischargePositiveAmps)
    }

    @Test fun controllerMirrorCannotOverrideActualControllerSample() {
        assertEquals(8f, batteryInputCurrent(motion(), battery(0f, BmsCurrentSource.CONTROLLER), now)?.dischargePositiveAmps)
        assertEquals(4f, batteryInputCurrent(motion().copy(hasBatteryCurrent = false), battery(-4f, BmsCurrentSource.CONTROLLER), now)?.dischargePositiveAmps)
    }

    @Test fun unknownStaleDisconnectedAndNonfiniteSourcesCannotWin() {
        val good = motion()
        listOf(
            battery().copy(hasCurrent = false, current = -999f),
            battery().copy(timestamp = Instant.fromEpochMilliseconds(now - 2_001)),
            battery().copy(timestamp = Instant.fromEpochMilliseconds(now + 1)),
            battery().copy(isConnected = false),
            battery(Float.NaN),
        ).forEach { assertEquals(8f, batteryInputCurrent(good, it, now)?.dischargePositiveAmps) }
        assertNull(batteryInputCurrent(good.copy(hasBatteryCurrent = false), battery().copy(hasCurrent = false), now))
        assertNull(batteryInputCurrent(good.copy(batteryCurrentA = Float.POSITIVE_INFINITY), battery().copy(hasCurrent = false), now))
    }

    @Test fun aggregationCannotTurnControllerOrMixedContributorsIntoSmartBmsEvidence() {
        fun pack(index: Int, data: BmsData) = PackState(Pack(index, "p", BmsType.ANT_BMS, "address$index"), data, isOnline = true)
        val real = pack(0, battery())
        val mirror = pack(1, battery(source = BmsCurrentSource.CONTROLLER))
        assertEquals(BmsCurrentSource.BMS, PackAggregator.aggregate(listOf(real), PackTopology.PARALLEL).currentSource)
        assertEquals(BmsCurrentSource.CONTROLLER, PackAggregator.aggregate(listOf(mirror), PackTopology.PARALLEL).currentSource)
        assertEquals(BmsCurrentSource.MIXED, PackAggregator.aggregate(listOf(real, mirror), PackTopology.PARALLEL).currentSource)
    }
}
