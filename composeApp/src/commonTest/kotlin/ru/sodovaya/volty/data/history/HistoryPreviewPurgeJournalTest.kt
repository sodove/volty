package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ru.sodovaya.volty.data.db.*
import kotlin.test.*

class HistoryPreviewPurgeJournalTest {
    private fun fixture(block: (VoltyDatabaseProvider, HistoryPreviewPurgeJournal) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            VoltyDatabase.Schema.create(driver)
            val provider = VoltyDatabaseProvider(driver)
            block(provider, HistoryPreviewPurgeJournal(provider))
        } finally { driver.close() }
    }
    @Test fun onlyExactCurrentGenerationCanAcknowledgePurgeAndLaterRequestSurvives() = fixture { _, journal ->
        assertNull(journal.pendingGeneration())
        assertEquals(1L, journal.request()); assertEquals(1L, journal.pendingGeneration())
        assertEquals(2L, journal.request()); journal.acknowledge(1)
        assertEquals(2L, journal.pendingGeneration()); journal.acknowledge(3); assertEquals(2L, journal.pendingGeneration())
        journal.acknowledge(2); assertNull(journal.pendingGeneration())
        assertEquals(3L, journal.request()); assertEquals(3L, journal.pendingGeneration())
    }
    @Test fun rolledBackDeletionCannotLeaveACommittedPurgeRequest() = fixture { provider, journal ->
        class ControlledRollback : IllegalStateException()
        assertFailsWith<ControlledRollback> { provider.database.transaction {
            journal.request(); throw ControlledRollback()
        } }
        assertNull(journal.pendingGeneration())
    }
}
