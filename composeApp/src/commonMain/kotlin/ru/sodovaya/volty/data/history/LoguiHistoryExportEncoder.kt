package ru.sodovaya.volty.data.history

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import ru.sodovaya.volty.domain.history.*
import kotlin.math.abs
import kotlin.math.floor

/**
 * A ride as a VESC logui CSV: the file the rider's own head unit writes, so it opens in VESC Tool's
 * Log Analysis (with the map) and in telemetry-studio's `vesc` parser without either being changed.
 *
 * ## The wire format, from the writers and the reader (paths under `vesc_tool_free_windows`)
 * - Header: one line of `key:name:unit:precision:isRelative:isTimestamp` descriptors joined by `;`,
 *   no trailing `;` (`vesc_express-main/main/log.c:78-82` `print_header`, `:164-212`). VESC Tool
 *   splits on `;` then `:` and needs at least two `:`-tokens in the first field, or it treats the
 *   file as an RT log (`vesc_tool-master/pages/pageloganalysis.cpp:1293-1324`). Keys and names
 *   therefore never contain `:` or `;` — every one of them is a constant below, never a label.
 * - Rows: `;`-separated, `\n` line ends, numbers printed `"%.*f"` at the field's precision with a
 *   `.` decimal point (`log.c:242-243`, C locale); VESC Tool reads them with `QString::toDouble`
 *   (C locale) (`pageloganalysis.cpp:1340-1344`). Here the fixed-precision text is additionally
 *   stripped of trailing zeros (`65.20` → `65.2`), which both readers parse identically.
 * - An empty cell is "no value in this row": logui's writer leaves a field empty when nothing
 *   updated it (`log.c:222-243`) and leaves GNSS empty without a new fix (`log.c:257-289`).
 *   **VESC Tool then repeats the previous value, starting from 0.0** (`pageloganalysis.cpp:1327,
 *   1340-1347`), and telemetry-studio reads an empty cell as 0 (`server/src/parsers/types.ts`
 *   `cleanNumeric`). Volty still writes empty cells, never a held value: the file states only what
 *   was sampled in that row, and the hold is the reader's own documented behaviour.
 * - A row with the wrong number of cells is silently dropped by VESC Tool (`:1334-1336`), so every
 *   row here carries exactly one cell per header field.
 * - Time: `t_day` (`Time`, s, precision 3, isTimestamp 1) is seconds of the local day
 *   (`log.c:430-435`, `:251`). VESC Tool treats a negative step as one midnight and adds 86 400 s
 *   (`pageloganalysis.cpp:853-856`, `:960-963`), so `t_day` wraps at 86 400 here as well. `t_day_pos`
 *   (`Time GNSS`) is the time of the fix and is written only in a row that carries a fix
 *   (`log.c:439-444`, `:257-261`); VESC Tool draws a map point only when it changes and
 *   `gnss_h_acc > 0` (`pageloganalysis.cpp:759-771`), which is what keeps held coordinates and the
 *   reader's 0.0 before the first fix off the map.
 * - GNSS keys `gnss_lat`, `gnss_lon` (deg, 7), `gnss_h_acc` (m, 2) are the ones VESC Tool looks for
 *   (`pageloganalysis.h:155-160`; `log.c:448-480`). Volty records neither altitude nor GNSS speed,
 *   so `gnss_alt` and `gnss_h_vel` are absent rather than invented; VESC Tool treats a missing
 *   `gnss_alt` as 0 (`pageloganalysis.cpp:783-787`). The fix heading has no logui key and goes in
 *   its own `gnss_heading` column.
 *
 * ## Columns
 * One per recorded (source, metric, cell) series with at least one finite value in the range
 * ([loguiField]); a series that is unknown throughout gets no column. The first motion source and
 * the first battery source keep logui's bare keys (`Input Voltage`, `Current`, `Current In`, `Duty`,
 * `RPM`, `Temp Fet`, `Temp Motor`, `Batt`, `kmh_vesc`, `trip_vesc`), which telemetry-studio's
 * `parseVesc` maps to its overlay channels. Every other source is prefixed like logui's CAN
 * template `V%d Current` (`vesc_pkg/logui/logger.lisp:71-81`); see [loguiSourcePrefixes].
 *
 * ## Rows
 * Each row is one slot of a [gridMillis] grid on the ride's wall-clock axis
 * ([historyAxisEpochMillis]); every sample and fix lands in the slot holding its own tick, and a
 * cell is written only in that slot — no forward fill, no interpolation. A metric sampled twice in
 * one slot keeps the later sample. Unknown samples write nothing, and a slot that would hold only
 * empty cells is not written at all. Samples are read in storage order, which is time order in
 * practice (the real T4 ride: 0 of 46 782 out of order); one arriving more than
 * [REORDER_WINDOW_MILLIS] behind the newest is written at once in a row of its own, out of time
 * order, rather than dropped.
 *
 * `t_day` counts from the local time of day at the range start plus elapsed axis time, so a DST
 * change mid-ride does not jump it by an hour; a ride longer than 24 h aliases, which no logui
 * reader can represent anyway.
 *
 * Markers (BLE gaps, GPS gaps, clock changes) are omitted: logui has no marker row, and any extra
 * line or section would be dropped or misread by both parsers. A gap shows as missing time in
 * `t_day`. Summaries are not read at all: they are aggregates of the very samples this file carries.
 *
 * The input flow is collected twice — once to learn which series earned a column (the header
 * comes first), once to stream the rows — so it must be cold. Fixes are held in memory between
 * the passes (about one per second); samples never are, beyond a [REORDER_WINDOW_MILLIS] window.
 */
internal class LoguiHistoryExportEncoder(
    private val metadata: HistoryExportMetadata,
    private val zone: TimeZone,
    private val gridMillis: Long = LOGUI_ROW_GRID_MILLIS,
) {
    init { require(gridMillis in 1..60_000) }
    private val legacy = metadata.ride.status == HistoryRideStatus.LEGACY
    private val sources = metadata.sources.associateBy { it.id }

    /** What the last [write] did, for measurement; not part of the file. */
    internal var stats = LoguiExportStats()
        private set

    suspend fun write(records: Flow<HistoryExportRecord>, output: suspend (String) -> Unit): HistoryExportCounts {
        if (metadata.ride.status == HistoryRideStatus.RECORDING) fail(HistoryExportFailure.ACTIVE_RECORDING)
        if (metadata.sources.map { it.id }.distinct().size != metadata.sources.size) fail(HistoryExportFailure.INVALID_DATA)

        // Pass 1: which series earned a column, and the fixes (ordered by their own tick).
        val earned = LinkedHashSet<HistorySeriesKey>()
        val fixes = ArrayList<Pair<Long, HistoryGpsFix>>()
        var events = 0L; var unmapped = 0L
        records.collect { record ->
            currentCoroutineContext().ensureActive()
            when (record) {
                is HistoryExportRecord.Numeric -> {
                    axis(record.sample.tick)
                    descriptor(record.sample.key)
                    if (record.sample.value?.isFinite() == true) earned += record.sample.key
                }
                is HistoryExportRecord.Location -> {
                    if (fixes.size >= MAX_FIXES) fail(HistoryExportFailure.LIMIT)
                    fixes += axis(record.fix.tick) to record.fix
                }
                is HistoryExportRecord.Event -> { axis(record.marker.tick); events++ }
                is HistoryExportRecord.LegacyUnmapped -> unmapped++
            }
        }
        fixes.sortBy { it.first }
        val prefixes = loguiSourcePrefixes(metadata.sources, earned.mapTo(HashSet()) { it.sourceId })
        val order = loguiSourceOrder(metadata.sources, prefixes)
        val keys = earned.sortedWith(compareBy<HistorySeriesKey>({ order.getValue(it.sourceId) },
            { metricOrder.getValue(it.metricId) }, { it.cellIndex ?: -1 }))
        val fields = keys.map { key -> loguiField(key.metricId, key.cellIndex) }
        val columns = keys.indices.map { index ->
            val prefix = prefixes.getValue(keys[index].sourceId)
            LoguiColumn(prefix + fields[index].key, prefix + fields[index].name, fields[index].unit, fields[index].precision)
        }
        val columnOf = keys.withIndex().associate { (index, key) -> key to index }
        val hasFix = fixes.isNotEmpty()
        val hasHeading = fixes.any { it.second.heading?.isFinite() == true }
        val header = columns + LoguiColumn("t_day", "Time", "s", 3, isTimestamp = true) + (if (!hasFix) emptyList() else listOf(
            LoguiColumn("t_day_pos", "Time GNSS", "s", 3, isTimestamp = true),
            LoguiColumn("gnss_lat", "Latitude", "deg", 7), LoguiColumn("gnss_lon", "Longitude", "deg", 7),
            LoguiColumn("gnss_h_acc", "H. Accuracy GNSS", "m", 2)) +
            if (hasHeading) listOf(LoguiColumn("gnss_heading", "Heading GNSS", "deg", 1)) else emptyList())
        if (header.map { it.key }.distinct().size != header.size) fail(HistoryExportFailure.INVALID_DATA)

        val anchor = metadata.range.startEpochMillis
        val anchorOfDay = loguiMillisOfDay(anchor, zone)
        fun tDay(epochMillis: Long) = loguiNumber(
            (anchorOfDay + (epochMillis - anchor)).mod(MILLIS_PER_DAY) / 1000.0, 3)

        val sink = BufferedSink(output)
        sink.append(header.joinToString(";") { it.descriptor() }).append("\n")

        // Pass 2: rows in slot order, merged within a short reorder window.
        val pending = ArrayList<Row>()
        var lastWritten = Long.MIN_VALUE
        var nextFix = 0
        var rows = 0L; var cells = 0L; var replaced = 0L; var late = 0L; var fixesWritten = 0L
        suspend fun emitRow(row: Row) {
            val line = StringBuilder()
            for (index in columns.indices) {
                if (index > 0) line.append(';')
                val value = row.values[index]
                if (!value.isNaN()) line.append(loguiNumber(value, columns[index].precision))
            }
            if (columns.isNotEmpty()) line.append(';')
            line.append(tDay(row.slot * gridMillis))
            if (hasFix) {
                val fix = row.fix
                line.append(';')
                if (fix != null) {
                    line.append(tDay(row.fixAxis)).append(';')
                        .append(loguiNumber(fix.lat, 7)).append(';')
                        .append(loguiNumber(fix.lon, 7)).append(';')
                        .append(loguiNumber(fix.accuracyMeters, 2))
                    if (hasHeading) line.append(';').append(fix.heading?.let { loguiNumber(it, 1) }.orEmpty())
                    fixesWritten++
                } else line.append(if (hasHeading) ";;;;" else ";;;")
            }
            line.append('\n')
            sink.append(line)
            rows++
        }
        fun rowFor(slot: Long): Row {
            var index = pending.size
            while (index > 0 && pending[index - 1].slot > slot) index--
            if (index > 0 && pending[index - 1].slot == slot) return pending[index - 1]
            return Row(slot, DoubleArray(columns.size) { Double.NaN }).also { pending.add(index, it) }
        }
        fun admitFixesThrough(slot: Long) {
            while (nextFix < fixes.size && fixes[nextFix].first.floorDiv(gridMillis) <= slot) {
                val (fixAxis, fix) = fixes[nextFix++]
                val row = rowFor(fixAxis.floorDiv(gridMillis))
                if (row.fix != null) replaced++
                row.fix = fix; row.fixAxis = fixAxis
            }
        }
        suspend fun flushThrough(slot: Long) {
            while (pending.isNotEmpty() && pending[0].slot <= slot) {
                val row = pending.removeAt(0)
                if (row.fix != null || row.values.any { !it.isNaN() }) { emitRow(row); lastWritten = row.slot }
            }
        }
        val window = (REORDER_WINDOW_MILLIS + gridMillis - 1) / gridMillis
        records.collect { record ->
            currentCoroutineContext().ensureActive()
            if (record !is HistoryExportRecord.Numeric) return@collect
            val sample = record.sample
            val value = sample.value?.takeIf { it.isFinite() } ?: return@collect
            val column = columnOf[sample.key] ?: fail(HistoryExportFailure.SOURCE_CHANGED)
            val slot = axis(sample.tick).floorDiv(gridMillis)
            val scaled = value * fields[column].scale
            if (slot <= lastWritten) {
                // Older than the reorder window: its own row, out of order, rather than lost.
                late++; cells++
                emitRow(Row(slot, DoubleArray(columns.size) { Double.NaN }.also { it[column] = scaled }))
                return@collect
            }
            admitFixesThrough(slot)
            val row = rowFor(slot)
            if (!row.values[column].isNaN()) replaced++ else cells++
            row.values[column] = scaled
            flushThrough(slot - window)
        }
        admitFixesThrough(Long.MAX_VALUE)
        flushThrough(Long.MAX_VALUE)
        sink.flush()
        stats = LoguiExportStats(rows, cells, replaced, late, columns.size, fixesWritten)
        return HistoryExportCounts(samples = cells, locations = fixesWritten, events = events, unmapped = unmapped)
    }

    private class Row(val slot: Long, val values: DoubleArray) {
        var fix: HistoryGpsFix? = null
        var fixAxis: Long = 0
    }

    /** Coalesces rows into bounded fragments so the file owner is not called once per row. */
    private class BufferedSink(private val output: suspend (String) -> Unit) {
        private val buffer = StringBuilder()
        suspend fun append(text: CharSequence): BufferedSink {
            buffer.append(text)
            if (buffer.length >= FRAGMENT_CHARS) flush()
            return this
        }
        suspend fun flush() {
            if (buffer.isEmpty()) return
            val fragment = buffer.toString(); buffer.clear()
            output(fragment)
        }
    }

    private fun axis(tick: HistoryTick): Long {
        val captured = if (legacy) tick.epochMillis
            else historyAxisEpochMillis(metadata.ride.started, tick) ?: fail(HistoryExportFailure.INVALID_DATA)
        if (captured !in metadata.range.startEpochMillis..metadata.range.endEpochMillis) fail(HistoryExportFailure.INVALID_DATA)
        return captured
    }
    private fun descriptor(key: HistorySeriesKey): HistoryMetricDescriptor {
        val source = sources[key.sourceId] ?: fail(HistoryExportFailure.INVALID_DATA)
        val descriptor = HistoryMetricRegistry.descriptor(key.metricId) ?: fail(HistoryExportFailure.INVALID_DATA)
        if (source.kind !in descriptor.sourceKinds || descriptor.indexedByCell != (key.cellIndex != null))
            fail(HistoryExportFailure.INVALID_DATA)
        return descriptor
    }
    private fun fail(reason: HistoryExportFailure): Nothing = throw HistoryExportException(reason)

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
        const val FRAGMENT_CHARS = 32 * 1024
        const val MAX_FIXES = 1_000_000
        /** logui's own field order (`logger.lisp:39-66`) first, then the registry's. */
        val metricOrder = (listOf("input_voltage_v", "motor_current_a", "battery_current_a", "duty_percent",
            "duty_estimated_percent", "erpm", "esc_temperature_c", "motor_temperature_c", "controller_power_w", "speed_kmh",
            "trip_distance_km", "odometer_km", "soc_percent", "voltage_v", "current_a", "power_w", "battery_temperature_c",
            "cell_min_v", "cell_max_v", "cell_delta_mv", "cell_v") + HistoryMetricRegistry.descriptors.map { it.id })
            .distinct().withIndex().associate { (index, id) -> id to index }
    }
}

/**
 * Row grid, chosen by measurement on the rider's real 14.3-minute Begode T4 ride
 * (`volty-ride-84bdb496…json`: 30 247 known values, 841 fixes; the scratch harness and the table are
 * in `.superpowers/sdd/2026-10-29-logui-export/report.md`):
 *
 * | grid | bytes | rows | samples merged away |
 * |---:|---:|---:|---:|
 * | 1 ms | 467 079 | 11 815 | 0 |
 * | 20 ms | 377 595 | 8 732 | 0 |
 * | **50 ms** | **312 727** | **6 194** | **0** |
 * | 100 ms | 272 229 | 4 658 | 0 |
 * | 200 ms | 246 789 | 3 625 | 20 |
 *
 * The wheel's frame types arrive tens of milliseconds apart, so a fine grid writes one sparse row
 * per frame; merging them into 50 ms slots saves a third without dropping anything. 100 ms saves
 * another 13 % on this wheel but would put two samples of a 20 Hz VESC poll (Part O's target) in
 * one slot and keep only the later; 200 ms already drops samples on the T4 itself. 50 ms is the
 * coarsest grid that gives every sample of today's rates (T4 ~3.2 Hz per frame type, VESC 6.7 Hz,
 * ANT 2 Hz) and of a 20 Hz poll its own slot — at exactly 20 Hz, timing jitter can still put two
 * in one slot, and [LoguiExportStats.replaced] counts it.
 */
internal const val LOGUI_ROW_GRID_MILLIS = 50L

/** A sample this far behind the newest one still merges into its own slot. */
internal const val REORDER_WINDOW_MILLIS = 2_000L

internal data class LoguiExportStats(val rows: Long = 0, val cells: Long = 0, val replaced: Long = 0,
    val late: Long = 0, val columns: Int = 0, val fixes: Long = 0)

internal data class LoguiColumn(val key: String, val name: String, val unit: String, val precision: Int,
    val isTimestamp: Boolean = false) {
    init { require(listOf(key, name, unit).none { ':' in it || ';' in it || '\n' in it || '\r' in it }) }
    /** `log.c:76-81`: `key:name:unit:precision:isRelative:isTimestamp`. Nothing here is relative to the first row. */
    fun descriptor() = "$key:$name:$unit:$precision:0:${if (isTimestamp) 1 else 0}"
}

/** How one Volty metric is written: the stored canonical value times [scale], at [precision] decimals. */
internal data class LoguiField(val key: String, val name: String, val unit: String, val precision: Int, val scale: Double = 1.0)

/**
 * The logui field for a registry metric. Keys VESC Tool or telemetry-studio already know are used
 * for the same quantity in the unit they expect (`logger.lisp:39-66`):
 * - `Duty` is a fraction (`get-duty`, -1..1; the rider's logs read `0.13`), so `duty_percent` is
 *   scaled by 0.01 and printed at 3 decimals (0.1 %); telemetry-studio multiplies it by 100.
 * - `trip_vesc` is metres (`get-dist`, unit `m`); VESC Tool's trip statistic is its end minus start.
 * - `RPM` is logui's `get-rpm`, electrical RPM, the same quantity as Volty's `erpm`.
 * - `Batt` is a percentage; here it is the battery source's state of charge.
 * The estimated duty (`duty_estimated_percent`) is a different quantity and never shares `Duty`.
 * Battery current and power keep Volty's stored sign (charge positive), named for the BMS, rather
 * than borrowing logui's `bms_i_in_ic`, whose sign convention was not verified.
 */
internal fun loguiField(metricId: String, cellIndex: Int?): LoguiField {
    val descriptor = HistoryMetricRegistry.descriptor(metricId)
    val unit = descriptor?.unit?.let(::loguiUnit).orEmpty()
    val precision = descriptor?.unit?.let(::loguiPrecision) ?: 2
    fun same(key: String, name: String = key) = LoguiField(key, name, unit, precision)
    return when (metricId) {
        "input_voltage_v" -> same("Input Voltage")
        "motor_current_a" -> same("Current")
        "battery_current_a" -> same("Current In")
        "duty_percent" -> LoguiField("Duty", "Duty", "", 3, scale = 0.01)
        "duty_estimated_percent" -> same("duty_estimated", "Duty Estimated")
        "erpm" -> LoguiField("RPM", "RPM", "", 0)
        "esc_temperature_c" -> same("Temp Fet")
        "motor_temperature_c" -> same("Temp Motor")
        "speed_kmh" -> same("kmh_vesc", "Speed")
        "trip_distance_km" -> LoguiField("trip_vesc", "Trip", "m", 1, scale = 1000.0)
        "odometer_km" -> same("odometer", "Odometer")
        "controller_power_w" -> same("power", "Power")
        "soc_percent" -> same("Batt", "Battery")
        "voltage_v" -> same("bms_v_tot", "BMS Voltage")
        "current_a" -> same("bms_current", "BMS Current")
        "power_w" -> same("bms_power", "BMS Power")
        "battery_temperature_c" -> same("bms_temp", "BMS Temp")
        "cell_min_v" -> LoguiField("cell_min", "Cell Min", unit, 3)
        "cell_max_v" -> LoguiField("cell_max", "Cell Max", unit, 3)
        "cell_delta_mv" -> same("cell_delta", "Cell Delta")
        // logger.lisp:120: per-cell fields are `BMS_C%d` (1-based), V, 3 decimals.
        "cell_v" -> "BMS_C${(cellIndex ?: 0) + 1}".let { LoguiField(it, it, unit, 3) }
        else -> (if (cellIndex == null) metricId else "${metricId}_${cellIndex + 1}").let { same(it) }
    }
}

internal fun loguiUnit(unit: HistoryUnit): String = when (unit) {
    HistoryUnit.PERCENT -> "%"; HistoryUnit.VOLT -> "V"; HistoryUnit.AMP -> "A"; HistoryUnit.WATT -> "W"
    HistoryUnit.CELSIUS -> "degC"; HistoryUnit.MILLIVOLT -> "mV"; HistoryUnit.KMH -> "km/h"; HistoryUnit.ERPM -> ""
    HistoryUnit.KM -> "km"; HistoryUnit.WH -> "Wh"; HistoryUnit.AH -> "Ah"
}

internal fun loguiPrecision(unit: HistoryUnit): Int = when (unit) {
    HistoryUnit.VOLT, HistoryUnit.AMP, HistoryUnit.KMH, HistoryUnit.WH -> 2
    HistoryUnit.PERCENT, HistoryUnit.CELSIUS -> 1
    HistoryUnit.WATT, HistoryUnit.MILLIVOLT, HistoryUnit.ERPM -> 0
    HistoryUnit.KM, HistoryUnit.AH -> 3
}

/**
 * Column prefix per source. The first motion source (a vehicle aggregate if there is one, else a
 * controller) and the first battery source (aggregate, else a pack) that earned a column keep bare
 * keys; every other source gets a kind letter and its 1-based ordinal among sources of that kind,
 * like logui's `V117 Current`. A second controller is `V<n>`, which telemetry-studio's
 * `detectSecondController` (`/^(V\d+)\s+/`) folds into its totals; other kinds use letters it does
 * not match. Labels never appear: they are rider text, can repeat, and may contain `:` or `;`.
 */
internal fun loguiSourcePrefixes(sources: List<HistorySource>, withData: Set<String>): Map<String, String> {
    fun first(vararg tiers: HistorySourceKind) = tiers.firstNotNullOfOrNull { kind ->
        sources.firstOrNull { it.kind == kind && it.id in withData } }
    val bare = setOfNotNull(first(HistorySourceKind.VEHICLE_MOTION, HistorySourceKind.CONTROLLER)?.id,
        first(HistorySourceKind.VEHICLE_BATTERY, HistorySourceKind.BATTERY)?.id)
    val ordinal = HashMap<HistorySourceKind, Int>()
    return sources.associate { source ->
        val number = (ordinal[source.kind] ?: 0) + 1
        ordinal[source.kind] = number
        source.id to if (source.id in bare) "" else "${loguiKindLetter(source.kind)}$number "
    }
}

private fun loguiKindLetter(kind: HistorySourceKind) = when (kind) {
    HistorySourceKind.CONTROLLER -> "V"; HistorySourceKind.VEHICLE_MOTION -> "M"
    HistorySourceKind.BATTERY -> "B"; HistorySourceKind.VEHICLE_BATTERY -> "P"
    HistorySourceKind.BATTERY_ASSEMBLY -> "A"; HistorySourceKind.DERIVED_BATTERY -> "D"
    HistorySourceKind.REPORTED_CAN -> "CAN"
}

/** Bare-key sources first (motion, then battery), then the rest in metadata order. */
private fun loguiSourceOrder(sources: List<HistorySource>, prefixes: Map<String, String>): Map<String, Int> {
    val motion = setOf(HistorySourceKind.VEHICLE_MOTION, HistorySourceKind.CONTROLLER)
    val ranked = sources.withIndex().sortedWith(compareBy({
        val bare = prefixes[it.value.id].isNullOrEmpty()
        when { bare && it.value.kind in motion -> 0; bare -> 1; else -> 2 }
    }, { it.index }))
    return ranked.withIndex().associate { (rank, entry) -> entry.value.id to rank }
}

/** Milliseconds since local midnight in [zone], as the head unit's `utils_ms_today()`. */
internal fun loguiMillisOfDay(epochMillis: Long, zone: TimeZone): Long {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    return ((local.hour * 60L + local.minute) * 60L + local.second) * 1000L + local.nanosecond / 1_000_000
}

/**
 * `%.{precision}f`, rounded half away from zero, with a `.` point whatever the locale, then trailing
 * zeros dropped (`65.20` → `65.2`, `3.00` → `3`, `-0.00` → `0`). Float noise such as
 * `-6.960000038146973` prints as `-6.96` at precision 2. Non-finite values have no text: null.
 */
internal fun loguiNumber(value: Double, precision: Int): String? {
    require(precision in 0..9)
    if (!value.isFinite()) return null
    var digits = precision
    var scale = POWERS[digits]
    while (digits > 0 && abs(value) * scale >= 9.0e15) { digits--; scale = POWERS[digits] }
    val magnitude = abs(value) * scale
    if (magnitude >= 9.0e15) return null
    val units = floor(magnitude + 0.5).toLong()
    if (units == 0L) return "0"
    val whole = units / POWERS[digits].toLong()
    var fraction = (units % POWERS[digits].toLong()).toString().padStart(digits, '0').trimEnd('0')
    if (digits == 0) fraction = ""
    return (if (value < 0) "-" else "") + whole + if (fraction.isEmpty()) "" else ".$fraction"
}

private val POWERS = DoubleArray(10) { var power = 1.0; repeat(it) { power *= 10.0 }; power }
