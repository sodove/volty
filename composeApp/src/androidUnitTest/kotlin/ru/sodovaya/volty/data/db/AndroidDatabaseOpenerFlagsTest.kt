package ru.sodovaya.volty.data.db

import android.database.sqlite.SQLiteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the version probe's open flags, nothing more: the constants are compile-time ints, so this needs no Android runtime.
 * What the flags DO is shown on real SQLite by [HotJournalRecoveryTest] and on the AVD (final-fix-report.md); this only
 * stops the probe drifting back to OPEN_READONLY, the final review's C1.
 */
class AndroidDatabaseOpenerFlagsTest {
    @Test fun theVersionProbeMayWriteSoItCanRollAHotJournalBack() {
        val flags = AndroidDatabaseOpener.VERSION_PROBE_FLAGS
        assertEquals(0, flags and SQLiteDatabase.OPEN_READONLY, "a read-only probe fails with SQLITE_READONLY_ROLLBACK after a kill")
        assertEquals(0, flags and SQLiteDatabase.CREATE_IF_NECESSARY, "the probe never creates a file")
        assertEquals(SQLiteDatabase.NO_LOCALIZED_COLLATORS, flags and SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            "the probe writes no android_metadata row")
    }
}
