package ru.sodovaya.volty.data.history

import java.io.File
import kotlinx.coroutines.sync.Mutex
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider

/** Shared serialization for disposable preview and export publication. */
class HistoryDerivedFiles(val previewFiles: HistoryPreviewFileCache, val exportDirectory: File,
    provider: VoltyDatabaseProvider) {
    val gate = Mutex()
    private val exports = HistoryExportFileCache(exportDirectory)
    private val recovery = HistoryPreviewPurgeRecovery(HistoryPreviewPurgeJournal(provider)) {
        previewFiles.clearOwned()
        exports.clearOwned()
    }
    /** Call on IO while holding [gate]. Acknowledge only after both namespaces are cleared. */
    fun reconcileLocked(): Boolean {
        if (!recovery.reconcile()) return false
        exports.prepare(0, 0)
        return true
    }
}
