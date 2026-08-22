package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.math.abs

/**
 * Read-only KingSong EUC telemetry.
 *
 * KingSong wheels stream fixed 20-byte records on the Nordic UART-style
 * `0000ffe0/0000ffe1` service; they do not need a request to produce data.
 * Every record starts `AA 55`, has its message type at byte 16 and ends
 * `5A 5A`.  The public sources do **not** describe a checksum: the tail is a
 * framing sentinel, not a checksum field, so this decoder rejects a bad tail
 * but never invents a CRC calculation.
 *
 * The layout below is based on the read path in
 * [WheelLog's KingsongAdapter](https://github.com/Wheellog/Wheellog.Android/blob/master/app/src/main/java/com/cooper/wheellog/utils/KingsongAdapter.java)
 * and the independent parser/tests in
 * [EUC-Dash-ESP32](https://github.com/Pickelhaupt/EUC-Dash-ESP32) and
 * [euc_ble_library](https://github.com/Tritbool/euc_ble_library).  The
 * sources agree on the frame header/tail, scales and BMS page numbers; the
 * A9 distance field is the deliberate non-standard byte order documented in
 * [kingSongDistance].  No command is sent: some of the same bytes are
 * KingSong's light, calibration and power-off channel, and a write would not
 * be read-only.
 *
 * Frame `A9` is wheel-level live telemetry: voltage (bytes 2..3, 0.01 V),
 * signed current (10..11, 0.01 A), board temperature (12..13, 0.01 °C), and
 * the lifetime distance at bytes 6..9.  `B9` supplies the latched controller
 * temperature and `F5` byte 15 supplies a reported duty percentage.  A9 byte
 * 15 is normally the `E0` pedal-mode marker rather than a reliable fuel
 * gauge; therefore this protocol leaves SoC unknown for an A9-only wheel.
 * Smart BMS frames `F1` and `F2` carry one battery each.  Page `00` supplies
 * voltage/current, remaining/factory capacity and cycle count (the latter
 * is byte 10), page `01` has seven temperatures in deci-kelvin, and pages
 * `02..06` contain cell readings, with page `06` offset 10 carrying the
 * eighth temperature.  Cell and temperature pages are accumulated until a
 * page-00 voltage exists; this prevents publishing a cell-only sample with an
 * unearned zero pack voltage.
 *
 * This class implements [MotionSource] because A9 also reports ground speed
 * and the lifetime distance.  Those values stay out of [BmsData] and are
 * exposed only through [latestMotion].
 */
class KingSongProtocol : BmsProtocol(), MotionSource {

    override val uuids = BmsUuids(
        serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
        notifyCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
        // The characteristic is physically capable of writes, but this
        // protocol intentionally never uses it (see [handshakeCommands]).
        writeCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    )

    /** KingSong's A9 stream needs no handshake and no poll. */
    override fun handshakeCommands(): List<ByteArray> = emptyList()

    /** Never write to FFE1: it is also the wheel command channel. */
    override fun pollCommands(): List<ByteArray> = emptyList()

    override val pollIntervalMs: Long = 0L

    /** F1 and F2 are the two smart-BMS units found on dual-battery wheels. */
    override val packCount: Int get() = 2

    private val buffer = ByteArrayAccumulator()
    private val bms = Array(2) { BmsState() }
    private var smartBmsSeen = false
    private var liveData: BmsData? = null
    private var motion: ControllerData? = null
    private var distanceBaselineMeters: Long? = null
    private var rideStatsTemperatureC: Float? = null
    private var dutyPercentValue: Float? = null

    private data class BmsState(
        var voltageV: Float? = null,
        var currentA: Float? = null,
        var socPercent: Float? = null,
        var cycleCount: Int? = null,
        val cellsMv: Array<Int?> = arrayOfNulls(MAX_CELLS),
        val temperaturesC: MutableList<Float> = mutableListOf(),
        var eighthTemperatureC: Float? = null,
        var seen: Boolean = false
    ) {
        fun reset() {
            voltageV = null
            currentA = null
            socPercent = null
            cycleCount = null
            cellsMv.fill(null)
            temperaturesC.clear()
            eighthTemperatureC = null
            seen = false
        }

        /**
         * A summary voltage is required before this state becomes a sample;
         * cell-only pages must not turn the model's zero voltage into a claim.
         */
        fun toData(): BmsData? {
            val voltage = voltageV ?: return null
            val current = currentA
            return BmsData(
                voltage = voltage,
                current = current ?: 0f,
                hasCurrent = current != null,
                // KingSong BMS does not report a power field.  Keep this
                // explicitly absent instead of deriving a headline number.
                power = 0f,
                hasPower = false,
                soc = socPercent ?: 0f,
                socKnown = socPercent != null,
                numCycles = cycleCount ?: 0,
                cellVoltages = cellsMv.mapNotNull { it?.let { mv -> mv / 1000f } },
                temperatures = temperaturesC + listOfNotNull(eighthTemperatureC),
                isConnected = true
            )
        }
    }

    override fun onNotification(data: ByteArray) {
        buffer.append(data)
        parseBufferedFrames()
    }

    override fun latestData(packIndex: Int): BmsData? {
        if (packIndex !in bms.indices) return null
        bms[packIndex].toData()?.let { return it }
        // Before a smart-BMS frame is observed, A9 is the only battery
        // evidence. Once any F1/F2 page arrives, do not mix wheel-level A9
        // values into a missing second smart-BMS pack.
        return if (packIndex == 0 && !smartBmsSeen) liveData else null
    }

    override val controllerCount: Int get() = 1

    override fun latestMotion(controllerIndex: Int): ControllerData? =
        if (controllerIndex == 0) motion else null

    override fun reset() {
        buffer.reset()
        bms.forEach(BmsState::reset)
        smartBmsSeen = false
        liveData = null
        motion = null
        distanceBaselineMeters = null
        rideStatsTemperatureC = null
        dutyPercentValue = null
    }

    private fun parseBufferedFrames() {
        while (true) {
            val bytes = buffer.toByteArray()
            val start = findHeader(bytes)
            if (start < 0) {
                // Preserve a possible first AA of a split header.
                if (bytes.size > 1) buffer.trimLeading(bytes.size - 1)
                return
            }
            if (start > 0) buffer.trimLeading(start)
            if (buffer.size < FRAME_SIZE) return

            val frame = buffer.toByteArray().copyOfRange(0, FRAME_SIZE)
            if (!hasValidTail(frame)) {
                // A bad candidate may contain a new AA55 inside it. Advance
                // one byte, not twenty, so the next real frame can recover.
                buffer.trimLeading(1)
                continue
            }
            parseFrame(frame)
            buffer.trimLeading(FRAME_SIZE)
        }
    }

    private fun findHeader(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i].u8() == HEADER_1 && bytes[i + 1].u8() == HEADER_2) return i
        }
        return -1
    }

    private fun hasValidTail(frame: ByteArray): Boolean =
        frame[TAIL_1_OFFSET].u8() == TAIL_BYTE && frame[TAIL_2_OFFSET].u8() == TAIL_BYTE

    private fun parseFrame(frame: ByteArray) {
        when (frame[MESSAGE_TYPE_OFFSET].u8()) {
            TYPE_LIVE -> parseLive(frame)
            TYPE_RIDE_STATS -> parseRideStats(frame)
            TYPE_OUTPUT -> parseOutput(frame)
            TYPE_BMS_1, TYPE_BMS_2 -> parseBms(frame)
            // Name, serial, speed-limit, alarm and fan records carry no
            // BmsData fields this read-only adapter promises.
            else -> Unit
        }
    }

    private fun parseLive(frame: ByteArray) {
        val voltage = frame.u16Le(2) / 100f
        val current = frame.i16Le(10) / 100f
        val temperature = frame.i16Le(12) / 100f
        if (voltage !in 40f..180f || current !in -200f..200f || temperature !in -40f..100f) {
            return
        }

        // Byte 15 is normally E0 (the mode marker), not a measured SoC.
        liveData = BmsData(
            voltage = voltage,
            current = current,
            hasCurrent = true,
            power = 0f,
            hasPower = false,
            soc = 0f,
            socKnown = false,
            temperatures = listOf(temperature),
            isConnected = true
        )

        val speedKmh = abs(frame.i16Le(4) / 100f)
        val distanceMeters = frame.kingSongDistance(6)
        val baseline = distanceBaselineMeters ?: distanceMeters.also { distanceBaselineMeters = it }
        motion = ControllerData(
            speedKmh = speedKmh,
            speedSource = SpeedSource.REPORTED,
            dutyPercent = dutyPercentValue ?: 0f,
            hasDuty = dutyPercentValue != null,
            // BmsData uses + = charging; ControllerData uses + = drawing.
            batteryCurrentA = -current,
            hasBatteryCurrent = true,
            inputVoltageV = voltage,
            hasInputVoltage = true,
            powerW = voltage * -current,
            hasPower = true,
            escTempC = rideStatsTemperatureC ?: temperature,
            hasMotorTemp = false,
            odometerKm = distanceMeters / 1000f,
            tripKm = (distanceMeters - baseline).coerceAtLeast(0L) / 1000f,
            hasDistance = true,
            hasEnergyCounters = false,
            isConnected = true
        )
    }

    private fun parseRideStats(frame: ByteArray) {
        val temperature = frame.i16Le(14) / 100f
        if (temperature !in -40f..150f) return
        rideStatsTemperatureC = temperature
        motion = motion?.copy(escTempC = temperature)
    }

    private fun parseOutput(frame: ByteArray) {
        val duty = frame[15].u8().toFloat()
        if (duty !in 0f..100f) return
        dutyPercentValue = duty
        motion = motion?.copy(dutyPercent = duty, hasDuty = true)
    }

    private fun parseBms(frame: ByteArray) {
        val bmsIndex = frame[MESSAGE_TYPE_OFFSET].u8() - TYPE_BMS_1
        if (bmsIndex !in bms.indices) return
        val state = bms[bmsIndex]

        when (frame[PAGE_OFFSET].u8()) {
            PAGE_SUMMARY -> {
                val voltage = frame.u16Le(2) / 100f
                val current = frame.i16Le(4) / 100f
                if (voltage !in 40f..180f || current !in -200f..200f) return
                val remainingRaw = frame.u16Le(6)
                val factoryRaw = frame.u16Le(8)
                state.voltageV = voltage
                state.currentA = current
                state.cycleCount = frame.u16Le(10)
                state.socPercent = if (factoryRaw > 0) {
                    (remainingRaw.toFloat() * 100f / factoryRaw).coerceIn(0f, 100f)
                } else {
                    null
                }
                state.seen = true
                smartBmsSeen = true
            }

            PAGE_TEMPERATURES -> {
                val values = buildList {
                    repeat(TEMPERATURE_COUNT) { i ->
                        val raw = frame.u16Le(2 + i * 2)
                        // WheelLog subtracts 2730 before dividing by ten:
                        // the BMS values are deci-kelvin, not deci-Celsius.
                        val celsius = (raw - KELVIN_OFFSET_DECI).toFloat() / 10f
                        if (celsius in -40f..150f) add(celsius)
                    }
                }
                if (values.isEmpty()) return
                state.temperaturesC.clear()
                state.temperaturesC.addAll(values)
                state.seen = true
                smartBmsSeen = true
            }

            in PAGE_CELLS_START..PAGE_CELLS_END -> {
                val page = frame[PAGE_OFFSET].u8() - PAGE_CELLS_START
                var accepted = false
                if (frame[PAGE_OFFSET].u8() == PAGE_CELLS_END) {
                    val raw = frame.u16Le(PAGE_SIX_TEMPERATURE_OFFSET)
                    if (raw in BMS_TEMPERATURE_MIN_RAW..BMS_TEMPERATURE_MAX_RAW) {
                        val celsius = (raw - KELVIN_OFFSET_DECI).toFloat() / 10f
                        state.eighthTemperatureC = celsius
                        accepted = true
                    }
                }
                repeat(CELLS_PER_PAGE) { i ->
                    val cellIndex = page * CELLS_PER_PAGE + i
                    if (cellIndex >= MAX_CELLS) return@repeat
                    val mv = frame.u16Le(2 + i * 2)
                    // Plausibility is a corruption guard, not a battery
                    // model: KingSong cells seen by WheelLog are 1.5..5.0 V.
                    if (mv in MIN_CELL_MV..MAX_CELL_MV) {
                        state.cellsMv[cellIndex] = mv
                        accepted = true
                    }
                }
                if (!accepted) return
                state.seen = true
                smartBmsSeen = true
            }

            else -> Unit
        }
    }

    private fun ByteArray.u16Le(offset: Int): Int =
        (this[offset].u8()) or (this[offset + 1].u8() shl 8)

    private fun ByteArray.i16Le(offset: Int): Int {
        val unsigned = u16Le(offset)
        return if (unsigned and 0x8000 != 0) unsigned - 0x10000 else unsigned
    }

    private fun ByteArray.kingSongDistance(offset: Int): Long =
        (this[offset].u8().toLong() shl 16) or
            (this[offset + 1].u8().toLong() shl 24) or
            this[offset + 2].u8().toLong() or
            (this[offset + 3].u8().toLong() shl 8)

    private fun Byte.u8(): Int = toInt() and 0xFF

    private companion object {
        const val FRAME_SIZE = 20
        const val HEADER_1 = 0xAA
        const val HEADER_2 = 0x55
        const val TAIL_1_OFFSET = 18
        const val TAIL_2_OFFSET = 19
        const val TAIL_BYTE = 0x5A
        const val MESSAGE_TYPE_OFFSET = 16
        const val PAGE_OFFSET = 17

        const val TYPE_LIVE = 0xA9
        const val TYPE_RIDE_STATS = 0xB9
        const val TYPE_OUTPUT = 0xF5
        const val TYPE_BMS_1 = 0xF1
        const val TYPE_BMS_2 = 0xF2

        const val PAGE_SUMMARY = 0x00
        const val PAGE_TEMPERATURES = 0x01
        const val PAGE_CELLS_START = 0x02
        const val PAGE_CELLS_END = 0x06
        const val PAGE_SIX_TEMPERATURE_OFFSET = 10
        const val CELLS_PER_PAGE = 7
        const val MAX_CELLS = 30
        const val TEMPERATURE_COUNT = 7
        const val KELVIN_OFFSET_DECI = 2730
        const val BMS_TEMPERATURE_MIN_RAW = 2530
        const val BMS_TEMPERATURE_MAX_RAW = 3930
        const val MIN_CELL_MV = 1500
        const val MAX_CELL_MV = 5000
    }
}
