# Part B1 — VESC protocol + Clean Ride dashboard — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect to a VESC controller (uBox) over BLE, decode its telemetry into the Part A motion path, and render the **Clean** Ride dashboard — with dashboard style and the secondary gauge configurable per vehicle.

**Architecture:** A `VescProtocol` implements Part A's `MotionSource` capability on top of the existing `BmsProtocol` base, speaking the VESC packet protocol over Nordic UART. The repository's protocol factory generalises from `BmsType` to `ProtocolKind` so a link can decode a controller, and `planLinks` finally receives the vehicle's controllers — which lights up the `onMotionSample` → funnel → `submitMotion` → `activeMotion` path Part A built but never fired. The UI adds a `Ride` tab rendering a Clean Material 3 Expressive dashboard driven by `activeMotion`.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform (Android target), Decompose, Koin, Kable (BLE), SQLDelight, kotlinx-coroutines, kotlin.test + Turbine.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-24-vehicle-platform/B-vesc-dashboard.md` (dashboard design LOCKED, §7). Shared context: `00-overview.md`, `01-linking.md`. Part A spec: `A-foundation.md`.
- Visual reference: `docs/design/ride-dashboard-mockup.html` — **directional only**. The Compose gauge lays out ticks/numbers/center with exact spacing; do not port the mockup's SVG math.
- Branch `feat/vesc-dashboard` (already created, off `main`). Part A is merged — `Controller`, `ControllerData`, `SpeedSource`, `MotorConfig`, `ControllerState`, `MotionSource`, `MotionAggregator`, `activeMotion`, `disconnectLink`, `ProtocolKind`/`OwnedSource`/`LinkSpec`, `VehicleConnection.submitMotion` all exist.
- Package root `ru.sodovaya.volty`. Tests use `kotlin.test`; Turbine where a Flow is asserted. Match the existing test style in `composeApp/src/commonTest`.
- **The battery path stays behaviorally unchanged.** Every existing BMS test must stay green.
- Canonical units in the domain: km/h, km, °C, A, V, W. Conversion is presentation-only.
- **Duty color bands: green `<75` · amber `75–90` · red `>90`** — one shared helper feeds both this UI and the Part F alarm.
- VESC opcodes (pinned from VESC Tool `datatypes.h` / `commands.cpp`): `COMM_GET_VALUES = 4`, `COMM_GET_VALUES_SETUP = 47`. All multi-byte integers **big-endian**; scaled ints (`Double16(s)` = int16 BE ÷ s, `Double32(s)` = int32 BE ÷ s).
- SQLDelight schema is at **v4** (migrations `1.sqm`, `2.sqm`, `3.sqm`). This plan adds `4.sqm` (v4→v5). Do not renumber.
- **Known environmental issue:** `:composeApp:verifyCommonMainVoltyDatabaseMigration` fails locally with `org.sqlite.core.NativeDB._open_utf8` (sqlite-jdbc native lib cannot load in the Gradle sandbox) — pre-existing, reproduces on an unmodified tree. Verify migrations by manual schema diff + the JDBC migration-chain test instead, and note it.
- Commit after every task with the message shown in its final step.

## File Structure

**New — protocol (`data/bms/vesc/`):**
- `VescCrc.kt` — CRC16-CCITT/XMODEM.
- `VescPacket.kt` — frame/deframe + a chunk accumulator (BLE splits payloads).
- `VescReader.kt` — big-endian scaled-int cursor over a payload.
- `VescFaults.kt` — `mc_fault_code` → label.
- `VescValues.kt` — `decodeSetupValues` / `decodeValues` → `ControllerData`.
- `../VescProtocol.kt` — `BmsProtocol() + MotionSource`, in `data/bms/` beside the other protocols.

**New — domain:**
- `domain/stats/DutyBands.kt` — `DutyLevel` + thresholds (shared with Part F).
- `domain/model/DashboardConfig.kt` — `DashboardStyle`, `SecondaryGauge`.
- `domain/stats/RideMetrics.kt` — pure consumption/speed math.

**New — units:**
- `util/UnitFormatter.kt` — `UnitSystem` + formatting.

**New — UI (`presentation/ride/`):**
- `gauge/RadialGauge.kt` — the Clean concentric arc gauge.
- `SecondaryGaugeMapper.kt` — `SecondaryGauge` → (fraction, label, value, unit, color).
- `RideDashboardComponent.kt`, `RideDashboardScreen.kt`.

**Modified:**
- `data/ble/KableBmsRepository.kt` — protocol factory by `ProtocolKind`, `planLinks(packs, controllers)`, controller-only connect.
- `data/bms/BmsTypeDetector.kt`, `domain/repository/BmsRepository.kt` (`DiscoveredDevice`), `presentation/picker/*` — VESC detection.
- `domain/model/Vehicle.kt` — `dashboardStyle` / `secondaryGauge`.
- `data/db/SqlDelightVehicleRepository.kt`, `sqldelight/.../VehicleRow.sq`, new `4.sqm`.
- `data/prefs/AppPrefs.kt` — unit system + default dashboard style.
- `presentation/root/RootComponent.kt`, `RootScreen.kt` — Ride/Battery/Settings.
- `presentation/dashboard/DashboardComponent.kt` — tab enum rename.
- `presentation/settings/SettingsComponent.kt`, `SettingsScreen.kt` — unit + style pickers.

---

### Task 1: VESC packet codec (CRC + framing + reassembly)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/vesc/VescCrc.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/vesc/VescPacket.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/vesc/VescPacketTest.kt`

**Interfaces:**
- Produces: `VescCrc.crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int`; `VescPacket.frame(payload: ByteArray): ByteArray`; `class VescFrameAccumulator { fun append(chunk: ByteArray): List<ByteArray>; fun reset() }` (returns zero or more complete, CRC-verified **payloads**).

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.data.bms.vesc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VescPacketTest {

    @Test fun crc16_xmodem_known_vector() {
        // CRC-16/XMODEM("123456789") == 0x31C3
        assertEquals(0x31C3, VescCrc.crc16("123456789".encodeToByteArray()))
    }

    @Test fun short_payload_uses_start_byte_2() {
        val f = VescPacket.frame(byteArrayOf(4))
        assertEquals(0x02, f[0].toInt())
        assertEquals(1, f[1].toInt())          // length
        assertEquals(4, f[2].toInt())          // payload
        assertEquals(0x03, f[f.size - 1].toInt()) // stop
        assertEquals(6, f.size)                // start+len+payload+crc(2)+stop
    }

    @Test fun long_payload_uses_start_byte_3_with_16bit_length() {
        val payload = ByteArray(300) { 7 }
        val f = VescPacket.frame(payload)
        assertEquals(0x03, f[0].toInt())
        assertEquals(300, ((f[1].toInt() and 0xFF) shl 8) or (f[2].toInt() and 0xFF))
        assertEquals(0x03, f[f.size - 1].toInt())
    }

    @Test fun accumulator_returns_payload_of_a_whole_frame() {
        val payload = byteArrayOf(47, 1, 2, 3)
        val out = VescFrameAccumulator().append(VescPacket.frame(payload))
        assertEquals(1, out.size)
        assertContentEquals(payload, out[0])
    }

    @Test fun accumulator_reassembles_a_frame_split_across_ble_chunks() {
        val payload = ByteArray(60) { (it % 251).toByte() }
        val frame = VescPacket.frame(payload)
        val acc = VescFrameAccumulator()
        val collected = mutableListOf<ByteArray>()
        frame.toList().chunked(20).forEach { chunk ->
            collected += acc.append(chunk.toByteArray())
        }
        assertEquals(1, collected.size)
        assertContentEquals(payload, collected[0])
    }

    @Test fun accumulator_yields_two_payloads_from_back_to_back_frames() {
        val a = byteArrayOf(4, 9); val b = byteArrayOf(47, 8, 8)
        val out = VescFrameAccumulator().append(VescPacket.frame(a) + VescPacket.frame(b))
        assertEquals(2, out.size)
        assertContentEquals(a, out[0]); assertContentEquals(b, out[1])
    }

    @Test fun bad_crc_frame_is_dropped_and_stream_resyncs() {
        val good = VescPacket.frame(byteArrayOf(4, 1))
        val bad = VescPacket.frame(byteArrayOf(4, 2)).copyOf()
        bad[bad.size - 2] = (bad[bad.size - 2].toInt() xor 0xFF).toByte() // corrupt CRC
        val out = VescFrameAccumulator().append(bad + good)
        assertEquals(1, out.size)
        assertContentEquals(byteArrayOf(4, 1), out[0])
    }

    @Test fun garbage_before_a_frame_is_skipped() {
        val out = VescFrameAccumulator().append(byteArrayOf(0x55, 0x00, 0x11) + VescPacket.frame(byteArrayOf(4)))
        assertEquals(1, out.size)
        assertTrue(out[0].contentEquals(byteArrayOf(4)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.bms.vesc.VescPacketTest"`
Expected: FAIL — unresolved references `VescCrc`, `VescPacket`, `VescFrameAccumulator`.

- [ ] **Step 3: Write minimal implementation**

`VescCrc.kt`:
```kotlin
package ru.sodovaya.volty.data.bms.vesc

/**
 * CRC-16/CCITT (XMODEM): poly 0x1021, init 0x0000, no reflection, no final xor.
 * This is the CRC the VESC packet layer puts over the payload — distinct from
 * the CRC-16/MODBUS the ANT BMS uses (see CrcUtils), hence a separate object.
 */
object VescCrc {
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }
}
```

`VescPacket.kt`:
```kotlin
package ru.sodovaya.volty.data.bms.vesc

/**
 * VESC packet framing:
 *   [start][length][payload...][crc16 BE][stop=0x03]
 * start 0x02 → 1-byte length (payload <= 255); 0x03 → 2-byte big-endian length.
 * (Firmware also defines 0x04 / 3-byte length for very large payloads; we never
 * SEND one, and the accumulator skips a start byte it cannot parse rather than
 * pretending to understand it.)
 */
object VescPacket {
    const val STOP: Int = 0x03

    fun frame(payload: ByteArray): ByteArray {
        val header = if (payload.size <= 255) {
            byteArrayOf(0x02, payload.size.toByte())
        } else {
            byteArrayOf(0x03, ((payload.size shr 8) and 0xFF).toByte(), (payload.size and 0xFF).toByte())
        }
        val crc = VescCrc.crc16(payload)
        return header + payload +
            byteArrayOf(((crc shr 8) and 0xFF).toByte(), (crc and 0xFF).toByte(), STOP.toByte())
    }
}

/**
 * Reassembles VESC frames from BLE notification chunks (a payload routinely
 * spans several 20-byte MTU writes) and hands back only complete, CRC-verified
 * payloads. Mirrors the role [ru.sodovaya.volty.data.bms.ByteArrayAccumulator]
 * plays for the byte-oriented BMS protocols; not thread-safe by design — it
 * lives inside one session's single observe coroutine.
 */
class VescFrameAccumulator(private val maxBuffer: Int = 4096) {

    private var buf = ByteArray(0)

    fun reset() { buf = ByteArray(0) }

    fun append(chunk: ByteArray): List<ByteArray> {
        buf = if (buf.isEmpty()) chunk.copyOf() else buf + chunk
        if (buf.size > maxBuffer) buf = buf.copyOfRange(buf.size - maxBuffer, buf.size)

        val out = mutableListOf<ByteArray>()
        while (true) {
            if (buf.isEmpty()) break
            val start = buf[0].toInt() and 0xFF
            val headerLen = when (start) { 0x02 -> 2; 0x03 -> 3; else -> 0 }
            if (headerLen == 0) { buf = buf.copyOfRange(1, buf.size); continue } // resync
            if (buf.size < headerLen) break                                       // need more
            val payloadLen = if (start == 0x02) buf[1].toInt() and 0xFF
                             else ((buf[1].toInt() and 0xFF) shl 8) or (buf[2].toInt() and 0xFF)
            val total = headerLen + payloadLen + 3                                // +crc(2)+stop(1)
            if (payloadLen == 0 || total > maxBuffer) { buf = buf.copyOfRange(1, buf.size); continue }
            if (buf.size < total) break                                           // need more
            val payload = buf.copyOfRange(headerLen, headerLen + payloadLen)
            val crcGot = ((buf[headerLen + payloadLen].toInt() and 0xFF) shl 8) or
                          (buf[headerLen + payloadLen + 1].toInt() and 0xFF)
            val stopOk = (buf[total - 1].toInt() and 0xFF) == VescPacket.STOP
            if (stopOk && crcGot == VescCrc.crc16(payload)) {
                out += payload
                buf = buf.copyOfRange(total, buf.size)
            } else {
                buf = buf.copyOfRange(1, buf.size)                                // false start; resync
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.bms.vesc.VescPacketTest"`
Expected: PASS (8/8)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/vesc/ composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/vesc/
git commit -m "feat(vesc): packet framing, CRC16-CCITT and chunk reassembly"
```

---

### Task 2: VESC value decoders → ControllerData

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/vesc/VescReader.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/vesc/VescFaults.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/vesc/VescValues.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/vesc/VescValuesTest.kt`

**Interfaces:**
- Consumes: `ControllerData`, `SpeedSource`, `MotorConfig` (Part A domain).
- Produces: `class VescReader(payload: ByteArray)` with `i8()/u8()/i16()/i32()/d16(scale)/d32(scale)/remaining()`; `VescFaults.label(code: Int): String?`; `VescValues.decodeSetupValues(payload: ByteArray): ControllerData?`; `VescValues.decodeValues(payload: ByteArray, motor: MotorConfig): ControllerData?`; `VescValues.derivedSpeedKmh(eRpm: Float, motor: MotorConfig): Float?`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.data.bms.vesc

import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VescValuesTest {

    /** Builds a COMM_GET_VALUES_SETUP payload in the pinned field order. */
    private fun setupPayload(): ByteArray {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) { o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
                          o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        o += 47                       // opcode
        i16(520)                      // temp_mos   /10  = 52.0
        i16(680)                      // temp_motor /10  = 68.0
        i32(-8250)                    // current_motor /100 = -82.5
        i32(5240)                     // current_in /100 = 52.4
        i16(760)                      // duty_now /1000 = 0.760
        i32(12000)                    // rpm
        i32(13056)                    // speed /1000 = 13.056 m/s = 47.0 km/h
        i16(782)                      // v_in /10 = 78.2
        i16(840)                      // battery_level /1000 = 0.840
        i32(154000)                   // amp_hours /1e4 = 15.4
        i32(21000)                    // amp_hours_charged /1e4 = 2.1
        i32(9800000)                  // watt_hours /1e4 = 980.0
        i32(1200000)                  // watt_hours_charged /1e4 = 120.0
        i32(12400000)                 // tachometer /1e3 = 12400.0 m
        i32(1284600000)               // tachometer_abs /1e3 = 1284600.0 m = 1284.6 km
        i32(0)                        // position
        o += 0                        // fault_code = 0
        o += 11                       // vesc_id
        return o.toByteArray()
    }

    @Test fun setup_values_decode_every_field() {
        val d = VescValues.decodeSetupValues(setupPayload())!!
        assertTrue(abs(d.speedKmh - 47.0f) < 0.05f)
        assertEquals(SpeedSource.REPORTED, d.speedSource)
        assertTrue(d.speedKnown)
        assertTrue(abs(d.dutyPercent - 76.0f) < 0.01f)
        assertTrue(abs(d.motorCurrentA - (-82.5f)) < 0.01f)
        assertTrue(abs(d.batteryCurrentA - 52.4f) < 0.01f)
        assertTrue(abs(d.inputVoltageV - 78.2f) < 0.01f)
        assertTrue(abs(d.powerW - (78.2f * 52.4f)) < 0.5f)
        assertEquals(12000f, d.eRpm)
        assertTrue(abs(d.escTempC - 52.0f) < 0.01f)
        assertTrue(abs(d.motorTempC - 68.0f) < 0.01f)
        assertTrue(d.hasMotorTemp)
        assertTrue(abs(d.odometerKm - 1284.6f) < 0.05f)
        assertTrue(abs(d.consumedAh - 15.4f) < 0.01f)
        assertTrue(abs(d.consumedWh - 980.0f) < 0.1f)
        assertTrue(abs(d.regenAh - 2.1f) < 0.01f)
        assertTrue(abs(d.regenWh - 120.0f) < 0.1f)
        assertTrue(d.faults.isEmpty())
        assertTrue(d.isConnected)
    }

    @Test fun duty_is_absolute_percent_when_braking() {
        val p = setupPayload().copyOf()
        // duty_now sits at offset 1+2+2+4+4 = 13 (after opcode + two i16 + two i32)
        val neg = -300
        p[13] = ((neg shr 8) and 0xFF).toByte(); p[14] = (neg and 0xFF).toByte()
        val d = VescValues.decodeSetupValues(p)!!
        assertTrue(abs(d.dutyPercent - 30.0f) < 0.01f)
    }

    @Test fun fault_code_maps_to_a_label() {
        val p = setupPayload().copyOf()
        p[p.size - 2] = 1                       // FAULT_CODE_OVER_VOLTAGE
        val d = VescValues.decodeSetupValues(p)!!
        assertEquals(listOf("Over voltage"), d.faults)
    }

    @Test fun missing_motor_sensor_reports_no_motor_temp() {
        val p = setupPayload().copyOf()
        val v = -2000                            // -200.0 °C sentinel of an unwired sensor
        p[3] = ((v shr 8) and 0xFF).toByte(); p[4] = (v and 0xFF).toByte()
        val d = VescValues.decodeSetupValues(p)!!
        assertTrue(!d.hasMotorTemp)
    }

    @Test fun truncated_payload_decodes_to_null_rather_than_throwing() {
        assertNull(VescValues.decodeSetupValues(setupPayload().copyOfRange(0, 12)))
    }

    @Test fun wrong_opcode_is_rejected() {
        val p = setupPayload().copyOf(); p[0] = 99
        assertNull(VescValues.decodeSetupValues(p))
    }

    @Test fun derived_speed_from_erpm_and_wheel() {
        // 10000 eRPM / 15 pole pairs = 666.67 mech RPM; 254 mm wheel.
        val kmh = VescValues.derivedSpeedKmh(10000f, MotorConfig(polePairs = 15, wheelDiameterMm = 254, gearRatio = 1f))!!
        assertTrue(abs(kmh - 31.9f) < 0.3f, "got $kmh")
    }

    @Test fun derived_speed_is_null_without_a_wheel_diameter() {
        assertNull(VescValues.derivedSpeedKmh(10000f, MotorConfig(wheelDiameterMm = 0)))
    }

    @Test fun plain_get_values_derives_speed_and_has_no_reported_source() {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) { o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
                          o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        o += 4                        // opcode
        i16(520); i16(680)            // temps
        i32(-8250); i32(5240)         // motor / input current
        i32(0); i32(0)                // id, iq
        i16(760)                      // duty
        i32(10000)                    // rpm
        i16(782)                      // v_in
        i32(154000); i32(21000); i32(9800000); i32(1200000)   // Ah / Wh
        i32(1000); i32(2000)          // tachometer, tachometer_abs (raw counts)
        o += 0                        // fault
        val d = VescValues.decodeValues(o.toByteArray(), MotorConfig(polePairs = 15, wheelDiameterMm = 254))!!
        assertEquals(SpeedSource.DERIVED, d.speedSource)
        assertTrue(abs(d.speedKmh - 31.9f) < 0.3f)
        assertTrue(abs(d.dutyPercent - 76.0f) < 0.01f)
        assertEquals(0f, d.odometerKm)   // raw counts are not metres — not reported
    }

    @Test fun plain_get_values_without_wheel_config_reports_speed_unknown() {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) { o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
                          o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        o += 4; i16(520); i16(680); i32(0); i32(0); i32(0); i32(0); i16(0); i32(10000); i16(782)
        i32(0); i32(0); i32(0); i32(0); i32(0); i32(0); o += 0
        val d = VescValues.decodeValues(o.toByteArray(), MotorConfig(wheelDiameterMm = 0))!!
        assertEquals(SpeedSource.NONE, d.speedSource)
        assertTrue(!d.speedKnown)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.bms.vesc.VescValuesTest"`
Expected: FAIL — unresolved `VescValues`, `VescFaults`, `VescReader`.

- [ ] **Step 3: Write minimal implementation**

`VescReader.kt`:
```kotlin
package ru.sodovaya.volty.data.bms.vesc

/**
 * Big-endian cursor over a VESC payload. Every read is length-checked: a short
 * frame yields null from the decoder rather than an exception, because a
 * truncated notification is an ordinary BLE event, not a programming error.
 */
class VescReader(private val p: ByteArray, private var i: Int = 0) {
    fun remaining(): Int = p.size - i
    fun has(n: Int): Boolean = remaining() >= n

    fun u8(): Int { val v = p[i].toInt() and 0xFF; i += 1; return v }
    fun i8(): Int { val v = p[i].toInt(); i += 1; return v }
    fun i16(): Int { val v = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF); i += 2
                     return if (v >= 0x8000) v - 0x10000 else v }
    fun i32(): Int { val v = ((p[i].toInt() and 0xFF) shl 24) or ((p[i + 1].toInt() and 0xFF) shl 16) or
                             ((p[i + 2].toInt() and 0xFF) shl 8) or (p[i + 3].toInt() and 0xFF); i += 4; return v }
    fun d16(scale: Float): Float = i16() / scale
    fun d32(scale: Float): Float = i32() / scale
}
```

`VescFaults.kt`:
```kotlin
package ru.sodovaya.volty.data.bms.vesc

/** `mc_fault_code` → human label (VESC datatypes.h). 0 is "no fault". */
object VescFaults {
    private val labels = mapOf(
        1 to "Over voltage", 2 to "Under voltage", 3 to "DRV fault", 4 to "ABS over current",
        5 to "Over temp FET", 6 to "Over temp motor", 7 to "Gate driver over voltage",
        8 to "Gate driver under voltage", 9 to "MCU under voltage", 10 to "Booting from watchdog reset",
        11 to "Encoder SPI fault", 12 to "Encoder sincos below min amplitude",
        13 to "Encoder sincos above max amplitude", 14 to "Flash corruption",
        15 to "High offset current sensor 1", 16 to "High offset current sensor 2",
        17 to "High offset current sensor 3", 18 to "Unbalanced currents"
    )
    fun label(code: Int): String? = if (code == 0) null else labels[code] ?: "Fault $code"
}
```

`VescValues.kt`:
```kotlin
package ru.sodovaya.volty.data.bms.vesc

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.PI
import kotlin.math.abs

/**
 * Decoders for the two VESC telemetry frames, pinned against VESC Tool's
 * `commands.cpp`. SETUP (opcode 47) is what we normally poll: it is the only
 * one carrying a controller-computed ground speed and battery level. Plain
 * GET_VALUES (opcode 4) is the fallback for setups that do not answer SETUP;
 * its speed must then be derived from eRPM (see 01-linking §2).
 */
object VescValues {

    const val OPCODE_GET_VALUES: Int = 4
    const val OPCODE_GET_VALUES_SETUP: Int = 47

    /** A motor temperature this low means "no sensor wired", not a cold motor. */
    private const val NO_MOTOR_SENSOR_BELOW_C = -50f

    fun decodeSetupValues(payload: ByteArray): ControllerData? {
        val r = VescReader(payload)
        if (!r.has(1) || r.u8() != OPCODE_GET_VALUES_SETUP) return null
        // temps(2+2) currents(4+4) duty(2) rpm(4) speed(4) vin(2) batt(2)
        // ah(4) ahc(4) wh(4) whc(4) tach(4) tachAbs(4) pos(4) fault(1) = 53
        if (!r.has(53)) return null
        val tempMos = r.d16(10f)
        val tempMotor = r.d16(10f)
        val currentMotor = r.d32(100f)
        val currentIn = r.d32(100f)
        val duty = r.d16(1000f)
        val rpm = r.d32(1f)
        val speedMs = r.d32(1000f)
        val vIn = r.d16(10f)
        val battLevel = r.d16(1000f)
        val ampHours = r.d32(1e4f)
        val ampHoursChg = r.d32(1e4f)
        val wattHours = r.d32(1e4f)
        val wattHoursChg = r.d32(1e4f)
        val tachM = r.d32(1000f)
        val tachAbsM = r.d32(1000f)
        r.d32(1e6f)                                   // position — unused
        val fault = r.i8()
        return ControllerData(
            speedKmh = speedMs * 3.6f,
            speedSource = SpeedSource.REPORTED,
            dutyPercent = abs(duty) * 100f,
            motorCurrentA = currentMotor,
            batteryCurrentA = currentIn,
            inputVoltageV = vIn,
            powerW = vIn * currentIn,
            eRpm = rpm,
            escTempC = tempMos,
            motorTempC = tempMotor,
            hasMotorTemp = tempMotor > NO_MOTOR_SENSOR_BELOW_C,
            odometerKm = tachAbsM / 1000f,
            tripKm = tachM / 1000f,
            consumedAh = ampHours,
            consumedWh = wattHours,
            regenAh = ampHoursChg,
            regenWh = wattHoursChg,
            faults = listOfNotNull(VescFaults.label(fault)),
            isConnected = true,
            batteryLevelFraction = battLevel
        )
    }

    fun decodeValues(payload: ByteArray, motor: MotorConfig): ControllerData? {
        val r = VescReader(payload)
        if (!r.has(1) || r.u8() != OPCODE_GET_VALUES) return null
        // temps(4) currents(8) id/iq(8) duty(2) rpm(4) vin(2) ah/wh(16) tach(8) fault(1) = 53
        if (!r.has(53)) return null
        val tempMos = r.d16(10f)
        val tempMotor = r.d16(10f)
        val currentMotor = r.d32(100f)
        val currentIn = r.d32(100f)
        r.d32(100f); r.d32(100f)                      // id, iq — unused
        val duty = r.d16(1000f)
        val rpm = r.d32(1f)
        val vIn = r.d16(10f)
        val ampHours = r.d32(1e4f)
        val ampHoursChg = r.d32(1e4f)
        val wattHours = r.d32(1e4f)
        val wattHoursChg = r.d32(1e4f)
        r.i32(); r.i32()                              // tachometer counts — NOT metres; not reported
        val fault = r.i8()
        val derived = derivedSpeedKmh(rpm, motor)
        return ControllerData(
            speedKmh = derived ?: 0f,
            speedSource = if (derived != null) SpeedSource.DERIVED else SpeedSource.NONE,
            dutyPercent = abs(duty) * 100f,
            motorCurrentA = currentMotor,
            batteryCurrentA = currentIn,
            inputVoltageV = vIn,
            powerW = vIn * currentIn,
            eRpm = rpm,
            escTempC = tempMos,
            motorTempC = tempMotor,
            hasMotorTemp = tempMotor > NO_MOTOR_SENSOR_BELOW_C,
            consumedAh = ampHours,
            consumedWh = wattHours,
            regenAh = ampHoursChg,
            regenWh = wattHoursChg,
            faults = listOfNotNull(VescFaults.label(fault)),
            isConnected = true
        )
    }

    /**
     * eRPM → ground speed. Mechanical RPM = eRPM / polePairs; wheel RPM =
     * mechanical / gearRatio (motor revolutions per wheel revolution). Null when
     * the wheel is unconfigured — an unknown speed must read as unknown, never 0.
     */
    fun derivedSpeedKmh(eRpm: Float, motor: MotorConfig): Float? {
        if (motor.wheelDiameterMm <= 0 || motor.polePairs <= 0 || motor.gearRatio <= 0f) return null
        val wheelRpm = eRpm / motor.polePairs / motor.gearRatio
        val circumferenceKm = (PI * motor.wheelDiameterMm / 1_000_000.0).toFloat()
        return wheelRpm * circumferenceKm * 60f
    }
}
```

Add the one new field to `ControllerData` (`domain/model/ControllerData.kt`), defaulted so nothing else changes:
```kotlin
    /**
     * Controller-computed battery level 0..1 from COMM_GET_VALUES_SETUP, or null
     * when the frame carries none (plain GET_VALUES, non-VESC controllers). Used
     * only to seed a derived battery's SoC — the real fuel gauge, when a smart
     * BMS is present, always wins.
     */
    val batteryLevelFraction: Float? = null,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.bms.vesc.VescValuesTest"` then the full suite `./gradlew :composeApp:testDebugUnitTest`.
Expected: PASS; the added defaulted field breaks nothing (`MotionAggregatorTest` and friends stay green).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/vesc/ composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/ControllerData.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/vesc/VescValuesTest.kt
git commit -m "feat(vesc): decode GET_VALUES_SETUP / GET_VALUES into ControllerData"
```

---

### Task 3: VescProtocol (MotionSource + derived battery)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/VescProtocol.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/VescProtocolTest.kt`

**Interfaces:**
- Consumes: `VescPacket`, `VescFrameAccumulator`, `VescValues`, `MotionSource`, `BmsProtocol`, `MotorConfig`.
- Produces: `class VescProtocol(deriveBattery: Boolean = true, motor: MotorConfig = MotorConfig(), useSetupFrame: Boolean = true) : BmsProtocol(), MotionSource` — `controllerCount = 1`, `latestMotion(0)`, `packCount = if (deriveBattery) 1 else 0`, `latestData(0)`; `VescProtocol.NUS_SERVICE/NUS_WRITE/NUS_NOTIFY` constants.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VescProtocolTest {

    private fun setupFrame(speedMs: Int = 13056, vIn: Int = 782, currentIn: Int = 5240, battLevel: Int = 840): ByteArray {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) { o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
                          o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        o += 47; i16(520); i16(680); i32(-8250); i32(currentIn); i16(760); i32(12000); i32(speedMs)
        i16(vIn); i16(battLevel); i32(154000); i32(21000); i32(9800000); i32(1200000)
        i32(12400000); i32(1284600000); i32(0); o += 0; o += 11
        return VescPacket.frame(o.toByteArray())
    }

    @Test fun poll_asks_for_the_setup_frame() {
        val p = VescProtocol()
        assertTrue(p.handshakeCommands().isEmpty())
        val poll = p.pollCommands()
        assertEquals(1, poll.size)
        assertEquals(47, poll[0][2].toInt())          // start, len, opcode
    }

    @Test fun notification_produces_motion() {
        val p = VescProtocol()
        assertNull(p.latestMotion(0))
        p.onNotification(setupFrame())
        val m = p.latestMotion(0)!!
        assertTrue(abs(m.speedKmh - 47.0f) < 0.05f)
        assertEquals(SpeedSource.REPORTED, m.speedSource)
        assertEquals(1, p.controllerCount)
    }

    @Test fun a_frame_split_across_chunks_still_decodes() {
        val p = VescProtocol()
        setupFrame().toList().chunked(20).forEach { p.onNotification(it.toByteArray()) }
        assertNotNull(p.latestMotion(0))
    }

    @Test fun each_decode_is_a_new_instance_so_the_motion_gate_lets_it_through() {
        val p = VescProtocol()
        p.onNotification(setupFrame())
        val first = p.latestMotion(0)
        assertSame(first, p.latestMotion(0))          // cached between frames
        p.onNotification(setupFrame())
        assertTrue(first !== p.latestMotion(0))       // new frame ⇒ new instance
    }

    @Test fun derived_battery_uses_the_controller_battery_level() {
        val p = VescProtocol(deriveBattery = true)
        assertEquals(1, p.packCount)
        p.onNotification(setupFrame())
        val b = p.latestData(0)!!
        assertTrue(abs(b.voltage - 78.2f) < 0.01f)
        assertTrue(abs(b.soc - 84.0f) < 0.01f)
        assertTrue(b.socKnown)
        // VESC input current is positive while DISCHARGING; BmsData is + = charging.
        assertTrue(b.current < 0f, "discharge must read negative, got ${b.current}")
        assertTrue(b.power < 0f)
        assertTrue(b.cellVoltages.isEmpty())
        assertTrue(b.isConnected)
    }

    @Test fun regen_flips_the_derived_battery_current_positive() {
        val p = VescProtocol(deriveBattery = true)
        p.onNotification(setupFrame(currentIn = -1200))
        assertTrue(p.latestData(0)!!.current > 0f)
    }

    @Test fun without_a_battery_level_soc_is_left_unknown_for_the_estimator() {
        // battLevel = 0 is what a VESC with no battery config reports.
        val p = VescProtocol(deriveBattery = true)
        p.onNotification(setupFrame(battLevel = 0))
        val b = p.latestData(0)!!
        assertEquals(0f, b.soc)
        assertTrue(!b.socKnown, "unknown SoC must be flagged so VoltageSocEstimator can fill it in")
    }

    @Test fun derive_battery_off_means_no_pack() {
        val p = VescProtocol(deriveBattery = false)
        assertEquals(0, p.packCount)
        p.onNotification(setupFrame())
        assertNull(p.latestData(0))
        assertNotNull(p.latestMotion(0))
    }

    @Test fun reset_clears_decoded_state() {
        val p = VescProtocol()
        p.onNotification(setupFrame())
        p.reset()
        assertNull(p.latestMotion(0))
    }

    @Test fun get_values_fallback_mode_polls_opcode_4() {
        val p = VescProtocol(useSetupFrame = false, motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254))
        assertEquals(4, p.pollCommands()[0][2].toInt())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.bms.VescProtocolTest"`
Expected: FAIL — unresolved `VescProtocol`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.data.bms.vesc.VescFrameAccumulator
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.MotorConfig

/**
 * VESC-based controllers (incl. uBox) over the Nordic UART Service.
 *
 * Both a [BmsProtocol] and a [MotionSource]: motion is the point, and the
 * battery side is optional — a VESC with no smart BMS still knows its pack
 * voltage, so it can back a single DERIVED pack ([deriveBattery]). When a real
 * BMS covers the pack, the composer turns that off and `packCount` is 0.
 *
 * Polling asks for COMM_GET_VALUES_SETUP: it is the only frame carrying a
 * controller-computed ground speed and battery level. [useSetupFrame] = false
 * falls back to plain COMM_GET_VALUES, whose speed is derived from eRPM + [motor].
 */
class VescProtocol(
    private val deriveBattery: Boolean = true,
    private val motor: MotorConfig = MotorConfig(),
    private val useSetupFrame: Boolean = true
) : BmsProtocol(), MotionSource {

    companion object {
        const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_WRITE   = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        const val NUS_NOTIFY  = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    }

    override val uuids = BmsUuids(
        serviceUuid = NUS_SERVICE, notifyCharUuid = NUS_NOTIFY, writeCharUuid = NUS_WRITE
    )

    private val accumulator = VescFrameAccumulator()

    @Volatile private var motion: ControllerData? = null
    @Volatile private var battery: BmsData? = null

    override fun handshakeCommands(): List<ByteArray> = emptyList()

    override fun pollCommands(): List<ByteArray> = listOf(
        VescPacket.frame(byteArrayOf(
            (if (useSetupFrame) VescValues.OPCODE_GET_VALUES_SETUP else VescValues.OPCODE_GET_VALUES).toByte()
        ))
    )

    /** ~6.7 Hz: fast enough for a live speedo, gentle enough on a BLE link. */
    override val pollIntervalMs: Long = 150L

    override val controllerCount: Int get() = 1
    override val packCount: Int get() = if (deriveBattery) 1 else 0

    override fun onNotification(data: ByteArray) {
        for (payload in accumulator.append(data)) {
            val decoded = if (useSetupFrame) VescValues.decodeSetupValues(payload)
                          else VescValues.decodeValues(payload, motor)
            if (decoded != null) {
                motion = decoded
                if (deriveBattery) battery = deriveBattery(decoded)
            }
        }
    }

    override fun latestMotion(controllerIndex: Int): ControllerData? =
        if (controllerIndex == 0) motion else null

    override fun latestData(packIndex: Int): BmsData? =
        if (packIndex == 0) battery else null

    override fun reset() {
        accumulator.reset()
        motion = null
        battery = null
    }

    /**
     * Synthesise the pack the controller can see. Sign is the one real trap:
     * VESC input current is POSITIVE while discharging, [BmsData.current] is
     * "+ = charging" — so it is negated here, and the power with it.
     *
     * `batteryLevelFraction` is the controller's own gauge (computed from its
     * configured battery cutoffs). When it is absent or zero the VESC has no
     * battery configuration, so the SoC is left unknown (`socKnown = false`)
     * and VoltageSocEstimator fills it in downstream from the vehicle's
     * chemistry — the same path a dumb Begode takes.
     */
    private fun deriveBattery(m: ControllerData): BmsData {
        val level = m.batteryLevelFraction
        val known = level != null && level > 0f
        return BmsData(
            voltage = m.inputVoltageV,
            current = -m.batteryCurrentA,
            power = -m.powerW,
            soc = if (known) level!! * 100f else 0f,
            socKnown = known,
            // No cells and no pack thermistor: a controller measures neither.
            // The ESC temperature is motion telemetry and stays out of the
            // battery's temperature list, so it can never trip a battery
            // over-temperature alert.
            cellVoltages = emptyList(),
            temperatures = emptyList(),
            chargeEnabled = true,
            dischargeEnabled = true,
            isConnected = true,
            timestamp = m.timestamp
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.bms.VescProtocolTest"`
Expected: PASS (10/10)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/VescProtocol.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/VescProtocolTest.kt
git commit -m "feat(vesc): VescProtocol — MotionSource over Nordic UART with a derived pack"
```

---

### Task 4: Wire controllers into the connection (THE CRUX)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryVescTest.kt`

**Interfaces:**
- Consumes: `VescProtocol`, `planLinks(packs, controllers)`, `LinkSpec.ownedControllers`, `Vehicle.primaryAddress`, `VehicleConnection(controllers = …)`, `submitMotion`.
- Produces: an internal `createProtocol(spec: LinkSpec, vehicle: Vehicle?): BmsProtocol` (the `BmsType` overload stays for guest/demo paths). No public API change.

> This is where Part A's dormant first hop switches on: once `ownedControllers` is populated, `ConnectionSession.onMotionSample` fires and motion reaches `activeMotion` through the real funnel. Work carefully and read every call site before editing.

**MUST read first:** `KableBmsRepository.kt` in full — `connect`, `doConnect(address, type, vehicle)`, `connectionPacks`, `effectiveLinkSpecs`, `buildOrchestrator`, `buildPipeline`, `makeLinkOnSample`, the `ConnectionSession(...)` construction, and `createProtocol(type: BmsType)`.

**Named requirements:**
1. **A controller-only vehicle must connect.** `Vehicle.bmsType` / `Vehicle.bmsAddress` call `packs.first()` and **throw** on a vehicle with zero packs (legal since Part A). `connect(vehicle)` currently routes through them. Route through `vehicle.primaryAddress` and plan the links from the vehicle instead, so a VESC-only vehicle never hits `packs.first()`.
2. `effectiveLinkSpecs` / `connectionPacks` must call `planLinks(stored, vehicle?.controllers ?: emptyList())` so links carry `ownedControllers`.
3. `createProtocol(spec, vehicle)`: for `ProtocolKind.VESC`, look up the `Controller` owned by that link (match `spec.ownedControllers.first().globalIndex` against `vehicle.controllers`) and build `VescProtocol(deriveBattery = controller.providesDerivedBattery, motor = controller.motor)`. Every other kind delegates to the existing `createProtocol(kind.toBmsType())` — battery behavior unchanged.
4. Wherever the code asks a protocol for `packCount` to size a link's packs, it must build the protocol via the spec+vehicle form so a VESC link reports its derived-pack count correctly.
5. `buildOrchestrator` already receives `controllers = vehicle?.controllers ?: emptyList()`; verify it still does after the edits.
6. Do **not** change the funnel, the consumer, `VehicleConnection`, or the battery decode path.

- [ ] **Step 1: Write the failing test**

Model the harness on `KableBmsRepositoryMultiLinkTest` / `KableBmsRepositoryMotionTest` (read one first for how peripherals/sessions are faked and how a vehicle is connected). Assertions to write:

```kotlin
// composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryVescTest.kt
@Test fun controller_only_vehicle_plans_a_controller_link_and_does_not_touch_packs_first() {
    // A Vehicle with packs = emptyList() and one VESC Controller.
    // Assert planning/connect does NOT throw (the packs.first() trap) and the
    // planned LinkSpec has protocolKind VESC with ownedControllers = [0].
}

@Test fun a_vesc_link_builds_a_VescProtocol_that_is_a_MotionSource() {
    // Build the protocol for that spec+vehicle; assert it is a MotionSource
    // with controllerCount == 1, and packCount == 1 when providesDerivedBattery.
}

@Test fun motion_from_a_vesc_link_reaches_activeMotion() {
    // Drive a decoded SETUP frame through the faked session for the controller
    // link; assert repo.activeMotion emits speedSource REPORTED and the speed.
}

@Test fun a_battery_only_vehicle_plans_exactly_as_before() {
    // Regression guard: same LinkSpec shape/protocol as pre-Task-4 for an ANT vehicle.
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.KableBmsRepositoryVescTest"`
Expected: FAIL — controller links are not planned / no VESC protocol is built (and the controller-only case throws from `packs.first()`).

- [ ] **Step 3: Write minimal implementation**

Apply the six named requirements. The factory takes this shape (adapt names to the file):
```kotlin
private fun createProtocol(spec: LinkSpec, vehicle: Vehicle?): BmsProtocol =
    when (spec.protocolKind) {
        ProtocolKind.VESC -> {
            val ctrlIndex = spec.ownedControllers.firstOrNull()?.globalIndex
            val controller = vehicle?.controllers?.firstOrNull { it.index == ctrlIndex }
            VescProtocol(
                // A lone controller with no battery source of its own backs a
                // derived pack; the composer (Part G) turns this off once a real
                // BMS covers the same battery.
                deriveBattery = controller?.providesDerivedBattery
                    ?: (vehicle?.packs?.isEmpty() ?: true),
                motor = controller?.motor ?: MotorConfig()
            )
        }
        else -> createProtocol(spec.protocolKind.toBmsType())
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest` (FULL suite — every multi-link/motion/battery test must stay green) and `./gradlew :composeApp:compileDebugKotlinAndroid`.
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryVescTest.kt
git commit -m "feat(vesc): plan controller links and build VescProtocol — motion flows end to end"
```

---

### Task 5: VESC detection in the scanner

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/BmsTypeDetector.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt` (the `DiscoveredDevice` data class)
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt` (`scanAll` fills the new field)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/BmsTypeDetectorVescTest.kt`

**Interfaces:**
- Produces: `BmsTypeDetector.detectController(name: String?, serviceUuids: List<String>): ControllerType?`; `DiscoveredDevice.controllerType: ControllerType? = null`. The existing `detect(name, serviceUuids): BmsType?` keeps its exact behavior.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BmsTypeDetectorVescTest {

    private val nus = listOf("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    @Test fun nordic_uart_service_flags_a_vesc_candidate() {
        assertEquals(ControllerType.VESC, BmsTypeDetector.detectController(null, nus))
    }

    @Test fun vesc_style_names_are_detected_without_the_service_uuid() {
        assertEquals(ControllerType.VESC, BmsTypeDetector.detectController("VESC BLE UART", emptyList()))
        assertEquals(ControllerType.VESC, BmsTypeDetector.detectController("uBox-250", emptyList()))
    }

    @Test fun a_battery_is_not_reported_as_a_controller() {
        assertNull(BmsTypeDetector.detectController("ANT-BLE24", listOf("0000ffe0-0000-1000-8000-00805f9b34fb")))
    }

    @Test fun controller_detection_does_not_change_battery_detection() {
        // The NUS device must not become a BMS, and every existing battery
        // signal must still resolve exactly as before.
        assertNull(BmsTypeDetector.detect(null, nus))
        assertEquals(BmsType.JK_BMS, BmsTypeDetector.detect("JK_B2A8S20P", emptyList()))
        assertEquals(BmsType.ANT_BMS, BmsTypeDetector.detect("ANT-BLE24", emptyList()))
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(null, listOf("0000ff00-0000-1000-8000-00805f9b34fb")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.bms.BmsTypeDetectorVescTest"`
Expected: FAIL — unresolved `detectController`.

- [ ] **Step 3: Write minimal implementation**

Add to `BmsTypeDetector` (leaving `detect` untouched):
```kotlin
    /**
     * Controller detection, deliberately separate from [detect]: a controller is
     * not a BMS, and the Nordic UART service is generic enough (any nRF-based
     * gadget exposes it) that this is a CANDIDATE signal only — the picker
     * confirms it by a successful GET_VALUES_SETUP handshake. Kept out of
     * [detect] so no battery flow can ever be handed a controller type.
     */
    fun detectController(name: String?, serviceUuids: List<String>): ControllerType? {
        controllerNameMatch(name)?.let { return it }
        // A device that already looks like a known BMS is never a controller.
        if (detect(name, serviceUuids) != null) return null
        val hasNus = serviceUuids.any { it.equals(VescProtocol.NUS_SERVICE, ignoreCase = true) }
        return if (hasNus) ControllerType.VESC else null
    }

    private fun controllerNameMatch(name: String?): ControllerType? {
        if (name.isNullOrEmpty()) return null
        return when {
            name.contains("VESC", ignoreCase = true) ||
                name.startsWith("uBox", ignoreCase = true) ||
                name.startsWith("ubox", ignoreCase = true) -> ControllerType.VESC
            else -> null
        }
    }
```

Add `val controllerType: ControllerType? = null` to `DiscoveredDevice`, and fill it in `scanAll` alongside the existing `bmsType = BmsTypeDetector.detect(...)` with `controllerType = BmsTypeDetector.detectController(...)`. In the picker, label a device with a non-null `controllerType` as a controller (reuse the existing label helper; a one-line `when` is enough).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.bms.*"` then the full suite.
Expected: PASS — including the existing `BmsTypeDetectorTest` and `PickerComponentTest`.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/bms/BmsTypeDetector.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/repository/BmsRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/picker/ composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/bms/BmsTypeDetectorVescTest.kt
git commit -m "feat(vesc): detect VESC controllers in the scanner"
```

---

### Task 6: Units (metric / imperial)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/util/UnitFormatter.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/prefs/AppPrefs.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/util/UnitFormatterTest.kt`

**Interfaces:**
- Produces: `enum class UnitSystem { METRIC, IMPERIAL }`; `UnitFormatter.speed(kmh, system): String`, `.speedUnit(system): String`, `.distance(km, system, decimals: Int = 1): String`, `.distanceUnit(system): String`; `AppPrefs.unitSystem: StateFlow<UnitSystem>` + `suspend fun setUnitSystem(system: UnitSystem)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.util

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitFormatterTest {
    @Test fun metric_speed_passes_through_rounded() {
        assertEquals("47", UnitFormatter.speed(47.4f, UnitSystem.METRIC))
        assertEquals("km/h", UnitFormatter.speedUnit(UnitSystem.METRIC))
    }
    @Test fun imperial_speed_converts_to_mph() {
        assertEquals("29", UnitFormatter.speed(47.0f, UnitSystem.IMPERIAL))  // 47 km/h = 29.2 mph
        assertEquals("mph", UnitFormatter.speedUnit(UnitSystem.IMPERIAL))
    }
    @Test fun metric_distance_keeps_one_decimal() {
        assertEquals("1284.6", UnitFormatter.distance(1284.6f, UnitSystem.METRIC))
        assertEquals("km", UnitFormatter.distanceUnit(UnitSystem.METRIC))
    }
    @Test fun imperial_distance_converts_to_miles() {
        assertEquals("62.1", UnitFormatter.distance(100f, UnitSystem.IMPERIAL))  // 100 km = 62.14 mi
        assertEquals("mi", UnitFormatter.distanceUnit(UnitSystem.IMPERIAL))
    }
    @Test fun zero_and_negative_are_formatted_not_crashed() {
        assertEquals("0", UnitFormatter.speed(0f, UnitSystem.METRIC))
        assertEquals("0.0", UnitFormatter.distance(0f, UnitSystem.METRIC))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.util.UnitFormatterTest"`
Expected: FAIL — unresolved `UnitFormatter`, `UnitSystem`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package ru.sodovaya.volty.util

import kotlin.math.roundToInt

enum class UnitSystem { METRIC, IMPERIAL }

/**
 * Display-only unit conversion. ControllerData stays canonical (km/h, km, °C) —
 * nothing in the domain or data layer ever sees imperial values.
 */
object UnitFormatter {
    private const val KM_PER_MILE = 1.609344f

    fun speed(kmh: Float, system: UnitSystem): String =
        (if (system == UnitSystem.IMPERIAL) kmh / KM_PER_MILE else kmh).roundToInt().toString()

    fun speedUnit(system: UnitSystem): String =
        if (system == UnitSystem.IMPERIAL) "mph" else "km/h"

    fun distance(km: Float, system: UnitSystem, decimals: Int = 1): String =
        formatFixed(if (system == UnitSystem.IMPERIAL) km / KM_PER_MILE else km, decimals)

    fun distanceUnit(system: UnitSystem): String =
        if (system == UnitSystem.IMPERIAL) "mi" else "km"
}
```
(Reuse the repo's existing `formatFixed` from `util/NumberFormat.kt` — same package, no import needed.)

In `AppPrefs`, following the existing pattern exactly:
```kotlin
    val unitSystem: StateFlow<UnitSystem> = store.data
        .map { runCatching { UnitSystem.valueOf(it[Keys.UNIT_SYSTEM] ?: "METRIC") }.getOrDefault(UnitSystem.METRIC) }
        .stateIn(scope, SharingStarted.Eagerly, UnitSystem.METRIC)

    suspend fun setUnitSystem(system: UnitSystem) = store.edit { it[Keys.UNIT_SYSTEM] = system.name }
```
plus `val UNIT_SYSTEM = stringPreferencesKey("unit_system")` in `Keys`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.util.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/util/UnitFormatter.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/prefs/AppPrefs.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/util/UnitFormatterTest.kt
git commit -m "feat(ride): metric/imperial unit formatting + preference"
```

---

### Task 7: Duty bands + ride metrics (shared with Part F)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/DutyBands.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/RideMetrics.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/DutyBandsTest.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/RideMetricsTest.kt`

**Interfaces:**
- Produces: `enum class DutyLevel { NORMAL, WARN, CRITICAL }`; `DutyBands.DEFAULT_WARN_PERCENT = 75f`, `DEFAULT_CRITICAL_PERCENT = 90f`, `fun level(dutyPercent: Float, warnPercent: Float = …, criticalPercent: Float = …): DutyLevel`; `RideMetrics.instantWhPerKm(powerW, speedKmh): Float?`, `RideMetrics.sessionWhPerKm(consumedWh, tripKm): Float?`.

- [ ] **Step 1: Write the failing test**

```kotlin
// DutyBandsTest.kt
package ru.sodovaya.volty.domain.stats

import kotlin.test.Test
import kotlin.test.assertEquals

class DutyBandsTest {
    @Test fun bands_are_green_below_75_amber_to_90_red_above() {
        assertEquals(DutyLevel.NORMAL, DutyBands.level(0f))
        assertEquals(DutyLevel.NORMAL, DutyBands.level(74.9f))
        assertEquals(DutyLevel.WARN, DutyBands.level(75f))
        assertEquals(DutyLevel.WARN, DutyBands.level(89.9f))
        assertEquals(DutyLevel.CRITICAL, DutyBands.level(90f))
        assertEquals(DutyLevel.CRITICAL, DutyBands.level(100f))
    }
    @Test fun thresholds_are_overridable_for_per_vehicle_alert_config() {
        assertEquals(DutyLevel.CRITICAL, DutyBands.level(70f, warnPercent = 50f, criticalPercent = 65f))
        assertEquals(DutyLevel.WARN, DutyBands.level(55f, warnPercent = 50f, criticalPercent = 65f))
    }
}
```

```kotlin
// RideMetricsTest.kt
package ru.sodovaya.volty.domain.stats

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideMetricsTest {
    @Test fun instant_consumption_is_power_over_speed() {
        // 1000 W at 50 km/h = 20 Wh/km
        assertTrue(abs(RideMetrics.instantWhPerKm(1000f, 50f)!! - 20f) < 0.01f)
    }
    @Test fun instant_consumption_is_unknown_when_stopped() {
        assertNull(RideMetrics.instantWhPerKm(500f, 0f))
        assertNull(RideMetrics.instantWhPerKm(500f, 0.4f))   // creeping: still meaningless
    }
    @Test fun instant_consumption_is_absolute_so_regen_does_not_read_negative() {
        assertTrue(RideMetrics.instantWhPerKm(-800f, 40f)!! > 0f)
    }
    @Test fun session_consumption_divides_energy_by_distance() {
        assertTrue(abs(RideMetrics.sessionWhPerKm(980f, 58f)!! - 16.9f) < 0.05f)
    }
    @Test fun session_consumption_is_unknown_before_any_distance() {
        assertNull(RideMetrics.sessionWhPerKm(980f, 0f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.stats.DutyBandsTest" --tests "ru.sodovaya.volty.domain.stats.RideMetricsTest"`
Expected: FAIL — unresolved `DutyBands`, `RideMetrics`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package ru.sodovaya.volty.domain.stats

enum class DutyLevel { NORMAL, WARN, CRITICAL }

/**
 * Duty-cycle (ШИМ) severity bands. THE single source of truth: the Ride
 * dashboard colors its gauge from this and the Part F audible alarm escalates
 * from this, so the color a rider sees and the tone they hear can never
 * disagree. Thresholds are overridable because Part F makes them per-vehicle.
 */
object DutyBands {
    const val DEFAULT_WARN_PERCENT: Float = 75f
    const val DEFAULT_CRITICAL_PERCENT: Float = 90f

    fun level(
        dutyPercent: Float,
        warnPercent: Float = DEFAULT_WARN_PERCENT,
        criticalPercent: Float = DEFAULT_CRITICAL_PERCENT
    ): DutyLevel = when {
        dutyPercent >= criticalPercent -> DutyLevel.CRITICAL
        dutyPercent >= warnPercent -> DutyLevel.WARN
        else -> DutyLevel.NORMAL
    }
}
```

```kotlin
package ru.sodovaya.volty.domain.stats

import kotlin.math.abs

/** Pure ride math. Null means "not meaningful yet", never a fake 0. */
object RideMetrics {
    /** Below this the vehicle is standing still and Wh/km is a division blow-up. */
    private const val MIN_SPEED_KMH = 0.5f

    fun instantWhPerKm(powerW: Float, speedKmh: Float): Float? =
        if (speedKmh < MIN_SPEED_KMH) null else abs(powerW) / speedKmh

    fun sessionWhPerKm(consumedWh: Float, tripKm: Float): Float? =
        if (tripKm <= 0f) null else consumedWh / tripKm
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.stats.*"`
Expected: PASS (including the existing aggregator tests)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/DutyBands.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/stats/RideMetrics.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/DutyBandsTest.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/RideMetricsTest.kt
git commit -m "feat(ride): duty severity bands (shared with alerts) and ride metrics"
```

---

### Task 8: Per-vehicle dashboard config + v4→v5 migration

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/DashboardConfig.kt`
- Create: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/4.sqm`
- Modify: `composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/VehicleRow.sq`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Vehicle.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/prefs/AppPrefs.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepositoryDashboardTest.kt`

**Interfaces:**
- Produces: `enum class DashboardStyle { CLEAN, CLASSIC }`; `enum class SecondaryGauge { DUTY, BATTERY, POWER, CURRENT, MOTOR_TEMP, ESC_TEMP, CONSUMPTION }`; `Vehicle.dashboardStyle: DashboardStyle?` (null = follow the app default) and `Vehicle.secondaryGauge: SecondaryGauge` (default `DUTY`); `AppPrefs.defaultDashboardStyle: StateFlow<DashboardStyle>` + `setDefaultDashboardStyle`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.data.db

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepositoryDashboardTest {

    // Copy the in-memory driver helper from SqlDelightVehicleRepositoryTest.
    @Test fun dashboard_config_round_trips() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(
            Vehicle(
                id = "v1", name = "Wheel", iconKey = "generic",
                packs = listOf(Pack(0, "P", BmsType.ANT_BMS, "A")),
                controllers = listOf(Controller(0, "C", ControllerType.VESC, "C0")),
                chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now(),
                dashboardStyle = DashboardStyle.CLASSIC,
                secondaryGauge = SecondaryGauge.DUTY
            )
        )
        val back = repo.get("v1")!!
        assertEquals(DashboardStyle.CLASSIC, back.dashboardStyle)
        assertEquals(SecondaryGauge.DUTY, back.secondaryGauge)
    }

    @Test fun a_vehicle_saved_without_a_style_follows_the_app_default() = runTest {
        val repo = newInMemoryRepo()
        repo.upsert(
            Vehicle(
                id = "v2", name = "Scooter", iconKey = "generic",
                packs = listOf(Pack(0, "P", BmsType.ANT_BMS, "A")),
                chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now()
            )
        )
        val back = repo.get("v2")!!
        assertNull(back.dashboardStyle)                       // null = follow app default
        assertEquals(SecondaryGauge.DUTY, back.secondaryGauge) // enum default
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.db.SqlDelightVehicleRepositoryDashboardTest"`
Expected: FAIL — unresolved `DashboardStyle`/`SecondaryGauge`/the new `Vehicle` params.

- [ ] **Step 3: Write minimal implementation**

`DashboardConfig.kt`:
```kotlin
package ru.sodovaya.volty.domain.model

/** Which Ride renderer a vehicle uses. Null on a Vehicle = follow the app default. */
enum class DashboardStyle { CLEAN, CLASSIC }

/**
 * What the secondary gauge shows — the inner ring in Clean, the emphasised dial
 * in Classic. A wheel rider wants DUTY in the middle, a scooter rider BATTERY,
 * so this is stored per vehicle rather than app-wide.
 */
enum class SecondaryGauge { DUTY, BATTERY, POWER, CURRENT, MOTOR_TEMP, ESC_TEMP, CONSUMPTION }
```

`Vehicle.kt` — two additive params (defaults keep every call site compiling):
```kotlin
    val dashboardStyle: DashboardStyle? = null,
    val secondaryGauge: SecondaryGauge = SecondaryGauge.DUTY,
```

`VehicleRow.sq` — add the columns to the `CREATE TABLE` (after `isPinned`) and to `upsert`'s column list + `VALUES`:
```sql
    dashboardStyle           TEXT,
    secondaryGauge           TEXT
```

`4.sqm`:
```sql
-- v4 -> v5: the Ride dashboard is configurable per vehicle.
-- Both columns are nullable with no default: an existing vehicle follows the
-- app-level default style and the DUTY secondary gauge until the rider picks.
ALTER TABLE VehicleRow ADD COLUMN dashboardStyle TEXT;
ALTER TABLE VehicleRow ADD COLUMN secondaryGauge TEXT;
```

`SqlDelightVehicleRepository` — write both in `upsert` (`vehicle.dashboardStyle?.name`, `vehicle.secondaryGauge.name`) and read them in `toDomain`:
```kotlin
    dashboardStyle = dashboardStyle?.let { runCatching { DashboardStyle.valueOf(it) }.getOrNull() },
    secondaryGauge = secondaryGauge?.let { runCatching { SecondaryGauge.valueOf(it) }.getOrNull() }
        ?: SecondaryGauge.DUTY,
```

`AppPrefs` — the app-level default, same pattern as `unitSystem`:
```kotlin
    val defaultDashboardStyle: StateFlow<DashboardStyle> = store.data
        .map { runCatching { DashboardStyle.valueOf(it[Keys.DASHBOARD_STYLE] ?: "CLEAN") }.getOrDefault(DashboardStyle.CLEAN) }
        .stateIn(scope, SharingStarted.Eagerly, DashboardStyle.CLEAN)

    suspend fun setDefaultDashboardStyle(style: DashboardStyle) =
        store.edit { it[Keys.DASHBOARD_STYLE] = style.name }
```
plus `val DASHBOARD_STYLE = stringPreferencesKey("dashboard_style")`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.db.*"` then the full suite.
Expected: PASS — including the existing migration-chain test (`migratesAV2DatabaseToOnePackPerVehicle`, which targets `VoltyDatabase.Schema.version` and now runs `4.sqm` too).
Then **manually diff** the `CREATE TABLE VehicleRow` in `VehicleRow.sq` against the two `ALTER TABLE` statements in `4.sqm` (name, type, nullability) and record it in the report — the automated `verifyCommonMainVoltyDatabaseMigration` cannot run here (see Global Constraints).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/ composeApp/src/commonMain/sqldelight/ composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepository.kt composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/prefs/AppPrefs.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/db/SqlDelightVehicleRepositoryDashboardTest.kt
git commit -m "feat(ride): per-vehicle dashboard style and secondary gauge (v4->v5 migration)"
```

---

### Task 9: RadialGauge composable + secondary-gauge mapper

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/gauge/RadialGauge.kt`
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/SecondaryGaugeMapper.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/ride/SecondaryGaugeMapperTest.kt`

**Interfaces:**
- Consumes: `ControllerData`, `BmsData`, `SecondaryGauge`, `DutyBands`, `UnitSystem`.
- Produces: `@Composable fun RadialGauge(speedFraction: Float, secondaryFraction: Float, secondaryColor: Color, modifier: Modifier, content: @Composable BoxScope.() -> Unit)`; `data class SecondaryReadout(val label: String, val value: String, val unit: String, val fraction: Float, val severity: DutyLevel)`; `SecondaryGaugeMapper.map(gauge, motion, battery, units): SecondaryReadout`.

> The mapper is pure and unit-tested; the Canvas composable is verified by eye (this repo has no Compose UI test harness — same situation the picker screen is in).

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecondaryGaugeMapperTest {

    private val motion = ControllerData(
        speedKmh = 47f, dutyPercent = 76f, motorCurrentA = -82.5f, batteryCurrentA = 52.4f,
        inputVoltageV = 78.2f, powerW = 4098f, escTempC = 52f, motorTempC = 68f, hasMotorTemp = true,
        consumedWh = 980f, tripKm = 58f, isConnected = true
    )
    private val battery = BmsData(voltage = 78.2f, soc = 84f, socKnown = true, isConnected = true)

    @Test fun duty_maps_to_percent_and_carries_its_severity() {
        val r = SecondaryGaugeMapper.map(SecondaryGauge.DUTY, motion, battery, UnitSystem.METRIC)
        assertEquals("76", r.value)
        assertEquals("%", r.unit)
        assertTrue(abs(r.fraction - 0.76f) < 0.01f)
        assertEquals(DutyLevel.WARN, r.severity)
    }

    @Test fun battery_maps_to_state_of_charge() {
        val r = SecondaryGaugeMapper.map(SecondaryGauge.BATTERY, motion, battery, UnitSystem.METRIC)
        assertEquals("84", r.value)
        assertEquals("%", r.unit)
        assertTrue(abs(r.fraction - 0.84f) < 0.01f)
        assertEquals(DutyLevel.NORMAL, r.severity)
    }

    @Test fun power_is_shown_in_kilowatts() {
        val r = SecondaryGaugeMapper.map(SecondaryGauge.POWER, motion, battery, UnitSystem.METRIC)
        assertEquals("4.1", r.value)
        assertEquals("kW", r.unit)
    }

    @Test fun temperatures_carry_severity_from_their_own_ceiling() {
        val hot = motion.copy(motorTempC = 105f)
        val r = SecondaryGaugeMapper.map(SecondaryGauge.MOTOR_TEMP, hot, battery, UnitSystem.METRIC)
        assertEquals("105", r.value)
        assertEquals("°C", r.unit)
        assertEquals(DutyLevel.CRITICAL, r.severity)
    }

    @Test fun consumption_falls_back_to_a_dash_when_standing_still() {
        val stopped = motion.copy(speedKmh = 0f, consumedWh = 0f, tripKm = 0f)
        val r = SecondaryGaugeMapper.map(SecondaryGauge.CONSUMPTION, stopped, battery, UnitSystem.METRIC)
        assertEquals("—", r.value)
        assertEquals(0f, r.fraction)
    }

    @Test fun fractions_never_leave_zero_to_one() {
        val over = motion.copy(dutyPercent = 140f)
        assertEquals(1f, SecondaryGaugeMapper.map(SecondaryGauge.DUTY, over, battery, UnitSystem.METRIC).fraction)
        val under = motion.copy(dutyPercent = -20f)
        assertEquals(0f, SecondaryGaugeMapper.map(SecondaryGauge.DUTY, under, battery, UnitSystem.METRIC).fraction)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.ride.SecondaryGaugeMapperTest"`
Expected: FAIL — unresolved `SecondaryGaugeMapper`.

- [ ] **Step 3: Write minimal implementation**

`SecondaryGaugeMapper.kt`:
```kotlin
package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.stats.DutyBands
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.domain.stats.RideMetrics
import ru.sodovaya.volty.util.UnitSystem
import ru.sodovaya.volty.util.formatFixed
import kotlin.math.abs
import kotlin.math.roundToInt

data class SecondaryReadout(
    val label: String,
    val value: String,
    val unit: String,
    /** 0..1 for the ring; 0 when the value is unknown. */
    val fraction: Float,
    val severity: DutyLevel
)

/**
 * Turns the rider's chosen secondary metric into everything the gauge needs.
 * Pure, so the choice logic is tested without a screen. Severity is what colors
 * the ring: duty uses the shared [DutyBands], temperatures their own ceilings,
 * everything else is neutral — semantic color is spent only on safety.
 */
object SecondaryGaugeMapper {

    private const val ESC_WARN_C = 70f
    private const val ESC_CRITICAL_C = 85f
    private const val MOTOR_WARN_C = 85f
    private const val MOTOR_CRITICAL_C = 100f
    private const val MAX_CURRENT_A = 150f
    private const val MAX_POWER_W = 8000f
    private const val MAX_WH_PER_KM = 50f

    fun map(
        gauge: SecondaryGauge,
        motion: ControllerData,
        battery: BmsData,
        units: UnitSystem
    ): SecondaryReadout = when (gauge) {
        SecondaryGauge.DUTY -> SecondaryReadout(
            "DUTY · ШИМ", motion.dutyPercent.roundToInt().toString(), "%",
            frac(motion.dutyPercent, 100f), DutyBands.level(motion.dutyPercent)
        )
        SecondaryGauge.BATTERY -> SecondaryReadout(
            "BATTERY",
            if (battery.socKnown) battery.soc.roundToInt().toString() else "—", "%",
            if (battery.socKnown) frac(battery.soc, 100f) else 0f,
            DutyLevel.NORMAL
        )
        SecondaryGauge.POWER -> SecondaryReadout(
            "POWER", formatFixed(motion.powerW / 1000f, 1), "kW",
            frac(abs(motion.powerW), MAX_POWER_W), DutyLevel.NORMAL
        )
        SecondaryGauge.CURRENT -> SecondaryReadout(
            "CURRENT", motion.batteryCurrentA.roundToInt().toString(), "A",
            frac(abs(motion.batteryCurrentA), MAX_CURRENT_A), DutyLevel.NORMAL
        )
        SecondaryGauge.MOTOR_TEMP -> SecondaryReadout(
            "MOTOR",
            if (motion.hasMotorTemp) motion.motorTempC.roundToInt().toString() else "—", "°C",
            if (motion.hasMotorTemp) frac(motion.motorTempC, MOTOR_CRITICAL_C + 20f) else 0f,
            tempLevel(motion.motorTempC, MOTOR_WARN_C, MOTOR_CRITICAL_C, motion.hasMotorTemp)
        )
        SecondaryGauge.ESC_TEMP -> SecondaryReadout(
            "ESC", motion.escTempC.roundToInt().toString(), "°C",
            frac(motion.escTempC, ESC_CRITICAL_C + 20f),
            tempLevel(motion.escTempC, ESC_WARN_C, ESC_CRITICAL_C, true)
        )
        SecondaryGauge.CONSUMPTION -> {
            val wh = RideMetrics.instantWhPerKm(motion.powerW, motion.speedKmh)
                ?: RideMetrics.sessionWhPerKm(motion.consumedWh, motion.tripKm)
            SecondaryReadout(
                "CONSUMPTION", wh?.let { formatFixed(it, 1) } ?: "—", "Wh/km",
                wh?.let { frac(it, MAX_WH_PER_KM) } ?: 0f, DutyLevel.NORMAL
            )
        }
    }

    private fun frac(value: Float, max: Float): Float =
        if (max <= 0f) 0f else (value / max).coerceIn(0f, 1f)

    private fun tempLevel(c: Float, warn: Float, critical: Float, known: Boolean): DutyLevel = when {
        !known -> DutyLevel.NORMAL
        c >= critical -> DutyLevel.CRITICAL
        c >= warn -> DutyLevel.WARN
        else -> DutyLevel.NORMAL
    }
}
```

`RadialGauge.kt` — a Canvas concentric gauge, 270° sweep starting at 135°:
```kotlin
package ru.sodovaya.volty.presentation.ride.gauge

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private const val START_ANGLE = 135f
private const val SWEEP = 270f

/**
 * The Clean hero: SPEED on the outer arc, the rider's chosen metric on the
 * inner one, with the readout composed in the middle. Two concentric rings
 * rather than two gauges because the two numbers a rider must not miss belong
 * in one glance (see the Part B spec, §7).
 */
@Composable
fun RadialGauge(
    speedFraction: Float,
    secondaryFraction: Float,
    speedColor: Color,
    secondaryColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val speed by animateFloatAsState(
        targetValue = speedFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220), label = "speedArc"
    )
    val secondary by animateFloatAsState(
        targetValue = secondaryFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220), label = "secondaryArc"
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val outerWidth = 14.dp.toPx()
            val innerWidth = 12.dp.toPx()
            val gap = 10.dp.toPx()

            fun ring(inset: Float, width: Float, color: Color, fraction: Float) {
                val topLeft = Offset(inset + width / 2f, inset + width / 2f)
                val side = size.minDimension - 2f * (inset + width / 2f)
                val arcSize = Size(side, side)
                drawArc(trackColor, START_ANGLE, SWEEP, false, topLeft, arcSize, style = Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                if (fraction > 0f) {
                    drawArc(color, START_ANGLE, SWEEP * fraction, false, topLeft, arcSize, style = Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
            ring(0f, outerWidth, speedColor, speed)
            ring(outerWidth + gap, innerWidth, secondaryColor, secondary)
        }
        content()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.ride.*"` and `./gradlew :composeApp:compileDebugKotlinAndroid`.
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/ composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/ride/
git commit -m "feat(ride): concentric radial gauge and secondary-gauge mapping"
```

---

### Task 10: RideDashboardComponent

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardComponent.kt`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardComponentTest.kt`

**Interfaces:**
- Consumes: `BmsRepository.activeMotion` / `activeVehicleData` / `activeVehicle` / `connectionState`, `AppPrefs.unitSystem` + `defaultDashboardStyle`, `SecondaryGaugeMapper`, `RideMetrics`.
- Produces: `interface RideDashboardComponent { val state: StateFlow<State>; fun onPillClicked(); fun onSheetDismiss(); fun onSwitchVehicle(v: Vehicle); fun onTabClicked(tab: Tab); fun onOpenSettings(); fun onDisconnect() }` with `State(vehicle, motion, battery, motionPartial, connection, units, style, secondary, secondaryReadout, sessionWhPerKm, uptimeSeconds, savedVehicles, sheetOpen)` and `enum class Tab { Ride, Battery, Settings }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.presentation.ride

import app.cash.turbine.test
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RideDashboardComponentTest {

    // Reuse the FakeBmsRepo pattern from DashboardComponentPacksTest — it already
    // implements every BmsRepository member, including activeMotion.
    @Test fun motion_reaches_the_state() = runTest {
        val repo = FakeBmsRepo()
        val c = component(repo)
        repo.emitMotion(ControllerData(speedKmh = 47f, speedSource = SpeedSource.REPORTED, dutyPercent = 76f, isConnected = true))
        c.state.test {
            val s = awaitItem()
            assertEquals(47f, s.motion.speedKmh)
            assertTrue(s.motion.speedKnown)
        }
    }

    @Test fun the_secondary_readout_follows_the_vehicles_choice() = runTest {
        val repo = FakeBmsRepo()
        val c = component(repo, secondary = SecondaryGauge.BATTERY)
        repo.emitMotion(ControllerData(dutyPercent = 76f, isConnected = true))
        c.state.test {
            assertEquals("BATTERY", awaitItem().secondaryReadout.label)
        }
    }

    @Test fun a_vehicle_style_overrides_the_app_default() = runTest {
        // App default CLEAN, vehicle CLASSIC → state.style is CLASSIC.
        val c = component(FakeBmsRepo(), vehicleStyle = DashboardStyle.CLASSIC, appDefault = DashboardStyle.CLEAN)
        c.state.test { assertEquals(DashboardStyle.CLASSIC, awaitItem().style) }
    }

    @Test fun a_vehicle_without_a_style_follows_the_app_default() = runTest {
        val c = component(FakeBmsRepo(), vehicleStyle = null, appDefault = DashboardStyle.CLASSIC)
        c.state.test { assertEquals(DashboardStyle.CLASSIC, awaitItem().style) }
    }

    @Test fun session_consumption_is_derived_from_energy_and_trip() = runTest {
        val repo = FakeBmsRepo()
        val c = component(repo)
        repo.emitMotion(ControllerData(consumedWh = 980f, tripKm = 58f, isConnected = true))
        c.state.test {
            val s = awaitItem()
            assertTrue(kotlin.math.abs(s.sessionWhPerKm!! - 16.9f) < 0.05f)
        }
    }
}
```
(Write the `component(...)` helper and extend the copied `FakeBmsRepo` with an `emitMotion` that pushes into its `activeMotion` MutableStateFlow.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.ride.RideDashboardComponentTest"`
Expected: FAIL — unresolved `RideDashboardComponent`.

- [ ] **Step 3: Write minimal implementation**

Follow `DefaultDashboardComponent` exactly (a `CoroutineScope(Dispatchers.Main + SupervisorJob())` cancelled in `lifecycle.doOnDestroy`, a `MutableStateFlow<State>` seeded from the repository's current values, one `scope.launch` collector per source). Collect: `activeMotion`, `activeVehicleData` (for `battery = it.aggregate` and `motionPartial`), `activeVehicle`, `connectionState`, `vehicleRepository.vehicles`, `appPrefs.unitSystem`, `appPrefs.defaultDashboardStyle`.

Derive on each update:
```kotlin
    style = vehicle?.dashboardStyle ?: appDefaultStyle,
    secondary = vehicle?.secondaryGauge ?: SecondaryGauge.DUTY,
    secondaryReadout = SecondaryGaugeMapper.map(secondary, motion, battery, units),
    sessionWhPerKm = RideMetrics.sessionWhPerKm(motion.consumedWh, motion.tripKm),
```
Uptime: record the `Instant` of the first sample of a connection (reset when `connectionState` leaves `Connected`) and set `uptimeSeconds = (motion.timestamp - startedAt).inWholeSeconds` on each motion sample — no timer, the sample rate drives it.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.*"`
Expected: PASS (new tests + the existing dashboard/picker component tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardComponent.kt composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardComponentTest.kt
git commit -m "feat(ride): RideDashboardComponent — motion, units and per-vehicle gauge choice"
```

---

### Task 11: RideDashboardScreen (Clean)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardScreen.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`, `values-ru/strings.xml`

**Interfaces:**
- Consumes: `RideDashboardComponent.State`, `RadialGauge`, `SecondaryReadout`, `UnitFormatter`, `VehiclePill`, `MetricCard`, `SparklineGraph`, `DutyLevel`.
- Produces: `@Composable fun RideDashboardScreen(component: RideDashboardComponent)`.

- [ ] **Step 1: Build the screen** (no unit test — this repo has no Compose UI test harness; correctness of the *data* is covered by Tasks 9–10, and the layout is verified by running the demo in Task 14)

Compose, top → bottom, per the locked design (`docs/design/ride-dashboard-mockup.html`, Clean view):
1. `VehiclePill` (reuse from `presentation/common/`) — vehicle identity + connection state; tap opens the vehicle sheet (reuse `VehicleSheet`).
2. **Hero** — `RadialGauge` sized ~250.dp:
   - `speedFraction = motion.speedKmh / vehicleMaxSpeed` where `vehicleMaxSpeed` is `max(70f, observed session max rounded up to 10)` — a local `remember` in the screen is fine; never divide by zero.
   - `secondaryFraction`/`secondaryColor` from `state.secondaryReadout` (`severity` → `MaterialTheme.colorScheme` mapping below).
   - Center content: unit label (`UnitFormatter.speedUnit`), the big speed (`UnitFormatter.speed`, `displayLarge`, `tabular` figures) or `—` when `!motion.speedKnown`, then the secondary readout's label/value/unit.
3. **2×2 cluster** of `MetricCard`: Power (kW), Battery (% + V), ESC °C, Motor °C (`—` when `!hasMotorTemp`). Temp cards carry a small severity dot.
4. **Consumption card** — instant Wh/km big, `avg <session>` small, with a `SparklineGraph` of recent speed.
5. **Strip** — odometer / trip / uptime, monospace-ish with `FontFamily.Monospace` and tabular figures; distances via `UnitFormatter.distance`.

Severity → color (semantic only, never the brand accent):
```kotlin
@Composable
private fun severityColor(level: DutyLevel): Color = when (level) {
    DutyLevel.NORMAL -> MaterialTheme.colorScheme.primary
    DutyLevel.WARN -> MaterialTheme.colorScheme.tertiary
    DutyLevel.CRITICAL -> MaterialTheme.colorScheme.error
}
```

All user-visible strings go through `stringResource` with entries added to **both** `values/strings.xml` and `values-ru/strings.xml` (follow the existing keys' naming, e.g. `ride_speed_unknown`, `ride_odometer`, `ride_trip`, `ride_uptime`, `ride_consumption`, `ride_power`, `ride_battery`, `ride_esc_temp`, `ride_motor_temp`).

- [ ] **Step 2: Verify it compiles and renders**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL. (Visual check happens in Task 14 via the demo vehicle.)

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ride/RideDashboardScreen.kt composeApp/src/commonMain/composeResources/
git commit -m "feat(ride): Clean Ride dashboard screen"
```

---

### Task 12: Navigation — Ride / Battery / Settings

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootComponent.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/root/RootScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/dashboard/DashboardComponent.kt` (+ its screen's bottom bar)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/root/RootNavigationTest.kt`

**Interfaces:**
- Produces: `RootComponent.Tab { Ride, Battery, Settings }`; `Config.Ride`; `RootComponent.Child.Ride(component: RideDashboardComponent)`; `DashboardComponent.Tab { Ride, Battery, Settings }`.

**Named requirements:**
- `Config.Ride` builds a `DefaultRideDashboardComponent`; `Config.Dashboard` (the existing battery screen) is now reached by the **Battery** tab — rename the tab, not the component.
- After a successful connect, route to `Config.Ride` **when the connected vehicle has controllers**, else `Config.Dashboard`. There are several `nav.replaceAll(Config.Dashboard)` call sites (AutoConnect `onConnected`, Picker `onConnectedKnown` / `onConnectedGuestNoSave` / `onDemoConnected`, VehicleEdit `onSaved`) — route them all through one helper:
  ```kotlin
  private fun homeConfig(): Config =
      if (bmsRepository.activeVehicle.value?.hasControllers == true) Config.Ride else Config.Dashboard
  ```
- The Ride tab is hidden for a controller-less vehicle; tapping Battery from Ride uses `nav.bringToFront(Config.Dashboard)`.
- `Graph` stops being a top-level tab: keep `Config.Graph` reachable from a button on both dashboards. Update `onBack` so Graph/Settings return to the current home (`homeConfig()`), not unconditionally to `Config.Dashboard`.
- `RootScreen`'s `when` over `Child` is exhaustive — add the `Ride` branch.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.presentation.root

import kotlin.test.Test
import kotlin.test.assertEquals

class RootNavigationTest {
    @Test fun home_is_ride_for_a_vehicle_with_a_controller() {
        // Build a RootComponent over a fake repo whose activeVehicle has controllers;
        // drive a connect; assert the active child is Child.Ride.
    }
    @Test fun home_is_battery_for_a_pure_bms_vehicle() {
        // Same, with a controller-less vehicle; assert Child.Dashboard.
    }
    @Test fun the_battery_tab_reaches_the_existing_dashboard() {
        // onTab(Tab.Battery) → Child.Dashboard.
    }
}
```
(Follow whatever construction the existing component tests use for a `ComponentContext`; if `RootComponent` proves impractical to instantiate in a unit test, assert the same three rules against the extracted pure `homeConfig()`-style helper instead and say so in the report — the routing rule is the thing under test, not Decompose.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.root.RootNavigationTest"`
Expected: FAIL — no `Config.Ride` / `Child.Ride`.

- [ ] **Step 3: Write minimal implementation**

Apply the named requirements above.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest` (full) and `./gradlew :composeApp:compileDebugKotlinAndroid`.
Expected: PASS / BUILD SUCCESSFUL — every existing navigation-dependent test stays green.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/
git commit -m "feat(ride): Ride/Battery/Settings tabs with controller-aware home"
```

---

### Task 13: Settings — units, default style, per-vehicle gauge

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/settings/SettingsComponent.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/settings/SettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/vehicle/VehicleEditComponent.kt` + `VehicleEditScreen.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`, `values-ru/strings.xml`
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/settings/SettingsUnitsTest.kt`

**Interfaces:**
- Consumes: `AppPrefs.unitSystem` / `setUnitSystem` / `defaultDashboardStyle` / `setDefaultDashboardStyle`; `Vehicle.dashboardStyle` / `secondaryGauge`; `VehicleRepository.upsert`.
- Produces: settings state gains `unitSystem: UnitSystem` + `defaultDashboardStyle: DashboardStyle` with `onUnitSystemChanged` / `onDefaultDashboardStyleChanged`; vehicle-edit state gains `dashboardStyle: DashboardStyle?` + `secondaryGauge: SecondaryGauge` with their setters.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.presentation.settings

import app.cash.turbine.test
import ru.sodovaya.volty.util.UnitSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsUnitsTest {
    @Test fun choosing_imperial_persists_and_shows_in_state() = runTest {
        // Build DefaultSettingsComponent over an in-memory AppPrefs (copy the
        // existing settings-test setup, or an in-memory DataStore).
        val c = settingsComponent()
        c.onUnitSystemChanged(UnitSystem.IMPERIAL)
        c.state.test { assertEquals(UnitSystem.IMPERIAL, awaitItem().unitSystem) }
    }
}
```
If no settings-component test exists to copy a harness from, test `AppPrefs` directly instead (`setUnitSystem` → `unitSystem` emits IMPERIAL) and note the substitution in the report.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.presentation.settings.SettingsUnitsTest"`
Expected: FAIL — no `unitSystem` on the settings state.

- [ ] **Step 3: Write minimal implementation**

- **Settings screen:** a "Units" row (Metric / Imperial segmented choice) and a "Dashboard style" row (Clean / Classic) writing the app default. Follow the existing settings rows' composition exactly.
- **Vehicle edit:** a "Dashboard style" row (Default / Clean / Classic — "Default" stores `null`) and an "Inner gauge" row (the seven `SecondaryGauge` values as chips). Saving writes them through the existing `vehicleRepository.upsert`.
- Add every new string to both `strings.xml` files.
- Classic is not implemented yet (Part B2): when the chosen style is `CLASSIC`, the Ride screen renders Clean and the picker row shows a "coming soon" caption. Keep the enum value selectable so the persistence path is exercised end-to-end now.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:testDebugUnitTest` (full) and `./gradlew :composeApp:compileDebugKotlinAndroid`.
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/ composeApp/src/commonMain/composeResources/ composeApp/src/commonTest/kotlin/ru/sodovaya/volty/presentation/settings/
git commit -m "feat(ride): unit, dashboard-style and inner-gauge settings"
```

---

### Task 14: Demo drives the Ride dashboard end to end

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt` (the demo vehicle)
- Test: `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/demo/DemoRideTest.kt`

**Interfaces:**
- Consumes: the Part A demo motion path (`DEMO_CONTROLLER`, `_activeMotion`), `RideDashboardComponent`.

- [ ] **Step 1: Write the failing test**

```kotlin
package ru.sodovaya.volty.data.demo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class DemoRideTest {
    @Test fun the_demo_vehicle_has_a_controller_so_ride_is_its_home() = runTest {
        // DEMO_VEHICLE.hasControllers must be true (Part A added DEMO_CONTROLLER).
    }
    @Test fun connecting_the_demo_produces_motion_on_activeMotion() = runTest {
        // repo.connectDemo(); assert activeMotion eventually reports a non-zero
        // speed with SpeedSource.REPORTED — the full Ride data path with no hardware.
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.demo.DemoRideTest"`
Expected: FAIL (or PASS immediately for the first assertion — Part A already added the demo controller; then only the second needs work).

- [ ] **Step 3: Make it pass**

Whatever the demo needs so that `connectDemo()` lands on the Ride screen with live synthetic motion. Part A already emits demo motion into `_activeMotion`; the likely gap is only the routing rule from Task 12 (`homeConfig()`), so this may be a test-only task — say so in the report if no production change is needed.

- [ ] **Step 4: Run the whole suite and the app**

Run: `./gradlew :composeApp:testDebugUnitTest` and `./gradlew :composeApp:assembleDebug`.
Expected: PASS / BUILD SUCCESSFUL.
Then **visually verify** the Clean dashboard by launching the app and tapping "Try demo": the speedo sweeps, the inner ring follows the chosen metric, the cluster and strip update, and switching the vehicle's inner gauge in settings changes the ring. Report what you saw.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(ride): demo drives the Ride dashboard end to end"
```

---

## Self-Review

**Spec coverage (`B-vesc-dashboard.md` → task):**
- §2 VESC BLE transport (NUS, framing, CRC16-CCITT, reassembly) → Tasks 1, 3 ✓
- §3 `VescProtocol`, SETUP primary poll, GET_VALUES fallback, `MotionSource` → Tasks 2, 3 ✓
- §4 fault names → Task 2 (`VescFaults`) ✓
- §5 derived battery (battery_level path + VoltageSocEstimator path via `socKnown = false`) → Task 3 ✓
- §6 scanner/detection → Task 5 ✓
- §7 Ride dashboard, Clean renderer, `RadialGauge` → Tasks 9, 10, 11 ✓
- §7.2 configurable secondary gauge → Tasks 8, 9, 13 ✓
- §7.3 per-vehicle persistence + v4→v5 migration → Task 8 ✓
- §7.4 duty bands shared with Part F → Task 7 ✓
- §8 navigation Ride/Battery/Settings, controller-aware home → Task 12 ✓
- §9 units → Tasks 6, 13 ✓
- §10 testing → every task; demo end-to-end → Task 14 ✓
- **Deliberately deferred to Part B2:** §7.1 `DialGauge` / `ClusterLayout` (the Classic renderer). Task 13 keeps `CLASSIC` selectable and persisted so B2 is a pure rendering addition. Also deferred (spec §11): `COMM_GET_MCCONF` auto-read of `MotorConfig`.

**Placeholder scan:** Tasks 4, 10, 12, 13, 14 give test *sketches* rather than complete runnable test code, because their harnesses (faked BLE sessions, Decompose `ComponentContext`, DataStore) are bespoke in this repo and must be copied from the named existing test. This is flagged inline in each, the assertions are specified exactly, and all production code is complete. Every other task carries full test + implementation code.

**Type consistency:** `ControllerData.batteryLevelFraction` is introduced in Task 2 and consumed in Task 3. `SecondaryReadout(label, value, unit, fraction, severity)` is identical across Tasks 9/10/11. `DutyBands.level(...)`/`DutyLevel` identical across Tasks 7/9/11. `UnitFormatter.speed/speedUnit/distance/distanceUnit` identical across Tasks 6/11. `DashboardStyle`/`SecondaryGauge` identical across Tasks 8/9/10/13. `createProtocol(spec, vehicle)` is Task 4's only new internal signature. `RootComponent.Tab { Ride, Battery, Settings }` matches `DashboardComponent.Tab` in Task 12.

**Scope:** one coherent slice — after Task 14 a rider can connect a VESC and ride with the Clean dashboard, style/gauge stored per vehicle. Classic is the follow-on plan.
