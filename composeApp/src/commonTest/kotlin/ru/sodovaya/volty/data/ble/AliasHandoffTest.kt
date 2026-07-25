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
     * link the gateway already multiplexes. Releasing it would disconnect the
     * head unit that is serving the battery.
     */
    @Test
    fun `an alias group entirely inside one gateway link plans nothing`() {
        val both = LinkSpec(
            address = HU,
            protocolKind = ProtocolKind.VESC,
            ownedPacks = listOf(
                OwnedSource(0, kind = ProtocolKind.VESC_BMS),
                OwnedSource(1, kind = ProtocolKind.VESC_BMS)
            ),
            ownedControllers = listOf(OwnedSource(0, canId = 41))
        )
        assertTrue(
            planAliasHandoffs(
                specs = listOf(both),
                packs = listOf(directPack(address = HU), hostedPack(address = HU))
            ).isEmpty()
        )
    }

    /** A one-link vehicle has nothing to hand anything off to. */
    @Test
    fun `a single link plans nothing`() {
        assertTrue(planAliasHandoffs(listOf(gatewaySpec(packIndex = 0)), listOf(hostedPack(index = 0))).isEmpty())
    }
}
