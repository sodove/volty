package ru.sodovaya.volty.data.ble

import com.juul.kable.Characteristic
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredService
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.bms.JkBmsProtocol
import ru.sodovaya.volty.data.bms.checksumSum
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class JkConnectionSessionTest {
    @Test
    fun waitsForCompleteDeviceInfoAndRetriesLostStatusRequestOnTheSameLink() = runTest {
        val subscriptionReady = CompletableDeferred<Unit>()
        val ble = JkPeripheral(backgroundScope, subscriptionReady = subscriptionReady)
        val samples = mutableListOf<BmsData>()
        val session = session(ble, backgroundScope, samples)
        try {
            session.connect().getOrThrow()
            advanceTimeBy(600)
            assertEquals(emptyList(), ble.opcodes)
            subscriptionReady.complete(Unit)
            advanceTimeBy(600)
            assertEquals(listOf(0x97), ble.opcodes)

            // A header and an incomplete response must not release the next write.
            val info = response(0x03)
            ble.notifications.send(info.copyOfRange(0, 244))
            advanceTimeBy(250)
            assertEquals(listOf(0x97), ble.opcodes)
            ble.notifications.send(info.copyOfRange(244, info.size))
            advanceTimeBy(250)
            assertEquals(listOf(0x97, 0x96), ble.opcodes)

            // The peer ignores the first 0x96 but responds to the retry.
            advanceTimeBy(1_000)
            assertEquals(listOf(0x97, 0x96, 0x96), ble.opcodes)
            ble.notifications.send(status())
            runCurrent()
            assertEquals(51.2f, samples.single().voltage, absoluteTolerance = 0.001f)
            advanceTimeBy(3_000)
            assertEquals(listOf(0x97, 0x96, 0x96), ble.opcodes)
            assertEquals(List(3) { WriteType.WithoutResponse }, ble.writes.map { it.second })
        } finally {
            session.tearDown()
        }
    }

    @Test
    fun usesWriteWithResponseForWriteOnlyCharacteristicAndBoundsSilentPeerRetries() = runTest {
        val ble = JkPeripheral(backgroundScope, properties = 0x08)
        val session = session(ble, backgroundScope)
        try {
            session.connect().getOrThrow()
            advanceTimeBy(8_000)
            assertEquals(listOf(0x97, 0x96, 0x96, 0x96), ble.opcodes)
            for ((characteristic, mode) in ble.writes) {
                assertSame(ble.writeEndpoint, characteristic)
                assertEquals(WriteType.WithResponse, mode)
            }
        } finally {
            session.tearDown()
        }
    }

    private fun session(
        ble: JkPeripheral,
        scope: CoroutineScope,
        samples: MutableList<BmsData> = mutableListOf(),
    ) = ConnectionSession(
        parentScope = scope,
        peripheral = ble.peripheral,
        protocol = JkBmsProtocol(),
        vehicle = null,
        connectionState = MutableStateFlow(ConnectionState.Idle),
        onSample = { _, data, _ -> samples += data },
        onDropDetected = { error("Unexpected disconnect: $it") },
    )

    private fun response(type: Int, initialize: (ByteArray) -> Unit = {}): ByteArray = ByteArray(300).also {
        byteArrayOf(0x55, 0xAA.toByte(), 0xEB.toByte(), 0x90.toByte(), type.toByte()).copyInto(it)
        initialize(it)
        it[299] = checksumSum(it, 0, 299).toByte()
    }

    private fun status() = response(0x02) {
        // 51,200 mV, little-endian.
        it[119] = 0xC8.toByte()
        it[141] = 75
    }

    private class JkPeripheral(
        scope: CoroutineScope,
        properties: Int = 0x0C,
        subscriptionReady: CompletableDeferred<Unit>? = null,
    ) {
        private val profile = JkBmsProtocol().uuids
        val notifications = Channel<ByteArray>(Channel.UNLIMITED)
        val opcodes = mutableListOf<Int>()
        val writes = mutableListOf<Pair<Characteristic, WriteType>>()
        val writeEndpoint = characteristic(properties)
        private val notifyEndpoint = characteristic(0x10)
        private val service = stub<DiscoveredService> { method, _ ->
            when (method) {
                "getServiceUuid" -> Uuid.parse(profile.serviceUuid)
                "getCharacteristics" -> listOf(notifyEndpoint, writeEndpoint)
                else -> error("Unexpected service method $method")
            }
        }

        val peripheral = stub<Peripheral> { method, args ->
            when (method) {
                "connect", "getScope" -> scope
                "disconnect" -> Unit
                "getServices" -> MutableStateFlow(listOf(service))
                "getState" -> MutableStateFlow(State.Connected(scope))
                "observe" -> {
                    @Suppress("UNCHECKED_CAST")
                    val onSubscription = args[1] as suspend () -> Unit
                    flow {
                        subscriptionReady?.await()
                        onSubscription()
                        emitAll(notifications.receiveAsFlow())
                    }
                }
                "write" -> {
                    writes += (args[0] as Characteristic) to (args[2] as WriteType)
                    opcodes += (args[1] as ByteArray)[4].toInt() and 0xFF
                    Unit
                }
                else -> error("Unexpected peripheral method $method")
            }
        }

        private fun characteristic(properties: Int) = stub<DiscoveredCharacteristic> { method, _ ->
            when {
                method == "getCharacteristicUuid" -> Uuid.parse(profile.writeCharUuid)
                method == "getServiceUuid" -> Uuid.parse(profile.serviceUuid)
                method.startsWith("getProperties") -> properties
                else -> error("Unexpected characteristic method $method")
            }
        }
    }
}

private inline fun <reified T : Any> stub(crossinline action: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        action(method.name, args ?: emptyArray())
    } as T
