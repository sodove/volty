package ru.sodovaya.volty.presentation.vehicle.info

import ru.sodovaya.volty.domain.alert.AlertLevel
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.AlertAvailability
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.availabilityFor
import ru.sodovaya.volty.domain.energy.CapacitySource
import ru.sodovaya.volty.domain.energy.CurrentSource
import ru.sodovaya.volty.domain.energy.EnergyDetail
import ru.sodovaya.volty.domain.energy.EnergyMemory
import ru.sodovaya.volty.domain.energy.EnergyRecord
import ru.sodovaya.volty.domain.energy.EnergyState
import ru.sodovaya.volty.domain.energy.LearnedCapacity
import ru.sodovaya.volty.domain.energy.PersistedCharge
import ru.sodovaya.volty.domain.energy.RangeDetail
import ru.sodovaya.volty.domain.energy.RangeEstimate
import ru.sodovaya.volty.domain.energy.RangeUnearnedReason
import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class VehicleInfoModelTest {
    private val epoch = Instant.fromEpochSeconds(0)

    private val bike = Vehicle(
        id = "bike", name = "Bike", iconKey = "bicycle",
        packs = listOf(Pack(0, "Main pack", BmsType.ANT_BMS, "BB", cellCount = 20)),
        controllers = listOf(Controller(0, "Motor", ControllerType.VESC, "AA")),
        chemistry = Chemistry.LI_ION_NMC, createdAt = epoch,
    )
    private val wheel = Vehicle(
        id = "wheel", name = "T4", iconKey = "wheel",
        packs = listOf(Pack(0, "Wheel pack", BmsType.BEGODE, "WW")),
        controllers = listOf(Controller(0, "Wheel", ControllerType.BEGODE, "WW")),
        chemistry = Chemistry.LI_ION_NMC, createdAt = epoch,
    )

    private fun info(
        vehicle: Vehicle,
        connected: Boolean = true,
        data: VehicleData? = null,
        motion: ControllerData? = data?.motion,
        links: List<BleDiagnosticLink> = emptyList(),
        stored: EnergyRecord? = null,
        range: RangeDetail? = null,
        rangeEstimate: RangeEstimate? = null,
        units: UnitSystem = UnitSystem.METRIC,
        now: Long = 100_000L,
    ) = vehicleInfoOf(vehicle, connected, data, motion, links, stored, range, availabilityFor(vehicle, motion), units, now, rangeEstimate = rangeEstimate)

    private fun VehicleInfo.section(kind: InfoSectionKind) = sections.filter { it.kind == kind }
    private fun VehicleInfo.row(kind: InfoSectionKind, label: InfoLabel, title: String? = null) =
        sections.first { it.kind == kind && (title == null || it.title == title) }.rows.first { it.label == label }
    private fun InfoRow.vu() = value to unit
    private fun VehicleInfo.allValues() = sections.flatMap { it.rows }.mapNotNull { it.value }

    private fun packState(pack: Pack, data: BmsData, online: Boolean = true) = PackState(pack, data, isOnline = online)
    private fun controllerState(c: Controller, data: ControllerData, online: Boolean = true) = ControllerState(c, data, isOnline = online)

    private fun detail() = EnergyDetail(
        counterSocPercent = 80.6f, voltageSocPercent = 78f, packV = 84.1f, compensatedPackV = 85.2f, dischargeA = 12f,
        currentSource = CurrentSource.CONTROLLER, integratedAh = 3.25, integratedWh = 270.0, learnDropPercent = 4.5f,
        usableFraction = 0.9f, atRest = false,
    )

    private fun modelledEnergy() = EnergyState(
        socPercent = 80.6f, usableAh = 20f, nominalPackV = 84f, remainingWh = 1350f, capacitySource = CapacitySource.ENTERED,
        learned = null, resistanceOhm = 0.12f, modelled = true, detail = detail(),
    )

    // ---- disconnected ------------------------------------------------------------------------

    @Test fun aDisconnectedVehicleShowsPersistedEnergyAndNotConnectedForLiveRows() {
        val record = EnergyRecord(
            EnergyMemory(LearnedCapacity(usableAh = 18.5f, weight = 1f, rides = 3), resistanceOhm = 0.09f, counter = PersistedCharge(61f, 1L)),
            seriesCells = 20, chemistry = Chemistry.LI_ION_NMC,
        )
        // Handed a live-looking snapshot on purpose: a disconnected vehicle must ignore it.
        val stale = VehicleData(energy = modelledEnergy(), motion = ControllerData(speedKmh = 55f, speedSource = SpeedSource.REPORTED, isConnected = true))
        val i = info(wheel, connected = false, data = stale, stored = record)

        val resistance = i.row(InfoSectionKind.ENERGY, InfoLabel.RESISTANCE)
        assertEquals("90.0" to InfoUnit.MILLIOHM, resistance.vu()); assertTrue(resistance.persisted)
        val learned = i.row(InfoSectionKind.ENERGY, InfoLabel.LEARNED_CAPACITY)
        assertEquals("18.5" to InfoUnit.AMP_HOUR, learned.vu()); assertTrue(learned.persisted)
        assertEquals("61.0" to InfoUnit.PERCENT, i.row(InfoSectionKind.ENERGY, InfoLabel.STORED_COUNTER).vu())

        assertEquals(InfoAbsence.NOT_CONNECTED, i.row(InfoSectionKind.ENERGY, InfoLabel.ENERGY_SOC).reason)
        assertEquals(InfoAbsence.NOT_CONNECTED, i.row(InfoSectionKind.CONTROLLERS, InfoLabel.SPEED).reason)
        assertEquals(InfoAbsence.NOT_CONNECTED, i.row(InfoSectionKind.BATTERIES, InfoLabel.VOLTAGE).reason)
        assertEquals(InfoAbsence.NOT_CONNECTED, i.row(InfoSectionKind.CONNECTIONS, InfoLabel.LINK_STATE).reason)
        assertFalse("55" in i.allValues().joinToString(), "a disconnected vehicle never shows the stale live speed")
        val series = i.row(InfoSectionKind.VEHICLE, InfoLabel.SERIES_CELLS)
        assertEquals(InfoChoice.SERIES_FROM_STORED, series.choice); assertEquals("20", series.value)
    }

    @Test fun nothingStoredAndNothingLiveIsNotEarnedNeverZero() {
        val i = info(wheel, connected = false)
        assertEquals(InfoAbsence.NOT_EARNED, i.row(InfoSectionKind.ENERGY, InfoLabel.RESISTANCE).reason)
        assertEquals(InfoAbsence.NOT_EARNED, i.row(InfoSectionKind.ENERGY, InfoLabel.LEARNED_CAPACITY).reason)
        assertEquals(InfoAbsence.NOT_SET, i.row(InfoSectionKind.VEHICLE, InfoLabel.SERIES_CELLS).reason)
    }

    // ---- a Begode T4: no duty, no motor temp -------------------------------------------------

    private fun t4Data(): VehicleData {
        val c = wheel.controllers.single()
        // Deliberately incoherent: a duty and a motor temperature that the flags say were never measured.
        val motion = ControllerData(
            speedKmh = 21f, speedSource = SpeedSource.REPORTED,
            dutyPercent = 37f, hasDuty = false,
            motorTempC = 66f, hasMotorTemp = false,
            escTempC = -999f,
            powerW = 4200f, hasPower = false,
            hasEnergyCounters = false, consumedWh = 777f,
            isConnected = true,
        )
        return VehicleData(
            packs = listOf(packState(wheel.packs.single(), BmsData(voltage = 84f, soc = 80.6f, socKnown = true, isConnected = true))),
            controllers = listOf(controllerState(c, motion)),
            motion = motion, energy = modelledEnergy(),
        )
    }

    @Test fun aT4ShowsNoDutyAndNoMotorTempWithTheReasonAndNeverTheFabricatedNumbers() {
        val i = info(wheel, data = t4Data())
        val duty = i.row(InfoSectionKind.CONTROLLERS, InfoLabel.DUTY)
        assertNull(duty.value); assertEquals(InfoAbsence.NOT_REPORTED, duty.reason)
        val motorTemp = i.row(InfoSectionKind.CONTROLLERS, InfoLabel.MOTOR_TEMP)
        assertNull(motorTemp.value); assertEquals(InfoAbsence.NO_SENSOR, motorTemp.reason)
        assertEquals(InfoAbsence.NO_SENSOR, i.row(InfoSectionKind.CONTROLLERS, InfoLabel.ESC_TEMP).reason)
        // hasPower = false beside powerW = 4200f; hasEnergyCounters = false beside consumedWh = 777f.
        assertNull(i.row(InfoSectionKind.CONTROLLERS, InfoLabel.POWER).value)
        assertEquals(InfoAbsence.NOT_REPORTED, i.row(InfoSectionKind.CONTROLLERS, InfoLabel.CONSUMED_WH).reason)
        val shown = i.allValues().joinToString("|")
        for (fabricated in listOf("37", "66", "4200", "777")) assertFalse(fabricated in shown, "$fabricated leaked into: $shown")
        assertEquals("21" to InfoUnit.SPEED, i.row(InfoSectionKind.CONTROLLERS, InfoLabel.SPEED).vu())
    }

    @Test fun anIncoherentHasDutyFalseNeverShows37() {
        val c = ControllerData(dutyPercent = 37f, hasDuty = false, isConnected = true)
        val vehicle = bike
        val data = VehicleData(controllers = listOf(controllerState(vehicle.controllers.single(), c)), motion = c)
        val duty = info(vehicle, data = data).row(InfoSectionKind.CONTROLLERS, InfoLabel.DUTY)
        assertNull(duty.value); assertEquals(InfoAbsence.NOT_REPORTED, duty.reason)
        // And when the flag says it was measured, the number is shown.
        val measured = c.copy(hasDuty = true)
        val shown = info(vehicle, data = data.copy(controllers = listOf(controllerState(vehicle.controllers.single(), measured)), motion = measured))
        assertEquals("37.0" to InfoUnit.PERCENT, shown.row(InfoSectionKind.CONTROLLERS, InfoLabel.DUTY).vu())
    }

    @Test fun aProtocolThatCannotReportDutySaysSoAtTheProtocolLevel() {
        val kelly = Vehicle("k", "K", "scooter", packs = listOf(Pack(0, "P", BmsType.JBD_BMS, "PP")),
            controllers = listOf(Controller(0, "Kelly", ControllerType.KELLY, "KK")), chemistry = Chemistry.LI_ION_NMC, createdAt = epoch)
        val c = ControllerData(dutyPercent = 12f, hasDuty = true, isConnected = true)
        val d = VehicleData(controllers = listOf(controllerState(kelly.controllers.single(), c)), motion = c)
        assertEquals(InfoAbsence.PROTOCOL_LACKS, info(kelly, data = d).row(InfoSectionKind.CONTROLLERS, InfoLabel.DUTY).reason)
    }

    // ---- who computes the SoC ----------------------------------------------------------------

    @Test fun anAntPackSocIsComputedByTheBmsAndAT4ByTheModel() {
        val ant = bike.packs.single()
        val antData = VehicleData(packs = listOf(packState(ant, BmsData(voltage = 72f, soc = 55f, socKnown = true, isConnected = true))),
            energy = EnergyState(55f, 20f, 72f, 1000f, CapacitySource.REPORTED, null, null, modelled = false))
        val antRow = info(bike, data = antData).row(InfoSectionKind.BATTERIES, InfoLabel.SOC)
        assertEquals(InfoChoice.SOC_BY_BMS, antRow.choice); assertEquals("55.0" to InfoUnit.PERCENT, antRow.vu())

        val t4Row = info(wheel, data = t4Data()).row(InfoSectionKind.BATTERIES, InfoLabel.SOC)
        assertEquals(InfoChoice.SOC_BY_MODEL, t4Row.choice); assertEquals("80.6" to InfoUnit.PERCENT, t4Row.vu())
    }

    @Test fun aModelledSocThatIsNotSeededYetIsNotEarnedNotZero() {
        val data = t4Data().let { d ->
            d.copy(packs = listOf(packState(wheel.packs.single(), BmsData(voltage = 84f, soc = 0f, socKnown = false, isConnected = true))))
        }
        val row = info(wheel, data = data).row(InfoSectionKind.BATTERIES, InfoLabel.SOC)
        assertNull(row.value); assertEquals(InfoAbsence.NOT_EARNED, row.reason); assertEquals(InfoChoice.SOC_BY_MODEL, row.choice)
    }

    @Test fun aBeGodeWithoutTheModelFallsBackToTheVoltageEstimate() {
        val data = t4Data().copy(energy = null)
        assertEquals(InfoChoice.SOC_BY_VOLTAGE, info(wheel, data = data).row(InfoSectionKind.BATTERIES, InfoLabel.SOC).choice)
    }

    @Test fun anOfflinePackIsOfflineNotZero() {
        val ant = bike.packs.single()
        val data = VehicleData(packs = listOf(packState(ant, BmsData(voltage = 0f, isConnected = false), online = false)))
        val i = info(bike, data = data)
        assertEquals(InfoAbsence.OFFLINE, i.row(InfoSectionKind.BATTERIES, InfoLabel.VOLTAGE).reason)
        assertEquals(InfoAbsence.OFFLINE, i.row(InfoSectionKind.BATTERIES, InfoLabel.SOC).reason)
    }

    // ---- energy ------------------------------------------------------------------------------

    @Test fun theModelsWorkingShowsOnlyWhileTheModelOwnsTheSoc() {
        val modelled = info(wheel, data = t4Data())
        assertEquals("80.6" to InfoUnit.PERCENT, modelled.row(InfoSectionKind.ENERGY, InfoLabel.COUNTER_SOC).vu())
        assertEquals(InfoChoice.CURRENT_FROM_CONTROLLER, modelled.row(InfoSectionKind.ENERGY, InfoLabel.CURRENT_SOURCE).choice)
        val smart = info(bike, data = VehicleData(energy = EnergyState(55f, 20f, 72f, 1000f, CapacitySource.REPORTED, null, null, modelled = false)))
        assertTrue(smart.section(InfoSectionKind.ENERGY).single().rows.none { it.label == InfoLabel.COUNTER_SOC })
    }

    @Test fun anAbsentMeasuredCurrentInTheModelIsNotEarnedNeverZero() {
        val d = t4Data().let { it.copy(energy = it.energy!!.copy(detail = detail().copy(dischargeA = null, currentSource = CurrentSource.NONE))) }
        val row = info(wheel, data = d).row(InfoSectionKind.ENERGY, InfoLabel.DISCHARGE_A)
        assertNull(row.value); assertNotNull(row.reason)
    }

    // ---- connections and facts ---------------------------------------------------------------

    private fun link(facts: List<DeviceFact> = emptyList(), at: Long? = 99_000L, fps: Float? = 9.8f) =
        BleDiagnosticLink("WW", "GotWay", "BEGODE", null, null, null, null, facts = facts, framesPerSecond = fps, reconnects = 2, statsAtEpochMillis = at)

    @Test fun aLinkWithNoFactsHasNoFactsSection() {
        val i = info(wheel, data = t4Data(), links = listOf(link()))
        assertTrue(i.section(InfoSectionKind.DEVICE_FACTS).isEmpty())
        assertEquals("9.8", i.row(InfoSectionKind.CONNECTIONS, InfoLabel.FRAMES_PER_SECOND).value)
        assertEquals("2", i.row(InfoSectionKind.CONNECTIONS, InfoLabel.RECONNECTS).value)
        assertEquals(InfoAbsence.NOT_REPORTED, i.row(InfoSectionKind.CONNECTIONS, InfoLabel.FIRMWARE).reason)
    }

    @Test fun aLinkWithFactsGetsOneSectionAndTheDecodersTextIsShownVerbatim() {
        val facts = listOf(
            DeviceFact(DeviceFactKey.ODOMETER_KM, "1234.500", "km"),
            DeviceFact(DeviceFactKey.WHEEL_ALERTS, "Overspeed, Low voltage"),
            DeviceFact(DeviceFactKey.BRANCH_VOLTAGE_V, "84.1", "V", index = 1),
        )
        val sections = info(wheel, data = t4Data(), links = listOf(link(facts))).section(InfoSectionKind.DEVICE_FACTS)
        val rows = sections.single().rows
        assertEquals("1234.500" to InfoUnit.KILOMETER, rows[0].vu())
        assertEquals("Overspeed, Low voltage", rows[1].value)
        assertEquals(DeviceFactKey.WHEEL_ALERTS, rows[1].fact)
        assertEquals(1, rows[2].index)
    }

    @Test fun linkStatsOlderThanThreeSecondsAreUnknown() {
        val facts = listOf(DeviceFact(DeviceFactKey.ODOMETER_KM, "1234.500", "km"))
        // Notification at t=96_900, now=100_000: 3.1 s of silence on a link that is still ONLINE.
        val stale = info(wheel, data = t4Data(), links = listOf(link(facts, at = 96_900L)), now = 100_000L)
        val fps = stale.row(InfoSectionKind.CONNECTIONS, InfoLabel.FRAMES_PER_SECOND)
        assertNull(fps.value); assertEquals(InfoAbsence.STALE, fps.reason)
        val fact = stale.section(InfoSectionKind.DEVICE_FACTS).single().rows.single()
        assertNull(fact.value); assertEquals(InfoAbsence.STALE, fact.reason)
        // Exactly at the limit is still fresh.
        val edge = info(wheel, data = t4Data(), links = listOf(link(facts, at = 97_000L)), now = 100_000L)
        assertEquals("9.8", edge.row(InfoSectionKind.CONNECTIONS, InfoLabel.FRAMES_PER_SECOND).value)
        // A link that never stamped its stats has none to trust.
        val never = info(wheel, data = t4Data(), links = listOf(link(facts, at = null)))
        assertEquals(InfoAbsence.STALE, never.row(InfoSectionKind.CONNECTIONS, InfoLabel.FRAMES_PER_SECOND).reason)
    }

    @Test fun aLinkWithNoRateYetIsNotYetNotZero() {
        val i = info(wheel, data = t4Data(), links = listOf(link(fps = null)))
        assertEquals(InfoAbsence.NOT_YET, i.row(InfoSectionKind.CONNECTIONS, InfoLabel.FRAMES_PER_SECOND).reason)
    }

    @Test fun theSeriesCellCountNamesItsSource() {
        val proven = info(wheel, data = t4Data(), links = listOf(link(listOf(DeviceFact(DeviceFactKey.SERIES_CELLS, "24")))))
        val row = proven.row(InfoSectionKind.VEHICLE, InfoLabel.SERIES_CELLS)
        assertEquals("24", row.value); assertEquals(InfoChoice.SERIES_FROM_PROTOCOL, row.choice)
        val configured = info(bike).row(InfoSectionKind.VEHICLE, InfoLabel.SERIES_CELLS)
        assertEquals("20", configured.value); assertEquals(InfoChoice.SERIES_FROM_PACK, configured.choice)
    }

    // ---- range and alerts --------------------------------------------------------------------

    @Test fun anUnearnedRangeSaysWhy() {
        val r = RangeDetail(tripWhPerKm = null, recentWhPerKm = null, blendedWhPerKm = null, tripKm = 0.4, unearnedReason = RangeUnearnedReason.TOO_SHORT)
        val i = info(wheel, data = t4Data(), range = r)
        assertEquals(InfoAbsence.RANGE_TOO_SHORT, i.row(InfoSectionKind.RANGE, InfoLabel.RANGE_STATUS).reason)
        assertEquals(InfoAbsence.RANGE_TOO_SHORT, i.row(InfoSectionKind.RANGE, InfoLabel.BLENDED_CONSUMPTION).reason)
        assertEquals("0.40" to InfoUnit.DISTANCE, i.row(InfoSectionKind.RANGE, InfoLabel.RANGE_TRIP_KM).vu())
        assertEquals(InfoAbsence.NOT_CONNECTED, info(wheel, connected = false).row(InfoSectionKind.RANGE, InfoLabel.RANGE_STATUS).reason)
    }

    @Test fun anEarnedRangeInImperialUnits() {
        val r = RangeDetail(20.0, 25.0, 22.0, tripKm = 16.09344, unearnedReason = null)
        val i = info(wheel, data = t4Data(), range = r, rangeEstimate = RangeEstimate(km = 32.18688f, whPerKm = 22.0), units = UnitSystem.IMPERIAL)
        // The estimate row carries the RANGE (20 mi), not the trip distance (10 mi) in the row beside it.
        assertEquals("20.0" to InfoUnit.DISTANCE, i.row(InfoSectionKind.RANGE, InfoLabel.RANGE_STATUS).vu())
        assertEquals("10.00" to InfoUnit.DISTANCE, i.row(InfoSectionKind.RANGE, InfoLabel.RANGE_TRIP_KM).vu())
        assertEquals("35.4" to InfoUnit.CONSUMPTION, i.row(InfoSectionKind.RANGE, InfoLabel.BLENDED_CONSUMPTION).vu())
        assertEquals("13" to InfoUnit.SPEED, i.row(InfoSectionKind.CONTROLLERS, InfoLabel.SPEED).vu())
    }

    @Test fun anEarnedRangeShowsItsKmNotTheTripDistance() {
        val r = RangeDetail(20.0, 25.0, 22.0, tripKm = 2.74, unearnedReason = null)
        val i = info(wheel, data = t4Data(), range = r, rangeEstimate = RangeEstimate(km = 41.6f, whPerKm = 22.0))
        assertEquals("41.6" to InfoUnit.DISTANCE, i.row(InfoSectionKind.RANGE, InfoLabel.RANGE_STATUS).vu())
        assertEquals("2.74" to InfoUnit.DISTANCE, i.row(InfoSectionKind.RANGE, InfoLabel.RANGE_TRIP_KM).vu())
    }

    @Test fun anEarnedDetailWithoutAnEstimateIsNotEarnedNotABlankOk() {
        // Deliberately incoherent: a detail that says "earned" (no reason) beside no estimate.
        val r = RangeDetail(20.0, 25.0, 22.0, tripKm = 2.74, unearnedReason = null)
        val row = info(wheel, data = t4Data(), range = r, rangeEstimate = null).row(InfoSectionKind.RANGE, InfoLabel.RANGE_STATUS)
        assertNull(row.value); assertNull(row.choice); assertEquals(InfoAbsence.NOT_EARNED, row.reason)
    }

    @Test fun anUnearnedReasonWinsOverAnEstimate() {
        // Deliberately incoherent: a reason AND an estimate. The reason is the detail's verdict.
        val r = RangeDetail(null, null, null, tripKm = 0.4, unearnedReason = RangeUnearnedReason.TOO_SHORT)
        val row = info(wheel, data = t4Data(), range = r, rangeEstimate = RangeEstimate(30f, 20.0)).row(InfoSectionKind.RANGE, InfoLabel.RANGE_STATUS)
        assertNull(row.value); assertEquals(InfoAbsence.RANGE_TOO_SHORT, row.reason)
    }

    @Test fun deviceFactBooleansAndTokensBecomeChoicesAndUnknownTextStaysVerbatim() {
        val facts = listOf(
            DeviceFact(DeviceFactKey.SMART_BMS_SEEN, "true"),
            DeviceFact(DeviceFactKey.SMART_BMS_SEEN, "false", index = 1),
            DeviceFact(DeviceFactKey.SERIES_CELLS_PROOF, "branch_ratio"),
            DeviceFact(DeviceFactKey.SERIES_CELLS_PROOF, "cells", index = 1),
            DeviceFact(DeviceFactKey.SERIES_CELLS_PROOF, "none", index = 2),
            DeviceFact(DeviceFactKey.SERIES_CELLS_PROOF, "something_new", index = 3),
            DeviceFact(DeviceFactKey.SERIES_CELLS, "24"),
        )
        val rows = info(wheel, data = t4Data(), links = listOf(link(facts))).section(InfoSectionKind.DEVICE_FACTS).single().rows
        assertEquals(InfoChoice.YES, rows[0].choice); assertNull(rows[0].value)
        assertEquals(InfoChoice.NO, rows[1].choice)
        assertEquals(InfoChoice.SERIES_PROOF_BRANCH_RATIO, rows[2].choice); assertNull(rows[2].value)
        assertEquals(InfoChoice.SERIES_PROOF_CELLS, rows[3].choice)
        assertEquals(InfoChoice.SERIES_PROOF_NONE, rows[4].choice)
        assertNull(rows[5].choice); assertEquals("something_new", rows[5].value)
        assertNull(rows[6].choice); assertEquals("24", rows[6].value)
    }

    @Test fun aWordTokenOnAFactThatNeverCarriesOneIsNotMistakenForAChoice() {
        val rows = info(wheel, data = t4Data(), links = listOf(link(listOf(DeviceFact(DeviceFactKey.WHEEL_ALERTS, "true")))))
            .section(InfoSectionKind.DEVICE_FACTS).single().rows
        assertNull(rows.single().choice); assertEquals("true", rows.single().value)
    }

    @Test fun alertsAreArmedUnavailableWithReasonOrActive() {
        val configured = wheel.copy(motionAlerts = listOf(
            AlertRule(MotionAlertKind.SPEED, listOf(AlertLevel(20f), AlertLevel(40f))),
            AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f))),
        ))
        val i = info(configured, data = t4Data())
        // Speed 21 km/h is past level 1 of 2.
        val speed = i.row(InfoSectionKind.ALERTS, InfoLabel.ALERT_SPEED)
        assertEquals(InfoChoice.ALERT_ACTIVE, speed.choice); assertEquals("1", speed.value)
        // Duty is unavailable: hasDuty = false (dutyPercent 37 is never compared).
        assertEquals(InfoAbsence.NOT_REPORTED, i.row(InfoSectionKind.ALERTS, InfoLabel.ALERT_DUTY).reason)
        assertEquals(InfoAbsence.NO_SENSOR, i.row(InfoSectionKind.ALERTS, InfoLabel.ALERT_MOTOR_TEMP).reason)
        // Not connected: the same kinds are unknown, not "missing hardware".
        val away = info(configured, connected = false)
        assertEquals(InfoAbsence.NOT_CONNECTED, away.row(InfoSectionKind.ALERTS, InfoLabel.ALERT_SPEED).reason)
    }

    @Test fun anArmedAlertBelowItsThresholdIsArmedNotActive() {
        val configured = wheel.copy(motionAlerts = listOf(AlertRule(MotionAlertKind.SPEED, listOf(AlertLevel(40f)))))
        val speed = info(configured, data = t4Data()).row(InfoSectionKind.ALERTS, InfoLabel.ALERT_SPEED)
        assertEquals(InfoChoice.ALERT_ARMED, speed.choice); assertNull(speed.value)
        val off = wheel.copy(motionAlerts = listOf(AlertRule(MotionAlertKind.SPEED, emptyList())))
        assertEquals(InfoChoice.ALERT_OFF, info(off, data = t4Data()).row(InfoSectionKind.ALERTS, InfoLabel.ALERT_SPEED).choice)
    }

    @Test fun aVehicleWithNoControllerHasNoControllerSectionAndAlertsSayWhy() {
        val packOnly = bike.copy(controllers = emptyList())
        val i = info(packOnly, connected = false)
        assertTrue(i.section(InfoSectionKind.CONTROLLERS).isEmpty())
        assertEquals(InfoAbsence.NO_CONTROLLER, i.row(InfoSectionKind.ALERTS, InfoLabel.ALERT_DUTY).reason)
    }

    @Test fun unitsAreEnumsAndTheValueIsDigitsOnly() {
        val i = info(wheel, data = t4Data())
        for (row in i.sections.flatMap { it.rows }.filter { it.unit != null }) {
            assertTrue(row.value!!.all { it.isDigit() || it in "-.," || it == ' ' }, "${row.label} value has words: ${row.value}")
        }
        val v = i.row(InfoSectionKind.BATTERIES, InfoLabel.VOLTAGE)
        assertEquals("84.00" to InfoUnit.VOLT, v.vu())
    }

    @Test fun aNewPackReportsZeroCyclesAsZeroAndAntKeepsItsAhUnderItsOwnLabel() {
        val newPack = bike.copy(packs = listOf(Pack(0, "P", BmsType.JBD_BMS, "BB")))
        val d = VehicleData(packs = listOf(packState(newPack.packs.single(), BmsData(voltage = 72f, numCycles = 0, capacity = 0f, isConnected = true))))
        val i = info(newPack, data = d)
        assertEquals("0" to null, i.row(InfoSectionKind.BATTERIES, InfoLabel.CYCLES).vu())
        assertEquals("0.0" to InfoUnit.AMP_HOUR, i.row(InfoSectionKind.BATTERIES, InfoLabel.CAPACITY).vu())

        val ant = VehicleData(packs = listOf(packState(bike.packs.single(), BmsData(voltage = 72f, cycleCapacityAh = 123f, isConnected = true))))
        val a = info(bike, data = ant)
        assertEquals(InfoAbsence.PROTOCOL_LACKS, a.row(InfoSectionKind.BATTERIES, InfoLabel.CYCLES).reason)
        assertEquals("123" to InfoUnit.AMP_HOUR, a.row(InfoSectionKind.BATTERIES, InfoLabel.CYCLE_CAPACITY).vu())
    }

    @Test fun sectionsComeInTheDocumentedOrder() {
        val facts = listOf(DeviceFact(DeviceFactKey.ODOMETER_KM, "1", "km"))
        val kinds = info(wheel, data = t4Data(), links = listOf(link(facts))).sections.map { it.kind }
        assertEquals(
            listOf(InfoSectionKind.VEHICLE, InfoSectionKind.CONNECTIONS, InfoSectionKind.BATTERIES, InfoSectionKind.CONTROLLERS, InfoSectionKind.PWM,
                InfoSectionKind.ENERGY, InfoSectionKind.RANGE, InfoSectionKind.ALERTS, InfoSectionKind.DEVICE_FACTS),
            kinds,
        )
    }
}
