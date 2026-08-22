package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.presentation.common.groupPackCells
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests against a REAL capture from a Begode ET Max (see [BegodeDumpFixture]).
 * Ground truth at capture time: 40S battery as two parallel branches of
 * 2 x 20S, ~147.2 V pack voltage per the 0x01 frames, each branch's 40 cells
 * at 3.70..3.72 V summing to ~148.4 V, section voltages 74.1 / 74.2 V,
 * temperatures 25..28 C, wheel stationary so branch currents are 0.
 */
class BegodeProtocolTest {

    private fun protocolFedWithFixture(): BegodeProtocol {
        val protocol = BegodeProtocol()
        // Feed one notification at a time, exactly as the BLE layer would:
        // at MTU 23 every 24-byte frame straddles two notifications.
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        return protocol
    }

    // --- The no-write rule ---

    @Test
    fun neverWritesToTheWheel() {
        // FFE1 is Begode's COMMAND channel: writes set light, pedal mode,
        // tiltback. A write could reconfigure a wheel under its rider, so the
        // protocol must never emit a single command. Empty lists here are a
        // safety requirement, not a stub.
        val protocol = BegodeProtocol()
        assertTrue(protocol.handshakeCommands().isEmpty(), "handshake must not write to FFE1")
        assertTrue(protocol.pollCommands().isEmpty(), "polling must not write to FFE1")
    }

    @Test
    fun reportsTwoPacks() {
        assertEquals(2, BegodeProtocol().packCount)
    }

    @Test
    fun usesBegodeSerialUuids() {
        val uuids = BegodeProtocol().uuids
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", uuids.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", uuids.notifyCharUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", uuids.writeCharUuid)
    }

    // --- Decoding the real capture ---

    @Test
    fun decodesBothBranchesFromRealCapture() {
        val protocol = protocolFedWithFixture()

        for (packIndex in 0..1) {
            val data = assertNotNull(
                protocol.latestData(packIndex),
                "branch $packIndex must produce data"
            )
            assertTrue(data.isConnected, "branch $packIndex must be connected")

            // Branch voltage is the SUM OF ITS CELLS (~148.4 V), not the
            // 0x01 frame's pack-voltage field (~147.2 V at 0.1 V/unit). That
            // field is not in the frame's own 0.1 V units — across the 86
            // samples of this capture cellSum / raw is a constant 0.1009 — and
            // showing 147.2 V next to "4.09 V/cell" on a 40S branch was a
            // visible contradiction on the wheel.
            assertTrue(
                data.voltage in 148.1f..148.7f,
                "branch $packIndex voltage ${data.voltage} should be the cell sum ~148.4 V"
            )
            assertEquals(
                data.cellVoltages.sum(), data.voltage, 1e-3f,
                "branch $packIndex voltage must equal its cell sum exactly"
            )

            // Wheel was stationary: branch current is exactly 0 in every frame.
            assertEquals(0f, data.current, 0.01f, "branch $packIndex current")
            assertEquals(0f, data.power, 1f, "branch $packIndex power")

            // 40 cells per branch (2 x 20S in series) — the branch's own cells
            // only, never both branches merged (which would be 80).
            assertEquals(40, data.cellVoltages.size, "branch $packIndex cell count")
            data.cellVoltages.forEachIndexed { i, v ->
                assertTrue(
                    v in 3.70f..3.72f,
                    "branch $packIndex cell $i = $v V outside 3.70..3.72"
                )
            }

            // Each branch's cells sum to ~148.4 V (two 20S sections in series).
            val sum = data.cellVoltages.sum()
            assertTrue(
                sum in 148.1f..148.7f,
                "branch $packIndex cell sum $sum should be ~148.4 V"
            )

            // Two temperatures per section, two sections per branch. The
            // capture's ground truth is 25..28 C (t1 = 28 C, t2 = 25..26 C on
            // every bmsnum), so the band pins the decode to the known values —
            // a decoder wrong by even a few degrees must fail here.
            assertEquals(4, data.temperatures.size, "branch $packIndex temp count")
            data.temperatures.forEach { t ->
                assertTrue(t in 25f..28f, "branch $packIndex temp $t outside 25..28")
            }
        }
    }

    // --- Branch voltage comes from the cells, with the frame field as fallback ---

    @Test
    fun branchVoltageFallsBackToTheFrameFieldBeforeAnyCellsArrive() {
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))
        val data = assertNotNull(protocol.latestData(0))
        // No cells yet — the 0x01 field is all we have, ~1 % low but usable.
        assertEquals(147.2f, data.voltage, 0.01f)
    }

    @Test
    fun branchWithTelemetryButNoCellsDoesNotKnowItsCharge() {
        // The pack-voltage field is enough to render voltage, but it is not a
        // fuel gauge. Until a branch supplies cells, a downstream estimator
        // has no evidence from which to earn a charge percentage.
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))

        val data = assertNotNull(protocol.latestData(0))
        assertTrue(data.cellVoltages.isEmpty(), "precondition: no cells arrived")
        assertFalse(data.socKnown, "telemetry without cells must not claim a known charge")
    }

    @Test
    fun livePhaseCurrentStaysOutOfBatteryDataUntilSevenFrameCurrentArrives() {
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, currentRaw = -350))

        val batteryBeforeSeven = assertNotNull(protocol.latestData(0))
        assertEquals(0f, batteryBeforeSeven.current, 0f, "0x00 current is phase current")
        assertFalse(batteryBeforeSeven.hasCurrent, "phase current is not BMS current")
        assertEquals(0f, batteryBeforeSeven.power, 0f)
        assertFalse(batteryBeforeSeven.hasPower, "phase current must not create battery power")

        val motionBeforeSeven = assertNotNull(protocol.latestMotion(0))
        assertEquals(3.5f, motionBeforeSeven.motorCurrentA, 0.001f)

        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))

        val batteryAfterSeven = assertNotNull(protocol.latestData(0))
        assertEquals(-0.67f, batteryAfterSeven.current, 0.001f)
        assertTrue(batteryAfterSeven.hasCurrent, "0x07 supplies battery current")
        assertEquals(0f, batteryAfterSeven.power, 0f)
        assertFalse(batteryAfterSeven.hasPower, "the synthetic pack still has no measured pack power")

        // The 0x00 phase-current reading remains the controller motor-current
        // reading after the independent battery-current frame arrives.
        assertEquals(3.5f, assertNotNull(protocol.latestMotion(0)).motorCurrentA, 0.001f)
    }

    @Test
    fun branchKnowsItsChargeOnceItsCellsComplete() {
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))
        for (packet in 0 until 5) {
            protocol.onNotification(cellFrame(type = 0x02, packetIndex = packet, baseMv = 3710))
        }

        val data = assertNotNull(protocol.latestData(0))
        assertEquals(40, data.cellVoltages.size, "precondition: cells complete")
        assertTrue(data.socKnown, "cells make the branch charge estimate knowable")
    }

    @Test
    fun nonZeroChargeOnABranchWithoutCellsRemainsUnknown() {
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))

        val data = assertNotNull(protocol.latestData(0))
        val estimatorFilledSoc = data.copy(soc = 80.6f)
        assertTrue(estimatorFilledSoc.cellVoltages.isEmpty(), "precondition: no cells arrived")
        assertFalse(
            estimatorFilledSoc.socKnown,
            "a non-zero estimate cannot turn absent cells into evidence"
        )
    }

    @Test
    fun aPartialCellSetNeverBecomesTheBranchVoltage() {
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))
        // Only the first of five cell packets: 8 cells summing to ~29.7 V.
        protocol.onNotification(cellFrame(type = 0x02, packetIndex = 0, baseMv = 3710))
        val data = assertNotNull(protocol.latestData(0))
        assertEquals(8, data.cellVoltages.size, "precondition: cells still arriving")
        assertEquals(147.2f, data.voltage, 0.01f, "a partial cell sum must not be published as the pack voltage")
    }

    @Test
    fun aPartialCellPageWithNonZeroTelemetryStillHasUnknownSoc() {
        val protocol = BegodeProtocol()
        protocol.onNotification(
            telemetryFrame(
                bmsnum = 0,
                packVoltageRaw = 1472,
                currentRaw = 88,
                t1 = 28,
                t2 = 26,
                sectionVoltageRaw = 741
            )
        )
        protocol.onNotification(cellFrame(type = 0x02, packetIndex = 0, baseMv = 3710))

        val data = assertNotNull(protocol.latestData(0))
        assertEquals(8, data.cellVoltages.size, "precondition: the page is partial")
        assertTrue(data.voltage > 0f, "the partial sample still carries a voltage")
        assertTrue(data.power < 0f, "the partial sample still carries non-zero power-like data")
        assertFalse(data.socKnown, "partial cells are not a complete pack SoC")
    }

    @Test
    fun theFullCellSetBecomesTheBranchVoltageAndDrivesPower() {
        val protocol = BegodeProtocol()
        // 8.8 A of DISCHARGE on the branch (the field's unit is 0.1 A) —
        // roughly the per-branch half of the 17.70 A a reference app showed for
        // the wheel. The wire field is discharge-positive and `BmsData` is
        // charge-positive, so a positive raw becomes a negative current; the
        // 2026-08-01 field test is what established that, and this assertion
        // carried the old sign until then.
        protocol.onNotification(
            telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, currentRaw = 88, t1 = 28, t2 = 26, sectionVoltageRaw = 741)
        )
        for (packet in 0 until 5) {
            protocol.onNotification(cellFrame(type = 0x02, packetIndex = packet, baseMv = 3710))
        }
        val data = assertNotNull(protocol.latestData(0))
        assertEquals(40, data.cellVoltages.size)
        val cellSum = data.cellVoltages.sum()
        assertEquals(148.54f, cellSum, 0.01f, "fixture-shaped cells sum to ~148.54 V")
        assertEquals(cellSum, data.voltage, 1e-3f, "voltage is the cell sum once the set is complete")
        assertEquals(-8.8f, data.current, 1e-3f, "a discharging branch reads as discharging")
        // Power is voltage x current, so correcting the voltage moves it too:
        // 8.8 A x 148.54 V = 1307.2 W, where the frame field gave 1295.4 W —
        // and negative, because the pack is delivering it rather than taking it.
        assertEquals(cellSum * -8.8f, data.power, 0.1f)
    }

    @Test
    fun mosfetFlagsAreOnBecauseAWheelReportsNoMosfetState() {
        // A Begode reports no charge/discharge MOSFET state at all. Defaulting
        // the flags to false painted alarming red "OFF" badges on a healthy
        // wheel — a wheel streaming telemetry is by definition not cut off, so
        // both flags must read true on every decoded branch.
        val protocol = protocolFedWithFixture()
        for (packIndex in 0..1) {
            val data = assertNotNull(protocol.latestData(packIndex))
            assertTrue(data.chargeEnabled, "branch $packIndex chargeEnabled must be true")
            assertTrue(data.dischargeEnabled, "branch $packIndex dischargeEnabled must be true")
        }
    }

    @Test
    fun branchesReportTheirOwnCells() {
        val protocol = protocolFedWithFixture()
        val cells0 = assertNotNull(protocol.latestData(0)).cellVoltages
        val cells1 = assertNotNull(protocol.latestData(1)).cellVoltages
        // The branches are physically distinct packs: identical 40-value lists
        // would mean one branch's cells leaked into the other.
        assertTrue(cells0 != cells1, "branch cell lists must be independent")
    }

    @Test
    fun chunkBoundariesDoNotMatter() {
        val perNotification = protocolFedWithFixture()

        // Same bytes, one giant chunk: the parser must not depend on where
        // the BLE layer happened to cut the stream.
        val oneChunk = BegodeProtocol()
        val allBytes = BegodeDumpFixture.chunks()
            .fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        oneChunk.onNotification(allBytes)

        for (packIndex in 0..1) {
            val a = assertNotNull(perNotification.latestData(packIndex))
            val b = assertNotNull(oneChunk.latestData(packIndex))
            assertSameDecodedData(a, b, "pack $packIndex")
        }
    }

    @Test
    fun garbagePrefixDoesNotPreventDecoding() {
        val clean = protocolFedWithFixture()

        val dirty = BegodeProtocol()
        // Garbage containing a deceptive 55 AA that is NOT a frame start: the
        // parser must resync one byte at a time until the real stream begins.
        dirty.onNotification(byteArrayOf(0x00, 0x55, 0xAA.toByte(), 0x13, 0x37, 0x5A, 0x5A))
        BegodeDumpFixture.chunks().forEach { dirty.onNotification(it) }

        for (packIndex in 0..1) {
            val a = assertNotNull(clean.latestData(packIndex))
            val b = assertNotNull(dirty.latestData(packIndex), "pack $packIndex lost after garbage")
            assertSameDecodedData(a, b, "pack $packIndex")
        }
    }

    // --- Boot-time zero telemetry (the wheel zero-pads 0x01 frames at start) ---

    @Test
    fun bootZeroFramesNeverPublishZeroTemperatures() {
        // The capture opens with ~5 s of zero-padded 0x01 frames: 13 all-zero
        // telemetry frames through notification 78, and under a naive decoder
        // the stale 0 C for branch 0 / section 0 survives in published data
        // until the first genuine bmsnum-0 frame completes at notification 98.
        // Walking the WHOLE fixture notification by notification covers that
        // opening window as a prefix (any prefix >= 99 would catch it) and
        // additionally proves zeros never reappear mid-stream.
        val protocol = BegodeProtocol()
        BegodeDumpFixture.chunks().forEachIndexed { i, chunk ->
            protocol.onNotification(chunk)
            for (packIndex in 0..1) {
                protocol.latestData(packIndex)?.temperatures?.forEach { t ->
                    assertTrue(
                        t != 0f,
                        "branch $packIndex published 0 C after notification $i"
                    )
                }
            }
        }
        // Not vacuous: all four real temperatures must have arrived by the end.
        for (packIndex in 0..1) {
            assertEquals(
                4,
                assertNotNull(protocol.latestData(packIndex)).temperatures.size,
                "branch $packIndex temp count after full fixture"
            )
        }
    }

    @Test
    fun bootPlaceholderKeepsPreviousTemperatures() {
        val protocol = BegodeProtocol()
        // A real reading first...
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))
        // ...then a zero-padded boot-style frame for the same section.
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1473, t1 = 0, t2 = 0, sectionVoltageRaw = 0))
        val data = assertNotNull(protocol.latestData(0))
        // Pack voltage is real even in boot frames and must update; the
        // zero-padded temperatures must not clobber the known ones.
        assertEquals(147.3f, data.voltage, 0.01f)
        assertEquals(listOf(28f, 26f), data.temperatures)
    }

    @Test
    fun genuineZeroCelsiusReadingIsAccepted() {
        // A wheel really can sit at 0 C in winter. Unlike a boot placeholder,
        // a genuine cold frame carries a live section voltage (a 20S section
        // never reads 0.0 V), so it must be published as-is.
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 0, t2 = 0, sectionVoltageRaw = 741))
        val data = assertNotNull(protocol.latestData(0))
        assertEquals(listOf(0f, 0f), data.temperatures, "0 C with live section voltage is real data")
    }

    // --- Cell positions must survive a missing middle packet ---

    @Test
    fun missingMiddleCellPacketDoesNotShiftPositions() {
        val protocol = BegodeProtocol()
        // Telemetry first: a branch publishes nothing before its 0x01 frame.
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))

        // Each cell gets a value unique to its physical position.
        fun mvOf(cell: Int) = 3000 + (cell / 8) * 100 + (cell % 8)

        // Packets arrive out of order and packet 2 (cells 16..23) is missing.
        for (packet in intArrayOf(3, 0, 4, 1)) {
            protocol.onNotification(cellFrame(type = 0x02, packetIndex = packet, baseMv = 3000 + packet * 100))
        }

        val partial = assertNotNull(protocol.latestData(0)).cellVoltages
        // Only the contiguous run 0..15 may be exposed: a map compacted around
        // the gap would show physical cell 24 at list index 16, and the
        // dashboard renders this list positionally.
        assertEquals(16, partial.size, "list must stop at the first gap")
        partial.forEachIndexed { i, v ->
            assertEquals(mvOf(i) / 1000f, v, 1e-4f, "cell $i misplaced — positions shifted")
        }

        // Once the missing packet lands, the full list appears, each cell in place.
        protocol.onNotification(cellFrame(type = 0x02, packetIndex = 2, baseMv = 3000 + 2 * 100))
        val full = assertNotNull(protocol.latestData(0)).cellVoltages
        assertEquals(40, full.size, "full list once every packet arrived")
        full.forEachIndexed { i, v ->
            assertEquals(mvOf(i) / 1000f, v, 1e-4f, "cell $i after gap filled")
        }
    }

    // --- Physical assemblies (sections) ---

    /** Wraps a branch's decode into the [PackState] the UI grouping sees. */
    private fun packStateOf(protocol: BegodeProtocol, packIndex: Int): PackState =
        PackState(
            pack = Pack(index = packIndex, label = "Branch", bmsType = BmsType.BEGODE, bmsAddress = "AA:BB"),
            data = assertNotNull(protocol.latestData(packIndex)),
            sections = protocol.sections(packIndex),
            isOnline = true
        )

    @Test
    fun fixtureYieldsTwoVerifiedSectionsPerBranch() {
        val protocol = protocolFedWithFixture()

        // Ground truth from the capture (see the multi-pack spec): each
        // branch's first 20 cells sum to its reported 74.1 V assembly, the
        // second 20 to the 74.2 V one.
        val expectedCellSums = listOf(
            74.19f to 74.25f, // branch 0
            74.14f to 74.27f  // branch 1
        )

        for (packIndex in 0..1) {
            val sections = protocol.sections(packIndex)
            assertEquals(2, sections.size, "branch $packIndex must report two assemblies")
            assertEquals(0, sections[0].index)
            assertEquals(1, sections[1].index)

            // Assembly voltages exactly as the 0x01 frames report them.
            assertEquals(74.1f, sections[0].voltage, 0.01f, "branch $packIndex assembly 0 voltage")
            assertEquals(74.2f, sections[1].voltage, 0.01f, "branch $packIndex assembly 1 voltage")

            // The split was FOUND against those voltages, not assumed: 20+20.
            assertEquals(0..19, sections[0].cellRange, "branch $packIndex assembly 0 range")
            assertEquals(20..39, sections[1].cellRange, "branch $packIndex assembly 1 range")

            // And it is genuine: each range's cells really sum to the capture's
            // known per-assembly values.
            val cells = assertNotNull(protocol.latestData(packIndex)).cellVoltages
            val (sum0, sum1) = expectedCellSums[packIndex]
            assertEquals(sum0, cells.subList(0, 20).sum(), 0.02f, "branch $packIndex assembly 0 cell sum")
            assertEquals(sum1, cells.subList(20, 40).sum(), 0.02f, "branch $packIndex assembly 1 cell sum")

            // Two temperatures per assembly, matching the capture's final
            // genuine frames (t1 = 28 C, t2 = 25 C on every bmsnum).
            assertEquals(listOf(28f, 25f), sections[0].temperatures, "branch $packIndex assembly 0 temps")
            assertEquals(listOf(28f, 25f), sections[1].temperatures, "branch $packIndex assembly 1 temps")
        }
    }

    @Test
    fun theUiGroupingPredicateAcceptsTheFixtureSections() {
        val protocol = protocolFedWithFixture()
        for (packIndex in 0..1) {
            val groups = groupPackCells(packStateOf(protocol, packIndex))
            assertEquals(2, groups.size, "branch $packIndex must group into its two assemblies")
            assertEquals(0, groups[0].section?.index)
            assertEquals(1, groups[1].section?.index)
            // Positional cell numbering survives the boundary: the second
            // assembly starts at physical cell 20 (rendered as cell 21).
            assertEquals(0, groups[0].startIndex)
            assertEquals(20, groups[1].startIndex)
            assertEquals(20, groups[0].voltages.size)
            assertEquals(20, groups[1].voltages.size)
        }
    }

    @Test
    fun noRangesAndNoGroupingWhileCellsAreStillArriving() {
        // Walk the real capture notification by notification: at NO point may
        // a branch with an incomplete cell list carry a cell range, and the UI
        // grouping must stay flat until the branch's full 40 cells landed.
        val protocol = BegodeProtocol()
        var partialStatesWithSections = 0
        BegodeDumpFixture.chunks().forEachIndexed { i, chunk ->
            protocol.onNotification(chunk)
            for (packIndex in 0..1) {
                val data = protocol.latestData(packIndex) ?: continue
                if (data.cellVoltages.size >= 40) continue
                val sections = protocol.sections(packIndex)
                if (sections.isNotEmpty()) partialStatesWithSections++
                sections.forEach { s ->
                    assertNull(
                        s.cellRange,
                        "branch $packIndex fabricated a range with only " +
                            "${data.cellVoltages.size} cells after notification $i"
                    )
                }
                val groups = groupPackCells(packStateOf(protocol, packIndex))
                assertTrue(
                    groups.all { it.section == null },
                    "branch $packIndex grouped an incomplete cell list after notification $i"
                )
            }
        }
        // Not vacuous: the capture really contains states where both assembly
        // voltages are known while the cells are still arriving.
        assertTrue(
            partialStatesWithSections > 0,
            "the fixture must exercise the sections-known / cells-partial window"
        )
    }

    @Test
    fun noSectionsBeforeBothAssemblyVoltagesArrive() {
        val protocol = BegodeProtocol()
        assertTrue(protocol.sections(0).isEmpty(), "no telemetry — no sections")

        // Boot-placeholder frames (all-zero payload) carry no assembly voltage
        // and must not create 0.0 V sections.
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 0, t2 = 0, sectionVoltageRaw = 0))
        protocol.onNotification(telemetryFrame(bmsnum = 1, packVoltageRaw = 1472, t1 = 0, t2 = 0, sectionVoltageRaw = 0))
        assertTrue(protocol.sections(0).isEmpty(), "boot placeholders are not evidence")

        // One genuine assembly is still only half a breakdown.
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))
        assertTrue(protocol.sections(0).isEmpty(), "one assembly voltage is not a breakdown")

        // Both genuine: the breakdown appears, for this branch only.
        protocol.onNotification(telemetryFrame(bmsnum = 1, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 742))
        val sections = protocol.sections(0)
        assertEquals(2, sections.size)
        assertEquals(74.1f, sections[0].voltage, 0.01f)
        assertEquals(74.2f, sections[1].voltage, 0.01f)
        assertTrue(protocol.sections(1).isEmpty(), "branch 1 has seen nothing")
    }

    @Test
    fun assemblyVoltagesThatDoNotAddUpToTheCellsYieldNoRanges() {
        // A wheel we have never seen: assemblies report 72.0 / 76.5 V, but the
        // cells (fixture-shaped, ~3.71 V each, total ~148.5) have no prefix
        // summing to 72.0 — the nearest prefix sums are 70.55 (19 cells) and
        // 74.26 (20 cells), both beyond the tolerance. Evidence contradicts
        // any split, so the honest answer is voltages without ranges.
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1485, t1 = 28, t2 = 26, sectionVoltageRaw = 720))
        protocol.onNotification(telemetryFrame(bmsnum = 1, packVoltageRaw = 1485, t1 = 28, t2 = 26, sectionVoltageRaw = 765))
        for (packet in 0 until 5) {
            protocol.onNotification(cellFrame(type = 0x02, packetIndex = packet, baseMv = 3710))
        }

        val sections = protocol.sections(0)
        assertEquals(2, sections.size, "the assembly voltages themselves are real evidence")
        assertEquals(72.0f, sections[0].voltage, 0.01f)
        assertEquals(76.5f, sections[1].voltage, 0.01f)
        assertNull(sections[0].cellRange, "no split verifies — no range")
        assertNull(sections[1].cellRange, "no split verifies — no range")

        // And the UI degrades to one flat, unattributed cell list.
        val groups = groupPackCells(packStateOf(protocol, 0))
        assertEquals(1, groups.size)
        assertNull(groups[0].section)
    }

    @Test
    fun anAmbiguousSplitIsRefused() {
        // Degenerate cells where TWO splits fit the reported voltages within
        // tolerance: [2.0, 0.1, 2.0, ...] with assemblies 2.1 and 6.3 V —
        // both k=1 (2.0 / 6.4) and k=2 (2.1 / 6.3) fit. Real hardware never
        // looks like this, but if it ever does there is no evidence for either
        // boundary, and no range beats a coin flip.
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 84, t1 = 28, t2 = 26, sectionVoltageRaw = 21))
        protocol.onNotification(telemetryFrame(bmsnum = 1, packVoltageRaw = 84, t1 = 28, t2 = 26, sectionVoltageRaw = 63))
        protocol.onNotification(
            cellFrameOf(type = 0x02, packetIndex = 0, mv = intArrayOf(2000, 100, 2000, 100, 2000, 100, 2000, 100))
        )

        val sections = protocol.sections(0)
        assertEquals(2, sections.size)
        assertNull(sections[0].cellRange, "two fitting splits — refuse both")
        assertNull(sections[1].cellRange, "two fitting splits — refuse both")
    }

    @Test
    fun theSplitSearchIsModelAgnostic() {
        // A T4-like branch: 24 cells (3 packets), two 12S assemblies. Nothing
        // in the protocol knows this layout — the same search that resolves
        // the ET Max's 20+20 must resolve 12+12 from the voltages alone.
        // First 12 cells of the builder's pattern sum to 46.834 V, the
        // remaining 12 to 46.850 V; the frames report 46.8 for both.
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 936, t1 = 20, t2 = 21, sectionVoltageRaw = 468))
        protocol.onNotification(telemetryFrame(bmsnum = 1, packVoltageRaw = 936, t1 = 20, t2 = 21, sectionVoltageRaw = 468))
        for (packet in 0 until 3) {
            protocol.onNotification(cellFrame(type = 0x02, packetIndex = packet, baseMv = 3900))
        }

        val sections = protocol.sections(0)
        assertEquals(2, sections.size)
        assertEquals(0..11, sections[0].cellRange, "24 cells resolve to 12+12 with nothing hard-coded")
        assertEquals(12..23, sections[1].cellRange)

        val groups = groupPackCells(packStateOf(protocol, 0))
        assertEquals(2, groups.size, "the UI grouping accepts the verified 12+12 split")
        assertEquals(12, groups[0].voltages.size)
        assertEquals(12, groups[1].voltages.size)
    }

    @Test
    fun temperaturesLandOnTheRightAssembly() {
        // bmsnum 0 and 1 are branch 0's two assemblies, 2 and 3 branch 1's.
        // Distinct temperatures per bmsnum pin the full mapping: a decoder
        // that swaps assemblies within a branch, or branches, must fail here.
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 30, t2 = 31, sectionVoltageRaw = 741))
        protocol.onNotification(telemetryFrame(bmsnum = 1, packVoltageRaw = 1472, t1 = 32, t2 = 33, sectionVoltageRaw = 742))
        protocol.onNotification(telemetryFrame(bmsnum = 2, packVoltageRaw = 1472, t1 = 34, t2 = 35, sectionVoltageRaw = 741))
        protocol.onNotification(telemetryFrame(bmsnum = 3, packVoltageRaw = 1472, t1 = 36, t2 = 37, sectionVoltageRaw = 742))

        val branch0 = protocol.sections(0)
        val branch1 = protocol.sections(1)
        assertEquals(listOf(30f, 31f), branch0[0].temperatures, "bmsnum 0 -> branch 0, assembly 0")
        assertEquals(listOf(32f, 33f), branch0[1].temperatures, "bmsnum 1 -> branch 0, assembly 1")
        assertEquals(listOf(34f, 35f), branch1[0].temperatures, "bmsnum 2 -> branch 1, assembly 0")
        assertEquals(listOf(36f, 37f), branch1[1].temperatures, "bmsnum 3 -> branch 1, assembly 1")
    }

    @Test
    fun resetClearsSections() {
        val protocol = protocolFedWithFixture()
        assertEquals(2, protocol.sections(0).size, "precondition: fixture produced sections")
        protocol.reset()
        assertTrue(protocol.sections(0).isEmpty())
        assertTrue(protocol.sections(1).isEmpty())
    }

    @Test
    fun noDataBeforeAnyBmsFrame() {
        val protocol = BegodeProtocol()
        assertNull(protocol.latestData(0))
        assertNull(protocol.latestData(1))
    }

    @Test
    fun resetClearsState() {
        val protocol = protocolFedWithFixture()
        assertNotNull(protocol.latestData(0))
        protocol.reset()
        assertNull(protocol.latestData(0))
        assertNull(protocol.latestData(1))

        // And the protocol decodes again from scratch after a reset.
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        val data = assertNotNull(protocol.latestData(0))
        assertEquals(40, data.cellVoltages.size)
    }

    // --- Derived cell count: the rail voltage without a vehicle profile ---

    /** Replays the real ET Max capture, exactly as [protocolFedWithFixture] does. */
    private fun feedEtMaxSmartBmsCapture(protocol: BegodeProtocol) {
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
    }

    @Test
    fun branchOnesCellsMustNeverDeriveTheCellCountEvenWhenTheyLookComplete() {
        // Branch 0 is the wheel's series-cell count (the two branches are
        // PARALLEL — see the class KDoc); branch 1 is not evidence of anything
        // beyond itself. Branch 0's own telemetry sets a real frame voltage
        // (147.2 V, matching the ET Max), but branch 0's cells never arrive —
        // so branch 0 has proven nothing.
        //
        // Branch 1 gets a matching frame voltage (realistic: parallel
        // branches report the same nominal voltage) and 34 cells at 4.329 V —
        // deliberately chosen so [isCellCountConfirmed] would ACCEPT this set
        // on its own (147.186 V against a 147.2 V frame is a ratio of 0.99990,
        // comfortably inside the tight lower edge) and the live-frame
        // convention check would too (avg 4.329 V against the 4.2 V the 67.2 V
        // reference assumes is a 3.07 % deviation, inside its 5 % tolerance).
        // So this is NOT a test of either ratio gate: if branch 1's cells
        // could reach [updateDerivedCellCount] at all, they would wrongly
        // derive 34S here — only the `packIndex == 0` gate in `rebuild`
        // refuses it, and branch 0 having proven nothing must leave the count
        // null.
        val protocol = BegodeProtocol(cellCount = null)
        protocol.onNotification(liveFrame(voltageRaw = 6720)) // 67.2 V — the reference scale itself
        protocol.onNotification(
            telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741)
        )
        protocol.onNotification(
            telemetryFrame(bmsnum = 2, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741)
        )
        protocol.onNotification(cellFrameOf(type = 0x03, packetIndex = 0, mv = IntArray(8) { 4329 }))
        protocol.onNotification(cellFrameOf(type = 0x03, packetIndex = 1, mv = IntArray(8) { 4329 }))
        protocol.onNotification(cellFrameOf(type = 0x03, packetIndex = 2, mv = IntArray(8) { 4329 }))
        protocol.onNotification(cellFrameOf(type = 0x03, packetIndex = 3, mv = IntArray(8) { 4329 }))
        protocol.onNotification(
            cellFrameOf(type = 0x03, packetIndex = 4, mv = intArrayOf(4329, 4329, 0, 0, 0, 0, 0, 0))
        )
        assertEquals(
            34, assertNotNull(protocol.latestData(1)).cellVoltages.size,
            "precondition: branch 1 complete at 34 cells"
        )
        assertTrue(
            assertNotNull(protocol.latestData(0)).cellVoltages.isEmpty(),
            "precondition: branch 0 has proven no cells at all"
        )
        assertNull(
            protocol.derivedCellCount(),
            "branch 1 is not evidence — branch 0 never proved anything"
        )
    }

    @Test
    fun smartBmsCellsSupplyTheRailVoltageWithNoProfileCellCount() {
        // The ET Max has a smart BMS whose branches are PARALLEL, so branch
        // 0's series-cell count IS the pack's — no vehicle profile needed at
        // all. This is the field report's bug: the rider's wheel was streaming
        // everything needed to compute its rail voltage while only a
        // human-entered cell count was ever consulted.
        val protocol = BegodeProtocol(cellCount = null) // nothing from the profile
        feedEtMaxSmartBmsCapture(protocol)
        val motion = assertNotNull(protocol.latestMotion(0))
        assertTrue(motion.hasInputVoltage, "the wheel's own cells prove a rail voltage")
        assertEquals(147.2f, motion.inputVoltageV, 0.5f)
        assertEquals(40, protocol.derivedCellCount())
    }

    @Test
    fun aProfileCellCountNeverOverridesTheWheelsOwnDerivedOne() {
        // Precedence: the wheel's own proof beats a profile that could be
        // stale, wrong, or simply mistyped. A (deliberately wrong) profile of
        // 20 would scale the capture's final live frame to 73.60 V; the
        // wheel's own cells prove 40S and 147.2 V.
        val protocol = BegodeProtocol(cellCount = 20)
        feedEtMaxSmartBmsCapture(protocol)
        val motion = assertNotNull(protocol.latestMotion(0))
        assertEquals(40, protocol.derivedCellCount())
        assertEquals(147.2f, motion.inputVoltageV, 0.5f, "the wheel's own proof wins over a wrong profile")
    }

    @Test
    fun aBootZeroTelemetryFrameIsNotEvidenceOfCompletenessEither() {
        // A booting BMS zero-pads its ENTIRE 0x01 payload, including the pack
        // voltage field itself (parseBmsTelemetry's isBootPlaceholder; the real
        // capture's opening ~13 frames). With no frame voltage to check a cell
        // sum against, that is a missing witness, not a free pass — it must
        // not be read as "nothing to disprove completeness with, so anything
        // goes". There is no separate `frameVoltage <= 0f` guard in
        // `updateDerivedCellCount` (see its comment): `cellSum / 0f` is
        // +Infinity, which fails `isCellCountConfirmed`'s upper edge outright,
        // so the boot-zero case is refused by the ratio itself. The live
        // frame here is chosen so the (irrelevant, since gate 1 already
        // refuses) live-frame convention check would ALSO have passed, to
        // keep this test pointed at the frame-voltage case specifically.
        val protocol = BegodeProtocol(cellCount = null)
        protocol.onNotification(liveFrame(voltageRaw = 5942))
        protocol.onNotification(
            telemetryFrame(bmsnum = 0, packVoltageRaw = 0, t1 = 0, t2 = 0, sectionVoltageRaw = 0)
        )
        protocol.onNotification(cellFrame(type = 0x02, packetIndex = 0, baseMv = 3710))
        assertNull(
            protocol.derivedCellCount(),
            "no frame voltage to check against must not be mistaken for a proven count"
        )
    }

    @Test
    fun aPartialCellSetMidStreamNeverDerivesACellCount() {
        // The single most likely way to get this task wrong: contiguousCells
        // legitimately returns a TRUNCATED run while packets are still
        // arriving, and a naive `cells.size` would derive 8S here and scale
        // the live frame to ~29.5 V on a 168 V wheel.
        val protocol = BegodeProtocol(cellCount = null)
        protocol.onNotification(liveFrame(voltageRaw = 5892))
        protocol.onNotification(
            telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741)
        )
        // Only the first of five cell packets: 8 cells summing to ~29.7 V —
        // far short of 145.73 V, isCellCountConfirmed's tight lower edge (99 %
        // of the 147.2 V a confirmed count must clear).
        protocol.onNotification(cellFrame(type = 0x02, packetIndex = 0, baseMv = 3710))
        assertEquals(
            8, assertNotNull(protocol.latestData(0)).cellVoltages.size,
            "precondition: cells still arriving"
        )
        assertNull(
            protocol.derivedCellCount(),
            "a truncated cell run must not be mistaken for a full pack"
        )
        val motion = assertNotNull(protocol.latestMotion(0))
        assertFalse(
            motion.hasInputVoltage,
            "no profile cell count and no proven derived one — voltage stays unknown"
        )
    }

    @Test
    fun aBranchWhoseCellsContradictTheLiveFrameRefusesToDeriveACellCount() {
        // Count confirmation alone is not enough: 20 cells at 4.00 V sum to
        // exactly the reported 80.0 V pack voltage (ratio 1.0, comfortably
        // inside isCellCountConfirmed's band), but the live frame's OWN
        // 67.2 V-scale reading implies a ~32S pack (actual ratio 2.0 against
        // the 1.25 expected for 20S) — the two witnesses disagree, and a wheel that
        // contradicts itself gets an honest absence rather than a guess.
        val protocol = BegodeProtocol(cellCount = null)
        protocol.onNotification(liveFrame(voltageRaw = 4000))
        protocol.onNotification(
            telemetryFrame(bmsnum = 0, packVoltageRaw = 800, t1 = 28, t2 = 26, sectionVoltageRaw = 400)
        )
        protocol.onNotification(cellFrameOf(type = 0x02, packetIndex = 0, mv = IntArray(8) { 4000 }))
        protocol.onNotification(cellFrameOf(type = 0x02, packetIndex = 1, mv = IntArray(8) { 4000 }))
        // Cells 16..19 real, 20..23 zero-padded (fewer cells than the packet
        // grid) — contiguousCells stops the run at 20, honestly.
        protocol.onNotification(
            cellFrameOf(type = 0x02, packetIndex = 2, mv = intArrayOf(4000, 4000, 4000, 4000, 0, 0, 0, 0))
        )
        assertEquals(
            20, assertNotNull(protocol.latestData(0)).cellVoltages.size,
            "precondition: 20 cells, contiguous"
        )
        assertNull(
            protocol.derivedCellCount(),
            "the cross-check must refuse a count the live frame contradicts"
        )
        val motion = assertNotNull(protocol.latestMotion(0))
        assertFalse(motion.hasInputVoltage, "no profile count and the derived one was refused")
    }

    @Test
    fun aCellSumWellAboveTheFrameFieldRefusesToDeriveACellCount() {
        // isCellCountConfirmed's upper edge is deliberately LOOSE (5 %, not a
        // mirror of the 1 % lower edge) — see its KDoc — but it still has to
        // have SOME limit, or a branch reporting sums wildly inconsistent
        // with its own 0x01 frame would still latch a count. 20 cells at
        // 5.00 V (the maximum this parser accepts) sum to 100.0 V against a
        // 90.0 V frame field — a ratio of 1.111, 11.1 % above 1.0, well past
        // the 5 % edge. The live frame is chosen so the (irrelevant, since
        // gate 1 already refuses) live-frame convention check would ALSO
        // have passed, to keep this test pointed at the upper edge alone:
        // avg 5.00 V x 67.2 / 80.0 V = 4.20 V, exactly FULL_CELL_V.
        val protocol = BegodeProtocol(cellCount = null)
        protocol.onNotification(liveFrame(voltageRaw = 8000)) // 80.00 V
        protocol.onNotification(
            telemetryFrame(bmsnum = 0, packVoltageRaw = 900, t1 = 28, t2 = 26, sectionVoltageRaw = 450)
        )
        protocol.onNotification(cellFrameOf(type = 0x02, packetIndex = 0, mv = IntArray(8) { 5000 }))
        protocol.onNotification(cellFrameOf(type = 0x02, packetIndex = 1, mv = IntArray(8) { 5000 }))
        protocol.onNotification(
            cellFrameOf(type = 0x02, packetIndex = 2, mv = intArrayOf(5000, 5000, 5000, 5000, 0, 0, 0, 0))
        )
        assertEquals(
            20, assertNotNull(protocol.latestData(0)).cellVoltages.size,
            "precondition: 20 cells, contiguous"
        )
        assertNull(
            protocol.derivedCellCount(),
            "a sum 11 % above the frame field must not be mistaken for a confirmed count"
        )
    }

    @Test
    fun aThirtySixOfFortyRunMustNeverLatchACellCount() {
        // A half-filled last cell packet: 36 of 40 real-shaped cells (avg
        // ~3.711 V, matching the ET Max capture), the real capture's own
        // frame voltage (147.2 V) and live-frame reading (58.88 V on the
        // 67.2 V scale). Both the old loose completeness cutoff (0.9) AND the
        // cross-check pass for this shortfall — measured: cellSum/frameVoltage
        // = 133.596/147.2 = 0.9076 (above 0.9), and the live-frame ratio
        // disagrees by the same ~0.85 % a genuinely complete branch does,
        // because that check is a function of average cell voltage only and
        // cancels count out algebraically. Latching 36S here would publish
        // ~132.5 V for a 147.2 V rail — 10 % low, with hasInputVoltage = true,
        // which is worse than the absence this task set out to remove.
        val protocol = BegodeProtocol(cellCount = null)
        protocol.onNotification(liveFrame(voltageRaw = 5888))
        protocol.onNotification(
            telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741)
        )
        // 4 full packets (32 cells) plus a half-filled 5th packet (4 more,
        // cells 36..39 never arrive) — 36 contiguous cells at 3711 mV each.
        for (packet in 0 until 4) {
            protocol.onNotification(cellFrameOf(type = 0x02, packetIndex = packet, mv = IntArray(8) { 3711 }))
        }
        protocol.onNotification(
            cellFrameOf(type = 0x02, packetIndex = 4, mv = intArrayOf(3711, 3711, 3711, 3711, 0, 0, 0, 0))
        )
        assertEquals(
            36, assertNotNull(protocol.latestData(0)).cellVoltages.size,
            "precondition: 36 of 40, a half-filled last packet"
        )
        assertNull(
            protocol.derivedCellCount(),
            "36 of 40 is not a proven count — it must not latch 36S"
        )
        val motion = assertNotNull(protocol.latestMotion(0))
        assertFalse(motion.hasInputVoltage, "no profile count and no proven derived one")
    }

    @Test
    fun resetClearsTheDerivedCellCount() {
        // A reconnect may face a different wheel: the constructor `val`
        // ([cellCount]) never needed a clear, but a value LEARNED this
        // connection must not survive into the next one, or a reconnect to a
        // different pack size would silently mis-scale it.
        val protocol = protocolFedWithFixture()
        assertEquals(40, protocol.derivedCellCount(), "precondition: fixture proved 40S")
        protocol.reset()
        assertNull(protocol.derivedCellCount(), "a reconnect may face a different wheel")
    }

    // --- Synthetic frame builders (24 bytes: 55 AA + 16 payload + type + subtype + 5A x4) ---

    // --- latestData identity contract ---

    @Test
    fun latestDataKeepsTheSameInstanceForABranchThatDecodedNothingNew() {
        // PackSampleGate (and through it the per-pack liveness check in
        // VehicleConnection) relies on this: a branch's latestData is REPLACED
        // with a fresh instance on every decode for that branch, and returns
        // the exact same instance while the branch is silent. If this contract
        // breaks, a dead branch either keeps looking alive (never replaced ->
        // fine, still same instance -> gate suppresses; but mutated in place
        // -> gate suppresses a LIVE branch) — pin it.
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))
        protocol.onNotification(telemetryFrame(bmsnum = 2, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))
        val branch0Before = protocol.latestData(0)
        val branch1Before = protocol.latestData(1)

        // Only branch 0 keeps decoding.
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1471, t1 = 28, t2 = 26, sectionVoltageRaw = 741))

        assertTrue(
            protocol.latestData(0) !== branch0Before,
            "a decode for branch 0 must produce a fresh instance"
        )
        assertTrue(
            protocol.latestData(1) === branch1Before,
            "a silent branch must keep returning the same cached instance"
        )
    }

    private fun frame(type: Int, subtype: Int, payload: ByteArray): ByteArray {
        require(payload.size == 16) { "payload is frame bytes 2..17" }
        return byteArrayOf(0x55, 0xAA.toByte()) + payload +
            byteArrayOf(type.toByte(), subtype.toByte(), 0x5A, 0x5A, 0x5A, 0x5A)
    }

    /** 0x01 telemetry frame: pack voltage at 6..7, current at 8..9, temps at 10..13, section voltage at 14..15 (all BE). */
    private fun telemetryFrame(
        bmsnum: Int,
        packVoltageRaw: Int,
        t1: Int,
        t2: Int,
        sectionVoltageRaw: Int,
        currentRaw: Int = 0
    ): ByteArray {
        val p = ByteArray(16)
        p[4] = (packVoltageRaw shr 8).toByte(); p[5] = packVoltageRaw.toByte()
        // Bytes 6..7 of the payload (frame 8..9) are the branch current, 0.1 A.
        p[6] = (currentRaw shr 8).toByte(); p[7] = currentRaw.toByte()
        p[8] = (t1 shr 8).toByte(); p[9] = t1.toByte()
        p[10] = (t2 shr 8).toByte(); p[11] = t2.toByte()
        p[12] = (sectionVoltageRaw shr 8).toByte(); p[13] = sectionVoltageRaw.toByte()
        return frame(0x01, bmsnum, p)
    }

    /** Cell frame (0x02/0x03): 8 cells at frame bytes 2..17, BE millivolts baseMv..baseMv+7. */
    private fun cellFrame(type: Int, packetIndex: Int, baseMv: Int): ByteArray {
        val p = ByteArray(16)
        for (i in 0 until 8) {
            val mv = baseMv + i
            p[i * 2] = (mv shr 8).toByte()
            p[i * 2 + 1] = mv.toByte()
        }
        return frame(type, packetIndex, p)
    }

    /** Cell frame with an explicit millivolt value per cell. */
    private fun cellFrameOf(type: Int, packetIndex: Int, mv: IntArray): ByteArray {
        require(mv.size == 8) { "a cell frame carries exactly 8 cells" }
        val p = ByteArray(16)
        for (i in 0 until 8) {
            p[i * 2] = (mv[i] shr 8).toByte()
            p[i * 2 + 1] = mv[i].toByte()
        }
        return frame(type, packetIndex, p)
    }

    /** Live 0x00 frame with voltage (bytes 2..3) and phase current (10..11). */
    private fun liveFrame(voltageRaw: Int, currentRaw: Int = 0): ByteArray {
        val p = ByteArray(16)
        p[0] = (voltageRaw shr 8).toByte(); p[1] = voltageRaw.toByte()
        p[8] = (currentRaw shr 8).toByte(); p[9] = currentRaw.toByte()
        return frame(0x00, 24, p)
    }

    /** Live 0x07 frame with battery current, motor temperature and duty. */
    private fun motionFrame(batteryCurrentRaw: Int, motorTempRaw: Int, dutyRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[0] = (batteryCurrentRaw shr 8).toByte(); p[1] = batteryCurrentRaw.toByte()
        p[4] = (motorTempRaw shr 8).toByte(); p[5] = motorTempRaw.toByte()
        p[6] = (dutyRaw shr 8).toByte(); p[7] = dutyRaw.toByte()
        return frame(0x07, 24, p)
    }

    private fun assertSameDecodedData(a: BmsData, b: BmsData, label: String) {
        assertEquals(a.voltage, b.voltage, 0.001f, "$label voltage")
        assertEquals(a.current, b.current, 0.001f, "$label current")
        assertEquals(a.cellVoltages, b.cellVoltages, "$label cells")
        assertEquals(a.temperatures, b.temperatures, "$label temps")
    }
}
