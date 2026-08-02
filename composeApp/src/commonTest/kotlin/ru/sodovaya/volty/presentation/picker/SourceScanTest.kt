package ru.sodovaya.volty.presentation.picker

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `SourceScan.kt` — what a scan found, in the terms the composer needs (G2 Task 5).
 *
 * The whole file is pure, which is deliberate: the sheet that renders a scan is
 * a Compose renderer and this repo cannot test one, so every judgement it
 * appears to make is made here.
 */
class SourceScanTest {

    private fun device(
        address: String = "AA:BB",
        name: String? = null,
        bmsType: BmsType? = null,
        controllerType: ControllerType? = null,
        rssi: Int = -60
    ) = DiscoveredDevice(
        address = address,
        name = name,
        rssi = rssi,
        bmsType = bmsType,
        controllerType = controllerType
    )

    // ------------------------------------------------------------------
    // Rider-visible identity (L Task 3)
    // ------------------------------------------------------------------

    /**
     * Deleting the address from a named label recreates the defect: several
     * nearby BMS boxes can share a name, but their BLE addresses cannot.
     */
    @Test
    fun `a named scan device label keeps its name and full address`() {
        assertEquals(
            ScanDeviceLabel(title = "ANT BMS", address = "C3:5E:E2:61:94:AE"),
            device(name = "ANT BMS", address = "C3:5E:E2:61:94:AE").scanDeviceLabel()
        )
    }

    @Test
    fun `an unnamed scan device label is its full address`() {
        assertEquals(
            ScanDeviceLabel(title = "C3:5E:E2:61:94:AE", address = null),
            device(address = "C3:5E:E2:61:94:AE").scanDeviceLabel()
        )
    }

    /** The regression itself: the old last-four-character label made this false. */
    @Test
    fun `two otherwise identical scan devices have distinct address labels`() {
        val first = device(name = "BMS", address = "AA:BB:CC:DD:EE:01")
        val second = device(name = "BMS", address = "AA:BB:CC:DD:EE:02")

        assertNotEquals(first.scanDeviceLabel(), second.scanDeviceLabel())
    }

    @Test
    fun `signal proximity becomes warmer when the device is nearby`() {
        assertEquals(SignalProximity.WARMER, device(rssi = -70).signalProximity())
        assertEquals(SignalProximity.COLDER, device(rssi = -71).signalProximity())
    }

    // ------------------------------------------------------------------
    // Roles (G §7)
    // ------------------------------------------------------------------

    @Test
    fun `a detected VESC is a controller`() {
        assertEquals(
            SourceRole.CONTROLLER,
            device(controllerType = ControllerType.VESC).sourceRole()
        )
    }

    @Test
    fun `a detected JK BMS is a battery`() {
        assertEquals(SourceRole.BATTERY, device(bmsType = BmsType.JK_BMS).sourceRole())
    }

    /**
     * The load-bearing case, and the reason roles are classified on the
     * PROTOCOL rather than on which of the two detector fields is set.
     *
     * `BmsTypeDetector.detect` matches a Begode's name before the controller
     * detector ever runs, so a wheel arrives as `bmsType = BEGODE` with
     * `controllerType = null`. Classifying on the fields would call the one
     * device `G §3` flow 3 exists for a plain battery, and hide "+ Wheel" on it.
     */
    @Test
    fun `a Begode is BOTH, whichever field detection filled in`() {
        assertEquals(SourceRole.BOTH, device(bmsType = BmsType.BEGODE).sourceRole())
        assertEquals(SourceRole.BOTH, device(controllerType = ControllerType.BEGODE).sourceRole())
    }

    @Test
    fun `an unrecognised device is UNKNOWN`() {
        assertEquals(SourceRole.UNKNOWN, device().sourceRole())
    }

    /**
     * Every non-Begode controller kind is a controller and nothing else, and
     * every non-Begode battery kind is a battery and nothing else — asserted
     * over the WHOLE of both enums rather than one example each, because
     * `sourceRole` derives from `ProtocolKind` and a new type joins that
     * derivation silently.
     */
    @Test
    fun `every controller and battery type classifies, and only Begode is BOTH`() {
        for (t in ControllerType.entries) {
            val expected = if (t == ControllerType.BEGODE) SourceRole.BOTH else SourceRole.CONTROLLER
            assertEquals(expected, device(controllerType = t).sourceRole(), "controller $t")
        }
        for (t in BmsType.entries) {
            val expected = if (t == BmsType.BEGODE) SourceRole.BOTH else SourceRole.BATTERY
            assertEquals(expected, device(bmsType = t).sourceRole(), "battery $t")
        }
    }

    // ------------------------------------------------------------------
    // What may be added
    // ------------------------------------------------------------------

    /**
     * Ordered, never filtered — detection is a hint, not a lock (the same rule
     * the picker's type sheet follows), so no device is refused an add.
     */
    @Test
    fun `every device offers all three adds`() {
        val cases = listOf(
            device(controllerType = ControllerType.VESC),
            device(bmsType = BmsType.JK_BMS),
            device(bmsType = BmsType.BEGODE),
            device()
        )
        for (d in cases) {
            assertEquals(
                ScannedAdd.entries.toSet(),
                addsFor(d).toSet(),
                "${d.sourceRole()} must offer every add"
            )
            assertEquals(3, addsFor(d).size, "${d.sourceRole()} must offer each add once")
        }
    }

    @Test
    fun `the detected role leads`() {
        assertEquals(ScannedAdd.WHEEL, addsFor(device(bmsType = BmsType.BEGODE)).first())
        assertEquals(ScannedAdd.CONTROLLER, addsFor(device(controllerType = ControllerType.VESC)).first())
        assertEquals(ScannedAdd.BATTERY, addsFor(device(bmsType = BmsType.JK_BMS)).first())
        assertEquals(ScannedAdd.CONTROLLER, addsFor(device()).first())
    }

    // ------------------------------------------------------------------
    // The types an add starts from
    // ------------------------------------------------------------------

    /**
     * A wheel add must produce a Begode controller beside a Begode pack — not a
     * VESC beside a JK, which is what a naive "detected type or default" would
     * give for a device only the BATTERY detector recognised.
     */
    @Test
    fun `a Begode seeds both halves as Begode`() {
        val d = device(bmsType = BmsType.BEGODE)
        assertEquals(ControllerType.BEGODE, d.addControllerType())
        assertEquals(BmsType.BEGODE, d.addBmsType())
    }

    @Test
    fun `a VESC seeds a VESC controller and falls back for its battery half`() {
        val d = device(controllerType = ControllerType.VESC)
        assertEquals(ControllerType.VESC, d.addControllerType())
        // ProtocolKind.VESC maps to no BmsType at all, so the form's own default
        // is the honest answer rather than VESC_BMS (which is gateway-hosted
        // only and would raise `HostlessVescBms` on a direct add).
        assertEquals(BmsType.JK_BMS, d.addBmsType())
    }

    @Test
    fun `an unrecognised device seeds the two defaults`() {
        val d = device()
        assertEquals(ControllerType.VESC, d.addControllerType())
        assertEquals(BmsType.JK_BMS, d.addBmsType())
    }

    /**
     * Exhaustive rather than by example: for EVERY battery kind, the controller
     * half is either that kind's own controller (Begode) or the VESC fallback —
     * never some third type — and the battery half is always the detected one.
     */
    @Test
    fun `seeding is total over both enums`() {
        for (t in BmsType.entries) {
            val d = device(bmsType = t)
            assertEquals(t, d.addBmsType(), "battery half of $t")
            val expected = if (t == BmsType.BEGODE) ControllerType.BEGODE else ControllerType.VESC
            assertEquals(expected, d.addControllerType(), "controller half of $t")
        }
        for (t in ControllerType.entries) {
            val d = device(controllerType = t)
            assertEquals(t, d.addControllerType(), "controller half of $t")
            val expected = if (t == ControllerType.BEGODE) BmsType.BEGODE else BmsType.JK_BMS
            assertEquals(expected, d.addBmsType(), "battery half of $t")
        }
    }

    // ------------------------------------------------------------------
    // Accumulating a scan
    // ------------------------------------------------------------------

    @Test
    fun `a new address appends`() {
        val out = listOf(device(address = "A")).withScanHit(device(address = "B"))
        assertEquals(listOf("A", "B"), out.map { it.address })
    }

    @Test
    fun `a repeat that adds a detection replaces the stored hit`() {
        val first = device(address = "A")
        val better = device(address = "A", controllerType = ControllerType.VESC)
        val out = listOf(first).withScanHit(better)
        assertEquals(1, out.size)
        assertEquals(ControllerType.VESC, out.single().controllerType)
    }

    @Test
    fun `a repeat that adds a name replaces the stored hit`() {
        val out = listOf(device(address = "A")).withScanHit(device(address = "A", name = "uBox"))
        assertEquals("uBox", out.single().name)
    }

    /**
     * A BLE scan re-reports the same peripheral continuously. A fold that
     * rewrote the list every time would churn the sheet under the rider's
     * finger; identity is asserted, not just equality, so a rewrite that
     * happened to produce an equal list still fails.
     */
    @Test
    fun `a repeat that adds nothing is not written`() {
        val start = listOf(device(address = "A", name = "uBox", controllerType = ControllerType.VESC))
        val out = start.withScanHit(
            device(address = "A", name = "uBox", controllerType = ControllerType.VESC, rssi = -90)
        )
        assertSame(start, out)
    }

    @Test
    fun `order is first-appearance and a replacement keeps its slot`() {
        val out = listOf(device(address = "A"), device(address = "B"), device(address = "C"))
            .withScanHit(device(address = "A", controllerType = ControllerType.VESC))
        assertEquals(listOf("A", "B", "C"), out.map { it.address })
        assertTrue(out.first().controllerType != null)
    }
}
