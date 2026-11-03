package ru.sodovaya.volty.data.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseOpenGateTest {
    private val mb = 1024L * 1024

    /** Records what the gate asked for; there is no delete here because the interface has none. */
    private class FakeOpener(
        var stored: Int = 27,
        val target: Int = 29,
        var bytes: Long = 50L * 1024 * 1024,   // under the floor: these tests are about the gate; the formula has its own test
        var free: Long = 400L * 1024 * 1024,
    ) : DatabaseOpener {
        val calls = mutableListOf<String>()
        var openFailure: Throwable? = null
        var storedFailure: Throwable? = null
        var onOpen: () -> Unit = {}
        override fun storedVersion(): Int { calls += "storedVersion"; storedFailure?.let { throw it }; return stored }
        override fun targetVersion(): Int = target
        override fun databaseBytes(): Long { calls += "databaseBytes"; return bytes }
        override fun freeBytes(): Long { calls += "freeBytes"; return free }
        override fun open() { calls += "open"; onOpen(); openFailure?.let { throw it } }
    }

    private fun TestScope.gateOf(opener: DatabaseOpener) =
        DatabaseOpenGate(opener, StandardTestDispatcher(testScheduler))

    @Test fun everyFailureIsLoggedWithItsCauseChainAndTheNumbers() = runTest {
        val logged = mutableListOf<Pair<String, Throwable?>>()
        val opener = FakeOpener(free = 400 * mb)
        val cause = IllegalStateException("constraint failed")
        opener.openFailure = RuntimeException("migration 28", cause)
        val gate = DatabaseOpenGate(opener, StandardTestDispatcher(testScheduler)) { message, failure -> logged += message to failure }
        gate.start(backgroundScope); runCurrent()
        val failure = logged.single { it.second != null }
        assertEquals(cause, failure.second!!.cause, "the whole chain reaches the logger, not just the class name")
        assertTrue("required=" in failure.first)
        assertTrue(logged.any { "stored=27 target=29" in it.first })
        assertTrue(logged.any { "free=" in it.first && "dbBytes=" in it.first })
    }

    @Test fun noticeOnlyForConversionMigration() {
        assertEquals(MigrationNotice(27, 29), migrationNotice(27, 29))
        assertEquals(MigrationNotice(28, 29), migrationNotice(28, 29))
        assertEquals(MigrationNotice(1, 30), migrationNotice(1, 30))
        assertNull(migrationNotice(0, 29), "a fresh install has nothing to convert")
        assertNull(migrationNotice(29, 29))
        assertNull(migrationNotice(30, 29), "a downgrade is not this screen")
        assertNull(migrationNotice(27, 28), "27 to 28 is the additive step, instant")
        assertNull(migrationNotice(-1, 29))
    }

    @Test fun requiredSpaceIsTheLargerOfFloorAndMeasuredPeakWithMargin() {
        assertEquals(150 * mb, migrationRequiredFreeBytes(0))
        assertEquals(150 * mb, migrationRequiredFreeBytes(50 * mb), "a small database needs the floor")
        // Measured on the AVD: 600,178,688 bytes grew by 616.7 MB at peak (file + journal); 1.55 times it is 930 MB.
        assertEquals(600_178_688L / 20 * 31, migrationRequiredFreeBytes(600_178_688L))
        assertTrue(migrationRequiredFreeBytes(600_178_688L) >= (69_787_648L + 546_906_504L) * 3 / 2, "measured peak growth (file + journal) plus 50 %")
        assertEquals(930 * mb, migrationRequiredFreeBytes(600 * mb))
        assertEquals(4096 * mb / 20 * 31, migrationRequiredFreeBytes(4096 * mb))
        assertEquals(150 * mb, migrationRequiredFreeBytes(-5), "an unreadable size is not negative room")
    }

    @Test fun gatePublishesReadyAfterOpen() = runTest {
        val opener = FakeOpener()
        val gate = gateOf(opener)
        // A recording start parks on the gate; it must not run before the database is open.
        val recordingStarted = backgroundScope.async { gate.awaitReady(); "recording" }
        val duringOpen = mutableListOf<DatabaseOpenState>()
        var waiterDoneDuringOpen: Boolean? = null
        opener.onOpen = { duringOpen += gate.state.value; waiterDoneDuringOpen = recordingStarted.isCompleted }
        assertEquals(DatabaseOpenState.Opening, gate.state.value)
        gate.start(backgroundScope)
        assertEquals(DatabaseOpenState.Opening, gate.state.value, "start does not run the attempt on the caller")
        runCurrent()
        assertEquals(listOf<DatabaseOpenState>(DatabaseOpenState.Migrating(27, 29)), duringOpen)
        assertEquals(false, waiterDoneDuringOpen)
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
        assertEquals("recording", recordingStarted.await())
        assertEquals(listOf("storedVersion", "databaseBytes", "freeBytes", "open"), opener.calls)
    }

    @Test fun freshInstallOpensWithoutAScreenOrASpaceCheck() = runTest {
        val opener = FakeOpener(stored = 0, free = 0)
        val gate = gateOf(opener)
        val duringOpen = mutableListOf<DatabaseOpenState>()
        opener.onOpen = { duringOpen += gate.state.value }
        gate.start(backgroundScope); runCurrent()
        assertEquals(listOf<DatabaseOpenState>(DatabaseOpenState.Opening), duringOpen)
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
        assertFalse("freeBytes" in opener.calls, "no conversion, no 150 MB demand")
    }

    @Test fun upToDateDatabaseOpensWithoutAScreen() = runTest {
        val opener = FakeOpener(stored = 29, free = 0)
        val gate = gateOf(opener)
        gate.start(backgroundScope); runCurrent()
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
        assertEquals(listOf("storedVersion", "open"), opener.calls)
    }

    @Test fun shortOfSpaceNeverStartsAndAsksForTheDifference() = runTest {
        val opener = FakeOpener(free = 150 * mb - 1)
        val gate = gateOf(opener)
        gate.start(backgroundScope); runCurrent()
        assertEquals(DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(150 * mb, 150 * mb - 1, 50 * mb)), gate.state.value)
        assertFalse("open" in opener.calls, "the migration is not even attempted")
    }

    @Test fun exactlyTheRequiredSpaceIsEnoughAndALargeDatabaseRaisesTheBar() = runTest {
        val exact = FakeOpener(free = 150 * mb)
        gateOf(exact).also { it.start(backgroundScope); runCurrent(); assertEquals(DatabaseOpenState.Ready, it.state.value) }
        val large = FakeOpener(bytes = 4096 * mb, free = 1000 * mb)
        gateOf(large).also {
            it.start(backgroundScope); runCurrent()
            assertEquals(DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(migrationRequiredFreeBytes(4096 * mb), 1000 * mb, 4096 * mb)), it.state.value)
        }
    }

    @Test fun retryAfterFreeingSpaceOpens() = runTest {
        val opener = FakeOpener(free = 10 * mb)
        val gate = gateOf(opener)
        gate.start(backgroundScope); runCurrent()
        assertTrue(gate.state.value is DatabaseOpenState.Failed)
        opener.free = 300 * mb
        gate.retry(); runCurrent()
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
        assertEquals(1, opener.calls.count { it == "open" })
    }

    @Test fun sqliteFullDuringTheMigrationIsTheSameMessageNotACrash() = runTest {
        val opener = FakeOpener(free = 400 * mb)
        opener.openFailure = DatabaseFullException(RuntimeException("SQLITE_FULL"))
        val gate = gateOf(opener)
        gate.start(backgroundScope); runCurrent()
        // Deliberately incoherent: the disk reports 400 MB free, yet SQLite said it was full.
        // The message must still ask for more than the disk has, never for zero.
        assertEquals(DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(400 * mb + 1, 400 * mb)), gate.state.value)
        opener.free = 40 * mb; opener.openFailure = null
        gate.retry(); runCurrent()
        assertEquals(DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(150 * mb, 40 * mb, 50 * mb)), gate.state.value,
            "with 40 MB the pre-check refuses before any attempt")
        opener.free = 300 * mb
        gate.retry(); runCurrent()
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
    }

    @Test fun fullWrappedInAnotherExceptionIsStillRecognised() = runTest {
        val opener = FakeOpener(free = 200 * mb)
        opener.openFailure = IllegalStateException("migration failed", DatabaseFullException())
        val gate = gateOf(opener)
        gate.start(backgroundScope); runCurrent()
        assertEquals(DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(200 * mb + 1, 200 * mb)), gate.state.value)
    }

    @Test fun failureKeepsDatabaseAndRetryRunsAgain() = runTest {
        val opener = FakeOpener()
        opener.openFailure = IllegalStateException("boom: /data/user/0/ru.sodovaya.volty/databases/volty.db")
        val gate = gateOf(opener)
        gate.start(backgroundScope); runCurrent()
        assertEquals(DatabaseOpenState.Failed(DatabaseOpenFailure.Other("IllegalStateException")), gate.state.value,
            "the class only: the message carries a path")
        assertTrue(opener.calls.none { "delete" in it.lowercase() }, "no delete is ever requested")
        assertEquals(1, opener.calls.count { it == "open" })
        opener.openFailure = null
        gate.retry(); runCurrent()
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
        assertEquals(2, opener.calls.count { it == "open" })
    }

    /**
     * The disk after a process was killed inside 28.sqm (final review C1): `volty.db` torn by spilled pages and a hot
     * `volty.db-journal` holding the old ones. Like SQLite, a probe that may write rolls the journal back on its first read
     * (file restored, journal deleted, its space returned); one that may not fails every read, for ever, which is what the
     * old read-only probe did. The numbers are the rider's: a 600 MB database, a 547 MB journal at t+90 s.
     */
    private class KilledMidConversionDisk(val probeCanWrite: Boolean) : DatabaseOpener {
        val calls = mutableListOf<String>()
        var stored = 27
        var fileBytes = 670L * 1024 * 1024        // grown by the conversion before the kill
        val originalBytes = 600L * 1024 * 1024
        var journalBytes = 547L * 1024 * 1024
        var free = 1000L * 1024 * 1024            // with the journal still on disk; 1547 MB once it is gone
        override fun storedVersion(): Int {
            calls += "storedVersion"
            if (journalBytes > 0) {
                if (!probeCanWrite) throw IllegalStateException("SQLITE_READONLY_ROLLBACK (776)")
                fileBytes = originalBytes; free += journalBytes; journalBytes = 0
            }
            return stored
        }
        override fun targetVersion(): Int = 29
        override fun databaseBytes(): Long { calls += "databaseBytes"; check(journalBytes == 0L) { "sized before the rollback" }; return fileBytes }
        override fun freeBytes(): Long { calls += "freeBytes"; return free }
        override fun open() { calls += "open"; check(journalBytes == 0L) { "opened over a hot journal" }; stored = 29 }
    }

    @Test fun aConversionKilledMidwayRestartsAfterTheProbeRollsItsJournalBack() = runTest {
        val disk = KilledMidConversionDisk(probeCanWrite = true)
        // The fixture separates the two orders: before the rollback 1000 MB free is short of 1.55 x 670 MB = 1038 MB
        // (NoSpace for a rider whose phone has room); after it 1547 MB free covers 1.55 x 600 MB = 930 MB.
        assertTrue(disk.free < migrationRequiredFreeBytes(disk.fileBytes))
        assertTrue(disk.free + disk.journalBytes >= migrationRequiredFreeBytes(disk.originalBytes))
        val duringOpen = mutableListOf<DatabaseOpenState>()
        lateinit var gate: DatabaseOpenGate
        gate = gateOf(object : DatabaseOpener by disk {
            override fun open() { duringOpen += gate.state.value; disk.open() }
        })
        gate.start(backgroundScope); runCurrent()
        assertEquals(DatabaseOpenState.Ready, gate.state.value, "the rides are reachable again")
        assertEquals(listOf<DatabaseOpenState>(DatabaseOpenState.Migrating(27, 29)), duringOpen, "the conversion starts over")
        assertEquals(listOf("storedVersion", "databaseBytes", "freeBytes", "open"), disk.calls, "sized only after the rollback")
        assertEquals(0L, disk.journalBytes)
        assertEquals(29, disk.stored)
    }

    @Test fun aProbeThatCannotRollTheJournalBackIsStuckOnEveryRetry() = runTest {
        // What the read-only probe did: kept as the documented failure shape, so the test above is not passing by accident.
        val disk = KilledMidConversionDisk(probeCanWrite = false)
        val gate = gateOf(disk)
        gate.start(backgroundScope); runCurrent()
        assertEquals(DatabaseOpenState.Failed(DatabaseOpenFailure.Other("IllegalStateException")), gate.state.value)
        gate.retry(); runCurrent()
        assertEquals(DatabaseOpenState.Failed(DatabaseOpenFailure.Other("IllegalStateException")), gate.state.value)
        assertFalse("open" in disk.calls, "nothing ever opened it")
        assertEquals(547L * 1024 * 1024, disk.journalBytes)
    }

    @Test fun aCorruptFileIsAVisibleFailureAndIsNeverOpened() = runTest {
        // Corruption is the one probe failure that must stay a failure: the open would hand the driver a damaged file.
        val opener = FakeOpener()
        opener.storedFailure = RuntimeException("SQLITE_NOTADB")
        val gate = gateOf(opener)
        gate.start(backgroundScope); runCurrent()
        assertEquals(DatabaseOpenState.Failed(DatabaseOpenFailure.Other("RuntimeException")), gate.state.value)
        assertFalse("open" in opener.calls)
    }

    @Test fun retryDoesNothingWhenReadyOrWhileAnAttemptIsPending() = runTest {
        val opener = FakeOpener()
        val gate = gateOf(opener)
        gate.retry(); runCurrent()
        assertTrue(opener.calls.isEmpty(), "retry before start has nothing to retry")
        gate.start(backgroundScope)
        gate.start(backgroundScope); runCurrent()
        assertEquals(1, opener.calls.count { it == "open" }, "a second start while one is queued adds no attempt")
        gate.retry(); gate.start(backgroundScope); runCurrent()
        assertEquals(1, opener.calls.count { it == "open" }, "nothing re-opens a ready database")
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
    }

    @Test fun gatedDriverTouchesNothingUntilItsWaitReturnsAndNeverWaitsForListeners() {
        val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { VoltyDatabase.Schema.create(it) }
        val log = mutableListOf<String>()
        val driver = GatedSqlDriver(raw) { log += "wait" }
        driver.addListener("a", listener = Query.Listener { })
        driver.notifyListeners("a")
        assertEquals(emptyList(), log, "listener bookkeeping is in memory")
        assertNull(driver.currentTransaction())
        driver.executeQuery(null, "SELECT 1", { QueryResult.Value(it.next().value) }, 0)
        assertEquals(listOf("wait"), log)
        driver.execute(null, "CREATE TABLE gate_t(a INTEGER)", 0)
        assertEquals(listOf("wait", "wait"), log)
        VoltyDatabaseProvider(driver).database.transaction { }
        assertEquals(listOf("wait", "wait", "wait"), log, "a transaction waits too")
    }

    @Test fun gatedDriverRunsNothingWhenTheWaitThrows() {
        val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val driver = GatedSqlDriver(raw) { throw IllegalStateException("not ready") }
        runCatching { driver.execute(null, "CREATE TABLE should_not_exist(a INTEGER)", 0) }
        val tables = raw.executeQuery(null, "SELECT count(*) FROM sqlite_master WHERE name = 'should_not_exist'",
            { QueryResult.Value(if (it.next().value) it.getLong(0) else -1L) }, 0).value
        assertEquals(0L, tables)
    }

    @Test fun waiterIsReleasedOnlyByReady() = runTest {
        val opener = FakeOpener(stored = 0)
        val gate = gateOf(opener)
        val released = CompletableDeferred<Unit>()
        backgroundScope.async { gate.awaitReady(); released.complete(Unit) }
        runCurrent()
        assertFalse(released.isCompleted)
        gate.start(backgroundScope); runCurrent()
        assertTrue(released.isCompleted)
    }

    // --- The rider's destructive ways out (owner request 2026-11-03) ---

    /** Records what was asked of it; [onClear]/[onDelete] model what the platform does to the disk. */
    private class FakeRecovery(val opener: FakeOpener, val calls: MutableList<String> = opener.calls) : DatabaseRecovery {
        var clearFailure: Throwable? = null
        var onClear: () -> Unit = { opener.bytes = 256L * 1024 }          // history gone: the file is a few hundred KB
        var onDelete: () -> Unit = { opener.stored = 0; opener.bytes = 0 } // no file: a fresh install
        override fun clearRideHistory() { calls += "clearRideHistory"; clearFailure?.let { throw it }; onClear() }
        override fun deleteDatabase() { calls += "deleteDatabase"; onDelete() }
    }

    private fun TestScope.gateWith(opener: FakeOpener, recovery: DatabaseRecovery) =
        DatabaseOpenGate(opener, StandardTestDispatcher(testScheduler), recovery = recovery)

    @Test fun eachFailureOffersExactlyItsOwnWayOut() {
        assertEquals(setOf(DatabaseGateAction.CLEAR_RIDE_HISTORY),
            databaseGateActions(DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(930 * mb, 500 * mb))))
        assertEquals(setOf(DatabaseGateAction.START_WITH_EMPTY_DATABASE),
            databaseGateActions(DatabaseOpenState.Failed(DatabaseOpenFailure.Other("SQLiteDatabaseCorruptException"))))
        for (state in listOf(DatabaseOpenState.Opening, DatabaseOpenState.Migrating(27, 29), DatabaseOpenState.Ready))
            assertEquals(emptySet(), databaseGateActions(state), "$state")
    }

    @Test fun clearingTheHistoryOnNoSpaceKeepsTheFileAndOpensAfterwards() = runTest {
        // The rider's case: 600 MB of history, 500 MB free, 930 MB needed.
        val opener = FakeOpener(bytes = 600 * mb, free = 500 * mb)
        val recovery = FakeRecovery(opener)
        val gate = gateWith(opener, recovery)
        gate.start(backgroundScope); runCurrent()
        assertTrue((gate.state.value as DatabaseOpenState.Failed).failure is DatabaseOpenFailure.NotEnoughSpace)
        opener.calls.clear()
        gate.recover(DatabaseGateAction.CLEAR_RIDE_HISTORY); runCurrent()
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
        assertEquals(listOf("clearRideHistory", "storedVersion", "databaseBytes", "freeBytes", "open"), opener.calls,
            "cleared first, then the whole attempt again, sized after the clear")
        assertFalse("deleteDatabase" in opener.calls, "a healthy database is never deleted")
        assertFalse(gate.recoveryFailed.value)
    }

    @Test fun anActionTheStateDoesNotOfferDoesNothing() = runTest {
        val noSpace = FakeOpener(bytes = 600 * mb, free = 500 * mb)
        val gate = gateWith(noSpace, FakeRecovery(noSpace))
        gate.recover(DatabaseGateAction.CLEAR_RIDE_HISTORY); runCurrent()
        assertTrue(noSpace.calls.isEmpty(), "before start there is nothing to recover")
        gate.start(backgroundScope); runCurrent()
        noSpace.calls.clear()
        gate.recover(DatabaseGateAction.START_WITH_EMPTY_DATABASE); runCurrent()
        assertEquals(emptyList(), noSpace.calls, "NoSpace never deletes the file")

        val ready = FakeOpener(stored = 29)
        val readyGate = gateWith(ready, FakeRecovery(ready))
        readyGate.start(backgroundScope); runCurrent()
        ready.calls.clear()
        for (action in DatabaseGateAction.entries) readyGate.recover(action)
        runCurrent()
        assertEquals(emptyList(), ready.calls, "an open database is never touched")
    }

    @Test fun startingEmptyAfterAnUnreadableFileDeletesItAndOpensAFreshOne() = runTest {
        val opener = FakeOpener()
        opener.storedFailure = RuntimeException("SQLITE_NOTADB")
        val recovery = FakeRecovery(opener)
        recovery.onDelete = { opener.storedFailure = null; opener.stored = 0 }
        val gate = gateWith(opener, recovery)
        gate.start(backgroundScope); runCurrent()
        assertEquals(DatabaseOpenState.Failed(DatabaseOpenFailure.Other("RuntimeException")), gate.state.value)
        gate.recover(DatabaseGateAction.CLEAR_RIDE_HISTORY); runCurrent()
        assertFalse("clearRideHistory" in opener.calls, "an untrustworthy file is not edited")
        gate.recover(DatabaseGateAction.START_WITH_EMPTY_DATABASE); runCurrent()
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
        assertEquals(1, opener.calls.count { it == "deleteDatabase" })
        assertFalse("freeBytes" in opener.calls, "a fresh database needs no conversion and no space check")
    }

    @Test fun aClearThatFailsSaysSoAndTheGateReadsTheFileAgain() = runTest {
        val opener = FakeOpener(bytes = 600 * mb, free = 500 * mb)
        val recovery = FakeRecovery(opener).apply { clearFailure = IllegalStateException("SQLITE_FULL") }
        val gate = gateWith(opener, recovery)
        gate.start(backgroundScope); runCurrent()
        opener.calls.clear()
        gate.recover(DatabaseGateAction.CLEAR_RIDE_HISTORY); runCurrent()
        assertTrue(gate.recoveryFailed.value)
        assertEquals(listOf("clearRideHistory", "storedVersion", "databaseBytes", "freeBytes"), opener.calls)
        assertEquals(setOf(DatabaseGateAction.CLEAR_RIDE_HISTORY), databaseGateActions(gate.state.value),
            "still NoSpace: a failed clear never turns into an offer to delete the file")
        recovery.clearFailure = null
        gate.recover(DatabaseGateAction.CLEAR_RIDE_HISTORY); runCurrent()
        assertFalse(gate.recoveryFailed.value)
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
    }

    @Test fun nothingTouchesTheFilesWhileAShareCopiesThem() = runTest {
        // Re-review F2: the probe writes (it rolls a journal back) and an attempt may start the conversion.
        val opener = FakeOpener(bytes = 600 * mb, free = 500 * mb)
        val gate = gateWith(opener, FakeRecovery(opener))
        gate.start(backgroundScope); runCurrent()
        val noSpace = gate.state.value
        opener.calls.clear()
        gate.filesBeingCopied = true
        gate.retry(); runCurrent()
        gate.recover(DatabaseGateAction.CLEAR_RIDE_HISTORY); runCurrent()
        assertEquals(emptyList(), opener.calls, "no probe, no clear under the copy")
        assertEquals(noSpace, gate.state.value)
        gate.filesBeingCopied = false
        opener.free = 1000 * mb
        gate.retry(); runCurrent()
        assertEquals(DatabaseOpenState.Ready, gate.state.value, "after the copy, Retry works again")
    }

    @Test fun aRunningActionIsPublishedSoTheScreenIsNotBlank() = runTest {
        val opener = FakeOpener(bytes = 600 * mb, free = 500 * mb)
        val recovery = FakeRecovery(opener)
        lateinit var gate: DatabaseOpenGate
        val during = mutableListOf<DatabaseOpenState>()
        recovery.onClear = { during += gate.state.value; opener.bytes = 256L * 1024 }
        gate = gateWith(opener, recovery)
        gate.start(backgroundScope); runCurrent()
        gate.recover(DatabaseGateAction.CLEAR_RIDE_HISTORY)
        assertEquals(DatabaseOpenState.Recovering(DatabaseGateAction.CLEAR_RIDE_HISTORY), gate.state.value, "published at once")
        assertEquals(emptySet(), databaseGateActions(gate.state.value), "nothing else is offered meanwhile")
        runCurrent()
        assertEquals(listOf<DatabaseOpenState>(DatabaseOpenState.Recovering(DatabaseGateAction.CLEAR_RIDE_HISTORY)), during)
        assertEquals(DatabaseOpenState.Ready, gate.state.value)
    }
}
