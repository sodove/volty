package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.CanScanRefusal
import ru.sodovaya.volty.domain.repository.CanScanRefusedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * `KableBmsRepository.discoverCanIds` — the four refusals (G2 Task 5).
 *
 * Only the refusals. The success path needs a live `ConnectionSession`, which
 * needs a real Kable peripheral, so it is **not reachable from commonTest at
 * all** — see this task's report for what that leaves to the product owner's
 * hardware. What IS reachable is every reason we decline to ask, and each has
 * its own message because each is a different thing a rider can fix.
 *
 * Owned through [bleRepositoryTest], the only sanctioned way to hold a
 * repository: its reconnect loop is an unbounded delayed loop that wedges the
 * build rather than failing it.
 */
@OptIn(ExperimentalTime::class)
class KableBmsRepositoryCanDiscoveryTest {

    private fun vehicle(
        controllers: List<Controller> = listOf(
            Controller(index = 0, label = "Head unit", controllerType = ControllerType.VESC, address = "HU:01")
        ),
        packs: List<Pack> = emptyList()
    ) = Vehicle(
        id = "v1",
        name = "Scooter",
        iconKey = "scooter",
        packs = packs,
        controllers = controllers,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(1_700_000_000)
    )

    private fun why(r: Result<List<Int>>): String =
        r.exceptionOrNull()?.message ?: "SUCCESS(${r.getOrNull()})"

    /**
     * The TYPED reason, which is what the screen renders - the message above is
     * only a log line. Asserted alongside it everywhere, because a refusal that
     * carried the right sentence and the wrong reason would print the wrong
     * thing in Russian and no message assertion could see it.
     */
    private fun reason(r: Result<List<Int>>): CanScanRefusal? =
        (r.exceptionOrNull() as? CanScanRefusedException)?.refusal

    @Test
    fun `an address with no link at all is refused`() = bleRepositoryTest { repo ->
        val r = repo.discoverCanIds("HU:01")
        assertTrue(r.isFailure)
        assertEquals(CanScanRefusal.NOT_CONNECTED, reason(r), why(r))
        assertTrue(why(r).contains("Not connected"), why(r))
    }

    /**
     * A link that is installed but has not come up cannot be asked — and this
     * is the state a rider hits while the head unit is still connecting, so it
     * must not read as "no such device".
     */
    @Test
    fun `a link that is not online yet is refused`() = bleRepositoryTest { repo ->
        val v = vehicle()
        repo.installLinksForTest(v, "HU:01", null)
        val r = repo.discoverCanIds("HU:01")
        assertTrue(r.isFailure)
        assertEquals(CanScanRefusal.LINK_NOT_READY, reason(r), why(r))
        assertTrue(why(r).contains("not online"), why(r))
    }

    /**
     * `PING_CAN` is a VESC command. A JK BMS has no bus, and telling the rider
     * that is more useful than a timeout.
     */
    @Test
    fun `a live link that does not speak VESC is refused with its kind`() = bleRepositoryTest { repo ->
        val v = vehicle(
            controllers = emptyList(),
            packs = listOf(Pack(index = 0, label = "P", bmsType = BmsType.JK_BMS, bmsAddress = "JK:01"))
        )
        repo.installLinksForTest(v, "JK:01", BmsType.JK_BMS)
        repo.markLinkOnlineForTest("JK:01")
        val r = repo.discoverCanIds("JK:01")
        assertTrue(r.isFailure)
        assertEquals(CanScanRefusal.NO_CAN_BUS, reason(r), why(r))
        assertTrue(why(r).contains("JK"), why(r))
        assertTrue(why(r).contains("no CAN bus"), why(r))
    }

    /**
     * An online VESC link with no session behind it — the window between the
     * fold saying "up" and a session existing. It gets past the kind check, so
     * it proves the kind check is not what refuses every case above.
     */
    @Test
    fun `a live VESC link with no session is refused`() = bleRepositoryTest { repo ->
        val v = vehicle()
        repo.installLinksForTest(v, "HU:01", null)
        repo.markLinkOnlineForTest("HU:01")
        val r = repo.discoverCanIds("HU:01")
        assertTrue(r.isFailure)
        // Same reason as "not online": to a rider both are "the link is not up
        // yet". The two stay distinct in the message, for logs and for this test.
        assertEquals(CanScanRefusal.LINK_NOT_READY, reason(r), why(r))
        assertTrue(why(r).contains("no session"), why(r))
    }

    /**
     * **The shape the review's Important 1 was about, from the other side.**
     *
     * A lone hosted `VESC_BMS` pack resolves — through the real `planLinks` —
     * to a link of kind `VESC_BMS`, and this refuses it. The composer used to
     * offer a scan on exactly this shape, so the button appeared and the tap
     * could only ever fail; both layers now read `ProtocolKind.hasCanBus`, and
     * this is the half of that fact which lives here.
     *
     * Without it `VESC_BMS -> false` in `hasCanBus` is indistinguishable from
     * `true`: `canScanTarget` looks only at controllers, and no `ControllerType`
     * maps to `VESC_BMS`.
     */
    @Test
    fun `a lone hosted VESC_BMS cannot even become a link, let alone a gateway`() = bleRepositoryTest { repo ->
        val v = vehicle(
            controllers = emptyList(),
            packs = listOf(Pack(index = 0, label = "P", bmsType = BmsType.VESC_BMS, bmsAddress = "HU:01"))
        )
        // The real planner calls it a VESC_BMS link…
        assertEquals(
            ProtocolKind.VESC_BMS,
            planLinks(v.packs, v.controllers).single().protocolKind
        )
        // …and `createProtocol` refuses to build one at all, so it can never be
        // online to be scanned. THAT is why `hasCanBus` answers false for it,
        // and why offering a scan on this shape produced a button that could
        // only ever fail.
        val thrown = assertFailsWith<IllegalStateException> {
            repo.installLinksForTest(v, "HU:01", BmsType.VESC_BMS)
        }
        assertTrue(thrown.message.orEmpty().contains("VESC BMS"), thrown.message.orEmpty())
    }

    /**
     * The single statement of which links have a bus, over the WHOLE enum —
     * because it is now read by two layers and a widening would quietly widen
     * both at once, which is precisely the failure mode that made this fact
     * shared in the first place.
     */
    @Test
    fun `only VESC has a CAN bus`() {
        for (kind in ProtocolKind.entries) {
            assertEquals(kind == ProtocolKind.VESC, kind.hasCanBus, "hasCanBus for $kind")
        }
    }

    /** Every refusal names the address it is about, so a two-link vehicle is unambiguous. */
    @Test
    fun `each refusal names its own link`() = bleRepositoryTest { repo ->
        val v = vehicle(
            packs = listOf(Pack(index = 0, label = "P", bmsType = BmsType.ANT_BMS, bmsAddress = "AN:01"))
        )
        repo.installLinksForTest(v, "HU:01", null)
        repo.markLinkOnlineForTest("AN:01")
        assertEquals(2, repo.linkCountForTest())
        assertTrue(why(repo.discoverCanIds("HU:01")).contains("HU:01"))
        assertTrue(why(repo.discoverCanIds("AN:01")).contains("AN:01"))
        // And the two are refused for DIFFERENT reasons: one is not up, the
        // other is up but has no bus.
        assertTrue(why(repo.discoverCanIds("HU:01")).contains("not online"))
        assertTrue(why(repo.discoverCanIds("AN:01")).contains("no CAN bus"))
    }
}
