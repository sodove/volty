package ru.sodovaya.volty.domain.voice

/** Access is serialized by the native diagnostic store, including late final snapshots. */
internal class VoiceDiagnosticReportHistory(private val capacity: Int = 3) {
    init { require(capacity > 0) }
    private data class Entry(val owner: VoiceDiagnostics, var report: String)
    private val retained = mutableListOf<Entry>()
    fun remember(owner: VoiceDiagnostics, report: String) {
        if (replace(owner, report)) return
        retained.add(Entry(owner, report))
        while (retained.size > capacity) retained.removeAt(0)
    }
    fun replace(owner: VoiceDiagnostics, report: String): Boolean {
        val entry = retained.firstOrNull { it.owner === owner } ?: return false
        entry.report = report
        return true
    }
    fun reports(): List<String> = retained.map { it.report }
    fun clear() = retained.clear()
}
