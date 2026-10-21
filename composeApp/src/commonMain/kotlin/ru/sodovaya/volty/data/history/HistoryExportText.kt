package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.HistoryExportException
import ru.sodovaya.volty.domain.history.HistoryExportFailure

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
