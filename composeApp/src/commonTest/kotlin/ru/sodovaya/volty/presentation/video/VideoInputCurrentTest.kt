package ru.sodovaya.volty.presentation.video

import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.domain.video.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class VideoInputCurrentTest {
    private val now = 10_000L
    private val stamp = Instant.fromEpochMilliseconds(now)
    private val vehicle = Vehicle("test", "T4", iconKey = "wheel", packs = listOf(Pack(0, "BMS", BmsType.ANT_BMS, "AA")), chemistry = Chemistry.LI_ION_NMC, createdAt = stamp)
    private val motion = ControllerData(batteryCurrentA = 8f, powerW = 800f, isConnected = true, timestamp = stamp)
    private val bms = BmsData(current = -3f, isConnected = true, timestamp = stamp)
    private fun snapshot(data: VehicleData) = assertNotNull(videoTelemetrySnapshot(
        data, vehicle, ConnectionState.Connected(vehicle), 20_000, now,
    ))

    @Test fun automaticInputCurrentPrefersBmsAndLeavesExplicitMetricsUnchanged() {
        val result = snapshot(VehicleData(motion = motion, aggregate = bms))
        assertEquals(3.0, result.readings[VideoMetric.INPUT_CURRENT]?.value)
        assertEquals(-3.0, result.readings[VideoMetric.BATTERY_CURRENT]?.value)
        assertEquals(8.0, result.readings[VideoMetric.CONTROLLER_CURRENT]?.value)
        assertEquals(0.0, snapshot(VehicleData(motion = motion, aggregate = bms.copy(current = 0f))).readings[VideoMetric.INPUT_CURRENT]?.value)
    }

    @Test fun t4UsesControllerInsteadOfItsSyntheticBmsZeroAndExpiresWithTheController() {
        val result = snapshot(VehicleData(motion = motion.copy(timestamp = Instant.fromEpochMilliseconds(now - 500)),
            aggregate = bms.copy(current = 0f, currentSource = BmsCurrentSource.CONTROLLER)))
        assertEquals(8.0, result.readings[VideoMetric.INPUT_CURRENT]?.value)
        assertEquals(21_500L, result.expiresAtMillis[VideoMetric.INPUT_CURRENT])
    }

    @Test fun aFreshAggregateTimestampCannotHideAStaleBmsContributor() {
        val pack = Pack(0, "BMS", BmsType.ANT_BMS, "AA")
        val stale = PackState(pack, bms.copy(timestamp = Instant.fromEpochMilliseconds(now - 2_001)), isOnline = true)
        val result = snapshot(VehicleData(packs = listOf(stale), motion = motion, aggregate = bms))
        assertEquals(8.0, result.readings[VideoMetric.INPUT_CURRENT]?.value)
    }

    @Test fun stockIconDocumentsUpgradeButEditedMetricChoicesSurvive() {
        val current = OverlayTemplates.meowIcons()
        val old = current.copy(widgets = current.widgets.map { if (it.id == "current") it.copy(metric = VideoMetric.BATTERY_CURRENT) else it })
        assertEquals(VideoMetric.INPUT_CURRENT, current.widgets.first { it.id == "current" }.metric)
        assertEquals(current, OverlayCodec.decode(OverlayCodec.encode(OverlayDocument(selected = old))).selected)
        val edited = old.copy(widgets = old.widgets.map { if (it.id == "current") it.copy(label = "My BMS") else it })
        assertEquals(edited, OverlayCodec.decode(OverlayCodec.encode(OverlayDocument(selected = edited))).selected)
    }
}
