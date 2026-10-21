package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.db.VoltyDatabaseProvider

/** One persistent purge generation, requested in the same transaction as ride deletion. */
class HistoryPreviewPurgeJournal(provider: VoltyDatabaseProvider) {
    private val database = provider.database
    private val queries = database.historyPreviewPurgeQueries

    fun pendingGeneration(): Long? =
        queries.pendingPreviewPurgeGeneration().executeAsOneOrNull()

    fun request(): Long = database.transactionWithResult {
        queries.ensurePreviewPurgeJournal()
        val previousGeneration = queries.currentPreviewPurgeGeneration().executeAsOne()
        check(previousGeneration < Long.MAX_VALUE) { "Preview purge generation exhausted" }

        queries.incrementPreviewPurgeGeneration()
        val generation = queries.currentPreviewPurgeGeneration().executeAsOne()
        check(generation == previousGeneration + 1) { "Preview purge generation was not advanced" }
        generation
    }

    fun acknowledge(generation: Long) {
        database.transaction {
            queries.acknowledgePreviewPurge(generation)
        }
    }
}

/** Caller owns serialization and IO dispatch; failures leave the request pending. */
class HistoryPreviewPurgeRecovery(private val journal: HistoryPreviewPurgeJournal, private val clearOwned: () -> Unit) {
    fun reconcile(): Boolean {
        val generation = journal.pendingGeneration() ?: return true
        clearOwned()
        journal.acknowledge(generation)
        return journal.pendingGeneration() == null
    }
}
