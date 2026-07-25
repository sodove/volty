package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Part C §5, the planning half: which links may be released to a head unit.
 *
 * These drive [planAliasHandoffs] directly rather than through the repository,
 * because every case below is a REFUSAL — a shape that must plan nothing — and
 * a refusal is far easier to state (and to read) as "this spec list yields an
 * empty plan" than as "connect a contrived vehicle and observe that nothing
 * happened". The happy path is covered end-to-end in
 * [KableBmsRepositoryAliasHandoffTest]; each refusal here has a matching
 * sentence in the function's KDoc explaining what it would cost the rider.
 */
class AliasHandoffTest {

    private companion object {
        const val ANT = "AA:BB:CC:DD:EE:A1"
        const val HU = "AA:BB:CC:DD:EE:0C"
        const val ALIAS = "ant-72v"
    }

    private fun directPack(alias: String? = ALIAS, index: Int = 0, address: String = ANT) =
        Pack(index = index, label = "ANT", bmsType = BmsType.ANT_BMS, bmsAddress = address, aliasGroup = alias)

    private fun hostedPack(alias: String? = ALIAS, index: Int = 1, address: String = HU) =
        Pack(index = index, label = "ANT via head unit", bmsType = BmsType.VESC_BMS, bmsAddress = address, aliasGroup = alias)

    private fun directSpec(index: Int = 0, address: String = ANT) = LinkSpec(
        address = address,
        protocolKind = ProtocolKind.ANT,
        ownedPacks = listOf(OwnedSource(index))
    )

    /** A head unit: two CAN uBoxes plus the battery it hosts. */
    private fun gatewaySpec(packIndex: Int = 1) = LinkSpec(
        address = HU,
        protocolKind = ProtocolKind.VESC,
        ownedPacks = listOf(OwnedSource(packIndex, canId = null, kind = ProtocolKind.VESC_BMS)),
        ownedControllers = listOf(OwnedSource(0, canId = 41), OwnedSource(1, canId = 42))
    )

    @Test
    fun `the product owner's scooter plans exactly one handoff`() {
        val plan = planAliasHandoffs(
            specs = listOf(directSpec(), gatewaySpec()),
            packs = listOf(directPack(), hostedPack())
        )
        assertEquals(
            listOf(
                AliasHandoff(
                    aliasGroup = ALIAS,
                    gatewayAddress = HU,
                    hostedPackIndex = 1,
                    directAddress = ANT,
                    directPackIndex = 0
                )
            ),
            plan
        )
    }

    /**
     * The guard with the most teeth. Two BMS at two addresses is the ordinary
     * multi-link vehicle Part A shipped; neither link is a head unit and
     * releasing either would simply delete a battery from the dashboard.
     */
    @Test
    fun `two ordinary BMS links plan nothing, alias group or not`() {
        val plainSecond = LinkSpec(
            address = HU,
            protocolKind = ProtocolKind.JBD,
            ownedPacks = listOf(OwnedSource(1))
        )
        assertTrue(
            planAliasHandoffs(
                specs = listOf(directSpec(), plainSecond),
                // Even sharing an alias group — the tags alone must not be
                // enough; the other end has to actually be a gateway.
                packs = listOf(directPack(), hostedPack().copy(bmsType = BmsType.JBD_BMS))
            ).isEmpty()
        )
    }

    /**
     * No shared alias group means the two packs are two different batteries.
     * `PackAggregator` would not collapse them, so releasing the direct link
     * removes a battery the rider still has.
     */
    @Test
    fun `packs that do not share an alias group plan nothing`() {
        assertTrue(
            planAliasHandoffs(
                specs = listOf(directSpec(), gatewaySpec()),
                packs = listOf(directPack(alias = null), hostedPack(alias = null))
            ).isEmpty()
        )
        assertTrue(
            planAliasHandoffs(
                specs = listOf(directSpec(), gatewaySpec()),
                packs = listOf(directPack(alias = "front"), hostedPack(alias = "rear"))
            ).isEmpty()
        )
    }

    /**
     * A direct link that also carries a controller cannot be released:
     * `disconnectLink` takes the WHOLE link down, so the rider would silently
     * lose that controller's motion telemetry for the duration of the ride —
     * the exact window in which it matters most.
     */
    @Test
    fun `a direct link that also owns a controller is never released`() {
        val directWithController = directSpec().copy(
            protocolKind = ProtocolKind.BEGODE,
            ownedControllers = listOf(OwnedSource(0))
        )
        assertTrue(
            planAliasHandoffs(
                specs = listOf(directWithController, gatewaySpec()),
                packs = listOf(directPack(), hostedPack())
            ).isEmpty()
        )
    }

    /**
     * Same reasoning one step over: a direct link carrying a SECOND, unaliased
     * battery (a Begode's two branches, a pack group behind one address) would
     * take that battery with it.
     */
    @Test
    fun `a direct link carrying an unaliased second pack is never released`() {
        val twoPackDirect = directSpec().copy(ownedPacks = listOf(OwnedSource(0), OwnedSource(2)))
        assertTrue(
            planAliasHandoffs(
                specs = listOf(twoPackDirect, gatewaySpec()),
                packs = listOf(
                    directPack(),
                    hostedPack(),
                    directPack(alias = null, index = 2).copy(label = "Branch 2")
                )
            ).isEmpty()
        )
    }

    /**
     * Both paths on ONE address is not a two-path battery at all — it is one
     * link the gateway already multiplexes (C §6: a gateway may own several
     * packs). Releasing it would disconnect the head unit that is serving the
     * battery.
     *
     * Shaped so `spec.address != gateway.address` is the ONLY clause that can
     * refuse it, because a version of this test that any other guard also
     * catches pins nothing: there are TWO links, so the `specs.size < 2` early
     * return cannot fire; the gateway owns no controllers and every pack it
     * owns is in this alias group, so neither "the direct link owns something
     * else" guard can answer either. Delete the address clause and this plans
     * a handoff whose direct address IS the gateway's — releasing the head
     * unit to itself, mid-ride.
     */
    @Test
    fun `an alias group entirely inside one gateway link plans nothing`() {
        val hostedBoth = LinkSpec(
            address = HU,
            protocolKind = ProtocolKind.VESC,
            ownedPacks = listOf(
                OwnedSource(0, kind = ProtocolKind.VESC_BMS),
                OwnedSource(1, kind = ProtocolKind.VESC_BMS)
            )
        )
        val unrelated = LinkSpec(
            address = ANT,
            protocolKind = ProtocolKind.JBD,
            ownedPacks = listOf(OwnedSource(2))
        )
        assertTrue(
            planAliasHandoffs(
                specs = listOf(hostedBoth, unrelated),
                packs = listOf(
                    directPack(address = HU),
                    hostedPack(address = HU),
                    directPack(alias = null, index = 2).copy(label = "Aux")
                )
            ).isEmpty()
        )
    }

    /** A one-link vehicle has nothing to hand anything off to. */
    @Test
    fun `a single link plans nothing`() {
        assertTrue(planAliasHandoffs(listOf(gatewaySpec(packIndex = 0)), listOf(hostedPack(index = 0))).isEmpty())
    }

    // ----- The other direction: which released links may be raised again -----

    /**
     * [yieldedLinksToRaise]'s three refusals, one test each.
     *
     * They live here, and not on the repository, because that is where the
     * guards themselves live — and because on the repository they are
     * unreachable individually: `onLinkDrop` returns on its own
     * `userInitiatedDisconnect` check before the re-raise is entered at all,
     * and `disconnect()` sets that flag and empties the link list inside ONE
     * critical section, so no repository-level sequence can produce
     * "disconnected but links still installed" to tell the first refusal from
     * the second. Each case below fails if — and only if — its own clause is
     * deleted.
     */
    private fun yielded(address: String = ANT) =
        YieldedLink(spec = directSpec(address = address), vehicle = null, gatewayAddress = HU)

    @Test
    fun `a released link is raised back into a live connection`() {
        assertEquals(
            listOf(yielded()),
            yieldedLinksToRaise(
                owed = listOf(yielded()),
                installedAddresses = listOf(HU),
                userInitiatedDisconnect = false
            ),
            "the head unit is still installed and the direct link is not — that is exactly the re-raise"
        )
    }

    @Test
    fun `nothing is raised once the user has disconnected`() {
        assertTrue(
            yieldedLinksToRaise(
                owed = listOf(yielded()),
                // Links still installed, so ONLY the user-disconnect clause
                // can refuse this one.
                installedAddresses = listOf(HU),
                userInitiatedDisconnect = true
            ).isEmpty(),
            "a drop report racing a user disconnect must not leave one link live behind it"
        )
    }

    @Test
    fun `nothing is raised into a connection that is already gone`() {
        assertTrue(
            yieldedLinksToRaise(
                owed = listOf(yielded()),
                installedAddresses = emptyList(),
                // Not user-initiated: the connection can be swept without the
                // rider having asked, and this clause is what covers that.
                userInitiatedDisconnect = false
            ).isEmpty(),
            "an empty link list means the connection is gone — re-adding a link rebuilds half of one"
        )
    }

    @Test
    fun `a link that is back by some other route is not raised a second time`() {
        assertTrue(
            yieldedLinksToRaise(
                owed = listOf(yielded()),
                installedAddresses = listOf(HU, ANT),
                userInitiatedDisconnect = false
            ).isEmpty(),
            "a second PackLink for one address would give it two reconnect loops fighting over the peripheral"
        )
    }
}
