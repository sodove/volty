package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.bms.vesc.VescCan
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescTestFrames
import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.domain.model.DeviceFact
import ru.sodovaya.volty.domain.model.DeviceFactKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [VescProtocol]'s and [VescGatewayProtocol]'s [ProtocolFacts]: the controller id, the raw fault
 * code and the CAN ids - the only things either decodes that a facts screen can show. There is no
 * firmware fact because neither asks for one; the test below pins that absence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VescFactsTest {

    /** SETUP reply with the `controller_id` byte right after the fault byte. */
    private fun setupReply(faultCode: Int = 0, controllerId: Int? = 11): ByteArray = VescPacket.frame(
        VescTestFrames.setupPayload(
            faultCode = faultCode,
            trailing = controllerId?.let { byteArrayOf(it.toByte()) } ?: ByteArray(0)
        )
    )

    /** `GET_VALUES` reply; the firmware appends a 4-byte position and then the id. */
    private fun valuesReply(faultCode: Int = 0, controllerId: Int? = 7, withPosition: Boolean = true): ByteArray {
        val tail = if (withPosition) byteArrayOf(0, 0, 0, 0) + (controllerId?.let { byteArrayOf(it.toByte()) } ?: ByteArray(0))
        else ByteArray(0)
        return VescPacket.frame(VescTestFrames.valuesPayload(faultCode = faultCode) + tail)
    }

    private fun pingReply(vararg ids: Int): ByteArray =
        VescPacket.frame(byteArrayOf(VescCan.OPCODE_PING_CAN.toByte()) + ids.map { it.toByte() }.toByteArray())

    private fun List<DeviceFact>.one(key: DeviceFactKey, index: Int? = null): DeviceFact? =
        singleOrNull { it.key == key && it.index == index }

    // ---- plain link ----

    @Test
    fun aVescThatHasSaidNothingHasNoFacts() {
        assertTrue(VescProtocol().deviceFacts().isEmpty())
        assertTrue(deviceFactsOf(JbdBmsProtocol()).isEmpty(), "a protocol without facts reports none")
    }

    @Test
    fun aSetupReplyCarriesTheControllerIdAndTheRawFaultCode() {
        val p = VescProtocol()
        p.onNotification(setupReply(faultCode = 0, controllerId = 11))
        val facts = p.deviceFacts()
        assertEquals(DeviceFact(DeviceFactKey.CONTROLLER_ID, "11"), facts.one(DeviceFactKey.CONTROLLER_ID))
        assertEquals(DeviceFact(DeviceFactKey.FAULT_CODES, "0"), facts.one(DeviceFactKey.FAULT_CODES))
        assertEquals(2, facts.size, "nothing else was said - in particular no firmware")
        assertNull(facts.one(DeviceFactKey.FIRMWARE))
    }

    @Test
    fun aValuesReplyReportsTheRawCodeNotTheLabel() {
        val p = VescProtocol(useSetupFrame = false)
        p.onNotification(valuesReply(faultCode = 99, controllerId = 7))
        assertEquals(listOf("Fault 99"), p.latestMotion(0)?.faults, "the shared model keeps only a label")
        val facts = p.deviceFacts()
        assertEquals("99", facts.one(DeviceFactKey.FAULT_CODES)?.value)
        assertEquals("7", facts.one(DeviceFactKey.CONTROLLER_ID)?.value)
    }

    @Test
    fun aValuesReplyWithoutThePositionHasNoIdToRead() {
        // Deliberately incoherent for a producer: a fault, and no id at the offset a position would shift.
        val p = VescProtocol(useSetupFrame = false)
        p.onNotification(valuesReply(faultCode = 5, controllerId = 7, withPosition = false))
        val facts = p.deviceFacts()
        assertEquals("5", facts.one(DeviceFactKey.FAULT_CODES)?.value)
        assertNull(facts.one(DeviceFactKey.CONTROLLER_ID), "a guess at a different offset is not an id")
    }

    @Test
    fun theFaultCodeFollowsTheLatestReply() {
        val p = VescProtocol()
        p.onNotification(setupReply(faultCode = 5))
        assertEquals("5", p.deviceFacts().one(DeviceFactKey.FAULT_CODES)?.value)
        p.onNotification(setupReply(faultCode = 0))
        assertEquals("0", p.deviceFacts().one(DeviceFactKey.FAULT_CODES)?.value, "a cleared fault must not linger")
    }

    @Test
    fun theCanIdsComeFromTheScanThatFoundThem() = runTest {
        val p = VescProtocol()
        val scan = async { p.scanCanBus { } }
        runCurrent()
        p.onNotification(pingReply(10, 11))
        assertEquals(listOf(10, 11), scan.await())
        assertEquals("10, 11", p.deviceFacts().one(DeviceFactKey.CAN_IDS)?.value)
    }

    @Test
    fun anEmptyBusIsNotAFact() = runTest {
        val p = VescProtocol()
        val scan = async { p.scanCanBus { } }
        runCurrent()
        p.onNotification(pingReply())
        scan.await()
        assertNull(p.deviceFacts().one(DeviceFactKey.CAN_IDS))
    }

    @Test
    fun theListIsCachedUntilAFactChanges() {
        val p = VescProtocol()
        p.onNotification(setupReply(faultCode = 0, controllerId = 11))
        val first = p.deviceFacts()
        assertSame(first, p.deviceFacts())
        p.onNotification(setupReply(faultCode = 0, controllerId = 11))
        assertSame(first, p.deviceFacts(), "same fault, same id: nothing to rebuild")
        p.onNotification(setupReply(faultCode = 3, controllerId = 11))
        val changed = p.deviceFacts()
        assertNotSame(first, changed)
        assertEquals("3", changed.one(DeviceFactKey.FAULT_CODES)?.value)
        assertSame(changed, p.deviceFacts())
    }

    @Test
    fun resetForgetsTheFacts() {
        val p = VescProtocol()
        p.onNotification(setupReply())
        p.reset()
        assertTrue(p.deviceFacts().isEmpty())
    }

    @Test
    fun theDecoderReadsTheSameBytesTheGatewayAttributesBy() {
        // The two offsets are the ones VescGatewayProtocol.controllerReplyAttributed already uses.
        val setup = VescTestFrames.setupPayload(faultCode = 4, trailing = byteArrayOf(0xF0.toByte()))
        assertEquals(240, VescValues.controllerId(setup), "an id is unsigned")
        assertEquals(4, VescValues.rawFaultCode(setup))
        assertNull(VescValues.controllerId(VescTestFrames.setupPayload()), "no trailing byte, no id")
        assertNull(VescValues.rawFaultCode(byteArrayOf(62, 1)), "another opcode has no fault byte")
        assertNull(VescValues.controllerId(byteArrayOf()))
    }

    // ---- gateway link ----

    private companion object {
        const val CAN_A = 41
        const val CAN_B = 42
    }

    private fun kotlinx.coroutines.test.TestScope.gateway() = VescGatewayProtocol(
        controllers = listOf(GatewaySource(globalIndex = 3, canId = CAN_A), GatewaySource(globalIndex = 1, canId = CAN_B)),
        packs = emptyList(),
        pollIntervalMs = 50L,
        replyTimeoutMs = 400L,
        lateReplyGuardMs = 100L,
        nowMs = { currentTime },
    )

    /** Answers every request after 20 ms with what [reply] says for (canId, opcode). */
    private fun kotlinx.coroutines.test.TestScope.link(
        p: VescGatewayProtocol,
        reply: (canId: Int?, opcode: Int) -> ByteArray?,
    ): suspend (ByteArray) -> Unit = { frame ->
        val len = frame[1].toInt() and 0xFF
        val payload = frame.copyOfRange(2, 2 + len)
        val first = payload[0].toInt() and 0xFF
        val forwarded = first == VescCan.OPCODE_FORWARD_CAN
        val canId = if (forwarded) payload[1].toInt() and 0xFF else null
        val opcode = if (forwarded) payload[2].toInt() and 0xFF else first
        val answer = reply(canId, opcode)
        if (answer != null) launch { delay(20); p.onNotification(answer) }
    }

    private fun reply(canId: Int?, opcode: Int): ByteArray? = when (opcode) {
        // Both opcodes carry the replying node's one mc_interface_get_fault(), so they agree.
        VescValues.OPCODE_GET_VALUES_SETUP -> setupReply(faultCode = if (canId == CAN_A) 5 else 0, controllerId = canId)
        VescValues.OPCODE_GET_VALUES -> valuesReply(faultCode = if (canId == CAN_A) 5 else 0, controllerId = canId)
        VescCan.OPCODE_PING_CAN -> pingReply(CAN_A, CAN_B)
        else -> null
    }

    @Test
    fun aGatewayListsEachControllerByItsPlaceInTheLink() = runTest {
        val p = gateway()
        val loop = launch { p.runPollLoop(link(p) { c, o -> reply(c, o) }) }
        advanceTimeBy(2_000)
        runCurrent()
        val facts = p.deviceFacts()
        // Controllers are [global 3 @ CAN_A, global 1 @ CAN_B]: positions 1 and 2, not global indices.
        assertEquals(CAN_A.toString(), facts.one(DeviceFactKey.CONTROLLER_ID, 1)?.value)
        assertEquals(CAN_B.toString(), facts.one(DeviceFactKey.CONTROLLER_ID, 2)?.value)
        assertEquals("5", facts.one(DeviceFactKey.FAULT_CODES, 1)?.value, "controller A reports fault 5")
        assertEquals("0", facts.one(DeviceFactKey.FAULT_CODES, 2)?.value)
        assertNull(facts.one(DeviceFactKey.FIRMWARE))
        loop.cancel()
    }

    @Test
    fun aGatewayRecordsTheScanAndCachesUntilSomethingChanges() = runTest {
        val p = gateway()
        val loop = launch { p.runPollLoop(link(p) { c, o -> reply(c, o) }) }
        advanceTimeBy(2_000)
        runCurrent()
        val scan = async { p.scanCanBus { } }
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(CAN_A, CAN_B), scan.await())
        val facts = p.deviceFacts()
        assertEquals("$CAN_A, $CAN_B", facts.one(DeviceFactKey.CAN_IDS)?.value)
        assertSame(facts, p.deviceFacts())
        // Further identical cycles replace nothing: the same list instance comes back.
        advanceTimeBy(1_000)
        runCurrent()
        assertSame(facts, p.deviceFacts(), "identical replies change no fact")
        loop.cancel()
        p.reset()
        assertTrue(p.deviceFacts().isEmpty())
    }
}
