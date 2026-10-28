package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import ru.sodovaya.volty.domain.history.*
import java.util.Locale
import kotlin.test.*

class LoguiHistoryExportTest {
    private val noon = Instant.parse("2026-10-29T12:00:00Z").toEpochMilliseconds()
    private val origin = HistoryTick(noon, 10, 1)
    private fun tick(offset: Long) = HistoryTick(noon + offset, 10 + offset, 1)
    private val controller = HistorySource("ctl", HistorySourceKind.CONTROLLER, "EXN: ctl;1")
    private val pack = HistorySource("pack", HistorySourceKind.BATTERY, "EXN pack")
    private fun metadata(vararg sources: HistorySource = arrayOf(controller, pack), range: HistoryRange = HistoryRange(noon, noon + 60_000),
        started: HistoryTick = origin) = HistoryExportMetadata(
        HistoryRide("ride", "vehicle", started, started.copy(epochMillis = started.epochMillis + 60_000,
            elapsedMillis = started.elapsedMillis + 60_000), HistoryRideStatus.COMPLETED), range, sources.toList())
    private fun sample(offset: Long, source: HistorySource, metric: String, value: Double?, cell: Int? = null) =
        HistoryExportRecord.Numeric(HistorySample(tick(offset), HistorySeriesKey(source.id, metric, cell), value,
            if (value == null) HistoryUnknownReason.UNAVAILABLE else null))
    private fun fix(offset: Long, lat: Double, lon: Double, accuracy: Double = 4.5, heading: Double? = null) =
        HistoryExportRecord.Location(HistoryGpsFix(tick(offset), lat, lon, accuracy, heading))
    private suspend fun export(metadata: HistoryExportMetadata, vararg records: HistoryExportRecord,
        zone: TimeZone = TimeZone.UTC, grid: Long = 50): String {
        val text = StringBuilder()
        StreamingHistoryExportEncoder(zone = { zone }, loguiGridMillis = grid)
            .write(HistoryExportFormat.LOGUI, metadata, flowOf(*records)) { text.append(it) }
        return text.toString()
    }
    private fun lines(text: String) = text.split('\n').also { assertEquals("", it.last(), "file ends with a newline") }.dropLast(1)
    private fun table(text: String): List<Map<String, String>> {
        val all = lines(text); val keys = all.first().split(';').map { it.substringBefore(':') }
        return all.drop(1).map { row -> row.split(';').also { assertEquals(keys.size, it.size, row) }.let { keys.zip(it).toMap() } }
    }

    @Test fun headerAndRowsAreByteExactLoguiWithSecondsOfDayAndGnssColumns() = runTest {
        val text = export(metadata(),
            sample(0, controller, "input_voltage_v", 84.123),
            sample(0, controller, "motor_current_a", -6.960000038146973),
            sample(0, controller, "duty_percent", 42.0),
            sample(0, pack, "soc_percent", 80.56),
            fix(20, 56.86326621234, 60.62058444, 3.256, 259.2))
        assertEquals(listOf(
            "Input Voltage:Input Voltage:V:2:0:0;Current:Current:A:2:0:0;Duty:Duty::3:0:0;Batt:Battery:%:1:0:0;" +
                "t_day:Time:s:3:0:1;t_day_pos:Time GNSS:s:3:0:1;gnss_lat:Latitude:deg:7:0:0;gnss_lon:Longitude:deg:7:0:0;" +
                "gnss_h_acc:H. Accuracy GNSS:m:2:0:0;gnss_heading:Heading GNSS:deg:1:0:0",
            "84.12;-6.96;0.42;80.6;43200;43200.02;56.8632662;60.6205844;3.26;259.2",
        ), lines(text))
        assertFalse('\r' in text); assertFalse("EXN" in text, "labels never reach the file")
    }

    @Test fun aValueAppearsOnlyInTheRowWhereItWasSampledAndUnknownsAreEmptyNeverHeld() = runTest {
        // Deliberately incoherent: the voltage series goes unknown while current keeps sampling.
        // A forward-filling writer would repeat 84 in the second and third rows.
        val rows = table(export(metadata(controller),
            sample(0, controller, "input_voltage_v", 84.0), sample(0, controller, "motor_current_a", 1.0),
            sample(300, controller, "input_voltage_v", null), sample(300, controller, "motor_current_a", 2.0),
            sample(600, controller, "motor_current_a", 3.0),
            sample(900, controller, "input_voltage_v", 83.5)))
        assertEquals(listOf("84", "", "", "83.5"), rows.map { it.getValue("Input Voltage") })
        assertEquals(listOf("1", "2", "3", ""), rows.map { it.getValue("Current") })
        assertEquals(listOf("43200", "43200.3", "43200.6", "43200.9"), rows.map { it.getValue("t_day") })
    }

    @Test fun samplesInOneGridSlotMergeIntoOneRowAndARepeatedMetricKeepsTheLaterSample() = runTest {
        val records = arrayOf(
            sample(10, controller, "input_voltage_v", 84.0), sample(30, controller, "motor_current_a", 5.0),
            sample(40, controller, "input_voltage_v", 83.0), sample(60, controller, "motor_current_a", 6.0))
        val rows = table(export(metadata(controller), *records, grid = 50))
        assertEquals(listOf("83", ""), rows.map { it.getValue("Input Voltage") })
        assertEquals(listOf("5", "6"), rows.map { it.getValue("Current") })
        assertEquals(listOf("43200", "43200.05"), rows.map { it.getValue("t_day") })
        // A 1 ms grid keeps every tick as its own row.
        assertEquals(4, table(export(metadata(controller), *records, grid = 1)).size)
    }

    @Test fun samplesOutOfOrderWithinTheWindowAreMergedInTimeOrderAndOlderOnesAreKeptNotLost() = runTest {
        val rows = table(export(metadata(controller),
            sample(1000, controller, "motor_current_a", 2.0),
            sample(900, controller, "motor_current_a", 1.0),
            sample(4000, controller, "motor_current_a", 4.0),
            sample(1010, controller, "input_voltage_v", 80.0), // inside the 1000 ms slot, already flushed
            sample(4100, controller, "motor_current_a", 5.0)))
        // The late sample is written as soon as it arrives, in its own row, after the slots already flushed.
        assertEquals(listOf("43200.9", "43201", "43201", "43204", "43204.1"), rows.map { it.getValue("t_day") })
        assertEquals(listOf("1", "2", "", "4", "5"), rows.map { it.getValue("Current") })
        assertEquals(listOf("", "", "80", "", ""), rows.map { it.getValue("Input Voltage") })
    }

    @Test fun otherSourcesArePrefixedLikeLoguiCanFieldsAndTheFirstOfEachRoleKeepsBareKeys() = runTest {
        val second = HistorySource("ctl2", HistorySourceKind.CONTROLLER, "rear")
        val assembly = HistorySource("asm", HistorySourceKind.BATTERY_ASSEMBLY, "pack A")
        val silent = HistorySource("a-silent", HistorySourceKind.CONTROLLER, "no data")
        val can = HistorySource("can", HistorySourceKind.REPORTED_CAN, "can")
        val text = export(metadata(silent, controller, second, pack, assembly, can),
            sample(0, silent, "motor_current_a", null),
            sample(0, can, "battery_current_a", 7.0),
            sample(0, assembly, "voltage_v", 42.0),
            sample(0, second, "motor_current_a", 3.0),
            sample(0, pack, "voltage_v", 84.0),
            sample(0, controller, "motor_current_a", 9.0))
        assertEquals(listOf("Current", "bms_v_tot", "V3 Current", "A1 bms_v_tot", "CAN1 Current In", "t_day"),
            lines(text).first().split(';').map { it.substringBefore(':') })
        assertEquals("V3 Current:V3 Current:A:2:0:0", lines(text).first().split(';')[2])
        assertEquals("9;84;3;42;7;43200", lines(text)[1])
        assertEquals(mapOf("a-silent" to "V1 ", "ctl" to "", "ctl2" to "V3 ", "pack" to "", "asm" to "A1 ", "can" to "CAN1 "),
            loguiSourcePrefixes(listOf(silent, controller, second, pack, assembly, can), setOf("ctl", "ctl2", "pack", "asm", "can")))
    }

    @Test fun numbersUseTheColumnPrecisionAPointWhateverTheLocaleAndNothingForNonFinite() = runTest {
        assertEquals("-6.96", loguiNumber(-6.960000038146973, 2))
        assertEquals("65.2", loguiNumber(65.2, 2)); assertEquals("3", loguiNumber(3.0, 2))
        assertEquals("0", loguiNumber(-0.001, 2)); assertEquals("0.13", loguiNumber(0.125, 2))
        assertEquals("-0.13", loguiNumber(-0.125, 2)); assertEquals("1235", loguiNumber(1234.5, 0))
        assertEquals("56.8632662", loguiNumber(56.86326621234, 7)); assertEquals("0.0000001", loguiNumber(1e-7, 7))
        assertEquals("12345678901", loguiNumber(12345678901.0, 2))
        assertNull(loguiNumber(Double.NaN, 2)); assertNull(loguiNumber(Double.POSITIVE_INFINITY, 1))
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("84.12", loguiNumber(84.123, 2))
            val text = export(metadata(controller), sample(0, controller, "input_voltage_v", 84.123),
                sample(600, controller, "input_voltage_v", 1.5))
            assertEquals(listOf("84.12;43200", "1.5;43200.6"), lines(text).drop(1))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun aSeriesWithNoFiniteValueInTheRangeGetsNoColumnAndAnEmptySlotGetsNoRow() = runTest {
        val text = export(metadata(controller, pack),
            // HistorySample cannot hold NaN or infinity, so an unknown is the only way a series stays empty.
            sample(0, controller, "motor_temperature_c", null), sample(100, controller, "motor_temperature_c", null),
            sample(0, pack, "current_a", null), sample(100, pack, "power_w", null),
            sample(200, controller, "esc_temperature_c", 41.26))
        assertEquals(listOf("Temp Fet:Temp Fet:degC:1:0:0;t_day:Time:s:3:0:1", "41.3;43200.2"), lines(text))
    }

    @Test fun estimatedDutyHasItsOwnColumnAndNeverFillsDuty() = runTest {
        val rows = table(export(metadata(controller),
            sample(0, controller, "duty_estimated_percent", 87.25), sample(0, controller, "duty_percent", null),
            sample(100, controller, "duty_percent", 50.0)))
        assertEquals(listOf("Duty", "duty_estimated", "t_day"), rows.first().keys.toList())
        assertEquals(listOf("87.3", ""), rows.map { it.getValue("duty_estimated") })
        assertEquals(listOf("", "0.5"), rows.map { it.getValue("Duty") })
        assertEquals(LoguiField("duty_estimated", "Duty Estimated", "%", 1), loguiField("duty_estimated_percent", null))
    }

    @Test fun timeOfDayIsLocalAndWrapsAtMidnightTheWayVescToolUnwrapsIt() = runTest {
        val moscow = TimeZone.of("Europe/Moscow") // UTC+3, no DST
        val beforeMidnight = Instant.parse("2026-10-29T20:59:59.900Z").toEpochMilliseconds()
        val started = HistoryTick(beforeMidnight, 10, 1)
        fun at(offset: Long, value: Double) = HistoryExportRecord.Numeric(HistorySample(
            HistoryTick(beforeMidnight + offset, 10 + offset, 1), HistorySeriesKey("ctl", "motor_current_a"), value))
        val rows = table(export(metadata(controller, range = HistoryRange(beforeMidnight, beforeMidnight + 60_000), started = started),
            at(0, 1.0), at(50, 2.0), at(150, 3.0), at(2100, 4.0), zone = moscow))
        assertEquals(listOf("86399.9", "86399.95", "0.05", "2"), rows.map { it.getValue("t_day") })
        // VESC Tool: time = t - start; if (time < 0) time += 86400 (pageloganalysis.cpp:851-855).
        val axis = rows.map { it.getValue("t_day").toDouble() - 86399.9 }.map { if (it < 0) it + 86400 else it }
        assertEquals(listOf(0.0, 0.05, 0.15, 2.1), axis.map { (it * 1000).let(Math::round) / 1000.0 })
        assertEquals(41_000L, loguiMillisOfDay(Instant.parse("2026-10-29T21:00:41Z").toEpochMilliseconds(), moscow))
    }

    @Test fun fixesShareRowsWithSamplesInTheirSlotOrTakeTheirOwnAndOnlyFixRowsCarryGnss() = runTest {
        val rows = table(export(metadata(controller),
            sample(0, controller, "speed_kmh", 25.0), sample(1000, controller, "speed_kmh", 26.0),
            fix(1020, 56.1, 60.2, 0.0), fix(1500, 56.2, 60.3, 12.0)))
        assertEquals(listOf("kmh_vesc", "t_day", "t_day_pos", "gnss_lat", "gnss_lon", "gnss_h_acc"), rows.first().keys.toList())
        assertEquals(listOf("25", "26", ""), rows.map { it.getValue("kmh_vesc") })
        assertEquals(listOf("", "56.1", "56.2"), rows.map { it.getValue("gnss_lat") })
        assertEquals(listOf("", "43201.02", "43201.5"), rows.map { it.getValue("t_day_pos") })
        assertEquals(listOf("", "0", "12"), rows.map { it.getValue("gnss_h_acc") })
        val onlyRoute = lines(export(metadata(controller), fix(0, 1.0, 2.0)))
        assertEquals("t_day:Time:s:3:0:1;t_day_pos:Time GNSS:s:3:0:1;gnss_lat:Latitude:deg:7:0:0;gnss_lon:Longitude:deg:7:0:0;" +
            "gnss_h_acc:H. Accuracy GNSS:m:2:0:0", onlyRoute.first())
        assertEquals("43200;43200;1;2;4.5", onlyRoute[1])
    }

    @Test fun unitsVescToolExpectsAreConvertedAndEveryRegistryMetricHasASafeKey() = runTest {
        val rows = table(export(metadata(controller),
            sample(0, controller, "trip_distance_km", 12.3456), sample(0, controller, "duty_percent", -12.34),
            sample(0, controller, "erpm", 2491.6), sample(0, controller, "odometer_km", 1234.5678)))
        assertEquals("12345.6", rows[0].getValue("trip_vesc")); assertEquals("-0.123", rows[0].getValue("Duty"))
        assertEquals("2492", rows[0].getValue("RPM")); assertEquals("1234.568", rows[0].getValue("odometer"))
        for (descriptor in HistoryMetricRegistry.descriptors) {
            val field = loguiField(descriptor.id, if (descriptor.indexedByCell) 0 else null)
            LoguiColumn("B1 " + field.key, field.name, field.unit, field.precision) // rejects ':' ';' and line breaks
            if (field.scale == 1.0 && descriptor.unit != HistoryUnit.ERPM) assertEquals(loguiUnit(descriptor.unit), field.unit, descriptor.id)
        }
        val keys = HistoryMetricRegistry.descriptors.map { loguiField(it.id, if (it.indexedByCell) 0 else null).key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("BMS_C12", loguiField("cell_v", 11).key)
    }

    @Test fun outOfRangeOrUnresolvedSamplesAndActiveRidesAreRefused() = runTest {
        for ((records, reason) in listOf(
            arrayOf<HistoryExportRecord>(sample(70_000, controller, "motor_current_a", 1.0)) to HistoryExportFailure.INVALID_DATA,
            arrayOf<HistoryExportRecord>(sample(0, pack, "motor_current_a", 1.0)) to HistoryExportFailure.INVALID_DATA,
            arrayOf<HistoryExportRecord>(sample(0, HistorySource("ghost", HistorySourceKind.CONTROLLER, "x"), "erpm", 1.0)) to HistoryExportFailure.INVALID_DATA)) {
            assertEquals(reason, assertFailsWith<HistoryExportException> { export(metadata(), *records) }.reason)
        }
        val active = metadata().let { it.copy(ride = it.ride.copy(status = HistoryRideStatus.RECORDING, ended = null)) }
        assertEquals(HistoryExportFailure.ACTIVE_RECORDING, assertFailsWith<HistoryExportException> { export(active) }.reason)
    }

    @Test fun theInputIsReadTwiceAndASinkCancellationStopsTheSecondPass() = runTest {
        var collections = 0; var lastEmitted = -1
        val records = flow {
            collections++
            for (index in 0 until 12_000) { lastEmitted = index; emit(sample(index * 5L, controller, "motor_current_a", index.toDouble())) }
        }
        var writes = 0
        assertFailsWith<CancellationException> {
            StreamingHistoryExportEncoder(zone = { TimeZone.UTC }, loguiGridMillis = 1).write(HistoryExportFormat.LOGUI, metadata(controller), records) {
                writes++; if (writes == 2) throw CancellationException("explicit export cancel")
            }
        }
        assertEquals(2, collections); assertTrue(lastEmitted < 11_999, "the second pass stopped at the cancelled write")
    }

    @Test fun fileNamesCarryTheVehicleAndLocalStartAndNeverAnAddressOrPath() {
        val moscow = TimeZone.of("Europe/Moscow")
        val start = Instant.parse("2026-10-29T21:05:07Z").toEpochMilliseconds()
        assertEquals("volty_GotWay_59672_2026-10-30_00-05-07.csv",
            historyExportFileName(HistoryExportFormat.LOGUI, "GotWay_59672", start, moscow))
        assertEquals("volty_Мой-самокат_2026-10-30_00-05-07_records.csv",
            historyExportFileName(HistoryExportFormat.CSV, "Мой самокат!!", start, moscow))
        assertEquals("volty_BMS-device_2026-10-29_21-05-07.gpx",
            historyExportFileName(HistoryExportFormat.GPX, "BMS AA:BB:CC:DD:EE:FF", start, TimeZone.UTC))
        assertEquals("volty_path_2026-10-29_21-05-07.csv", historyExportFileName(HistoryExportFormat.LOGUI, "/data/user/0/x", start, TimeZone.UTC))
        assertEquals("volty_ride_2026-10-29_21-05-07.csv", historyExportFileName(HistoryExportFormat.LOGUI, null, start, TimeZone.UTC))
        assertEquals("pack label", historyExportVehicleName(metadata(HistorySource("p", HistorySourceKind.BATTERY, "pack label"))))
        assertEquals("EXN", historyExportVehicleName(metadata().copy(vehicleName = "EXN")))
    }
}
