package ru.sodovaya.volty.data.history

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import ru.sodovaya.volty.domain.history.HistoryExportException
import ru.sodovaya.volty.domain.history.HistoryExportFailure
import ru.sodovaya.volty.domain.history.HistoryExportFormat
import ru.sodovaya.volty.domain.history.HistoryExportMetadata

/** Display names can contain device addresses or copied private file paths.
 * Preserve ordinary user text; neither metadata nor text may reintroduce those
 * storage identities into an explicitly shared history document.
 */
internal fun historyExportLabel(value: String): String {
    if (value.length > 200 || value.any { it.code < 0x20 && it !in "\t\r\n" })
        throw HistoryExportException(HistoryExportFailure.INVALID_DATA)
    return exportPrivatePath.replace(exportMacAddress.replace(value, "[device]"), "[path]")
}

private val exportMacAddress = Regex("(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")
private val exportPrivatePath = Regex(
    """(?:[A-Za-z]:[\\/]|/(?:data|storage|sdcard|home|Users|mnt|private)/|(?:file|content)://)[^\s"<>]+""",
    RegexOption.IGNORE_CASE,
)

/**
 * `volty_<vehicle>_<yyyy-MM-dd_HH-mm-ss>.<ext>`, the time being the selected range's start in the
 * phone's zone (the head unit names its logs by local time too, `log.c:151-163`).
 * The vehicle part keeps letters (any script), digits, `-` and `_`; device addresses and private
 * paths are redacted first, as in every other exported label.
 */
fun historyExportFileName(format: HistoryExportFormat, vehicle: String?, startEpochMillis: Long, zone: TimeZone): String {
    val redacted = vehicle?.let { runCatching { historyExportLabel(it) }.getOrNull() }.orEmpty()
    val name = redacted.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }.joinToString("")
        .replace(Regex("-{2,}"), "-").trim('-', '_').take(40).trimEnd('-', '_').ifEmpty { "ride" }
    val time = Instant.fromEpochMilliseconds(startEpochMillis).toLocalDateTime(zone)
    fun two(value: Int) = value.toString().padStart(2, '0')
    val stamp = "${time.year.toString().padStart(4, '0')}-${two(time.monthNumber)}-${two(time.dayOfMonth)}_" +
        "${two(time.hour)}-${two(time.minute)}-${two(time.second)}"
    return "volty_${name}_$stamp.${format.extension}"
}

/** The vehicle part of [historyExportFileName]: the rider's name for it, else the first source's label. */
fun historyExportVehicleName(metadata: HistoryExportMetadata): String? =
    metadata.vehicleName?.takeIf { it.isNotBlank() } ?: metadata.sources.firstOrNull()?.label
