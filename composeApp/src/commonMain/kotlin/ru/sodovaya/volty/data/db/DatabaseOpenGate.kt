package ru.sodovaya.volty.data.db

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The schema version whose `28.sqm` converts every ride to the compact layout (a long migration). */
const val CONVERSION_SCHEMA_VERSION: Int = 29

/** Free space the conversion needs at least: measured peak growth ~70 MB, plus the journal and margin. */
const val MIGRATION_MIN_FREE_BYTES: Long = 150L * 1024 * 1024

/** A migration long enough that the rider is shown a screen instead of a frozen launch. */
data class MigrationNotice(val fromVersion: Int, val toVersion: Int)

/**
 * The screen is for the conversion only: a stored version in `1 until 29` going to 29 or later.
 * A fresh install (0) has nothing to convert, an up-to-date database nothing to wait for, and a
 * newer stored version (a downgrade) is not this screen's business.
 */
fun migrationNotice(storedVersion: Int, targetVersion: Int): MigrationNotice? =
    if (storedVersion in 1 until CONVERSION_SCHEMA_VERSION && targetVersion >= CONVERSION_SCHEMA_VERSION)
        MigrationNotice(storedVersion, targetVersion) else null

/**
 * Free bytes that must be available before the conversion starts: the larger of
 * [MIGRATION_MIN_FREE_BYTES] and 1.55 times the database.
 *
 * Measured 2026-11-03 on the AVD (Pixel 3a API 34 x86_64, debug build c5ec52ed + the rider's 600,178,688 byte v27 copy,
 * `ls -l` of `databases/` every ~0.4 s): journal_mode is DELETE (a rollback journal; no -wal, no -shm ever appeared),
 * auto_vacuum FULL. Peak `volty.db` 669,966,336 (+69.8 MB), peak `volty.db-journal` 546,906,504 (the old pages the DROP of
 * the V2 table and the commit's auto-vacuum relocation overwrite are journaled), so the PEAK GROWTH over the starting file
 * is 616.7 MB, 1.03 times the database. It falls to 82.2 MB at the commit. 137 s from launch to the finished conversion.
 * An earlier revision of this comment said "+70 MB, journal 1.3 MB, so 150 MB is plenty": that counted the file's growth
 * and sampled the journal between statements on JDBC; the journal is the larger half. The 1.55 is that 1.03 plus the 50 %
 * margin; the floor keeps small databases from being waved through with a few megabytes.
 */
fun migrationRequiredFreeBytes(databaseBytes: Long): Long =
    maxOf(MIGRATION_MIN_FREE_BYTES, databaseBytes.coerceAtLeast(0L) / 20 * 31)

/** The database ran out of room (SQLITE_FULL). The migration is atomic, so nothing was changed. */
class DatabaseFullException(cause: Throwable? = null) : Exception("Database is full", cause)

sealed interface DatabaseOpenFailure {
    /** Not enough free storage. The rider can free [requiredBytes] - [freeBytes] and retry. */
    data class NotEnoughSpace(val requiredBytes: Long, val freeBytes: Long) : DatabaseOpenFailure
    /** Anything else. [type] is the exception class only: messages can carry paths. */
    data class Other(val type: String) : DatabaseOpenFailure
}

sealed interface DatabaseOpenState {
    data object Opening : DatabaseOpenState
    data class Migrating(val fromVersion: Int, val toVersion: Int) : DatabaseOpenState
    data object Ready : DatabaseOpenState
    data class Failed(val failure: DatabaseOpenFailure) : DatabaseOpenState
}

/**
 * What the gate needs from the platform. There is deliberately no delete operation: a failed
 * open never removes data, and a fake cannot be asked to.
 */
interface DatabaseOpener {
    /** `PRAGMA user_version` of the file on disk, read without opening it for writing; 0 if absent. */
    fun storedVersion(): Int
    fun targetVersion(): Int
    /** Size of the database and its WAL. */
    fun databaseBytes(): Long
    fun freeBytes(): Long
    /** Opens the database; any migration runs inside. Throws [DatabaseFullException] on SQLITE_FULL. */
    fun open()
}

/**
 * Opens the database off the main thread and publishes how that is going. Nothing may query the
 * database before [DatabaseOpenState.Ready]: [awaitReady] is the wait, and the driver handed to the
 * repositories blocks on it, so a caller that forgot to wait is stalled rather than racing a migration.
 */
class DatabaseOpenGate(
    private val opener: DatabaseOpener,
    private val dispatcher: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow<DatabaseOpenState>(DatabaseOpenState.Opening)
    val state: StateFlow<DatabaseOpenState> = mutableState.asStateFlow()

    @kotlin.concurrent.Volatile private var scope: CoroutineScope? = null
    @kotlin.concurrent.Volatile private var running: Job? = null

    /** Begins opening; a no-op while an attempt is running or after it has succeeded. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        launchAttempt(scope)
    }

    /** Runs the attempt again after a [DatabaseOpenState.Failed]; nothing else can be retried. */
    fun retry() {
        val scope = scope ?: return
        if (mutableState.value !is DatabaseOpenState.Failed) return
        launchAttempt(scope)
    }

    suspend fun awaitReady() {
        mutableState.first { it is DatabaseOpenState.Ready }
    }

    val isReady: Boolean get() = mutableState.value is DatabaseOpenState.Ready

    private fun launchAttempt(scope: CoroutineScope) {
        if (running?.isActive == true || isReady) return
        mutableState.value = DatabaseOpenState.Opening
        running = scope.launch(dispatcher) { attempt() }
    }

    private fun attempt() {
        var required = MIGRATION_MIN_FREE_BYTES
        try {
            val stored = opener.storedVersion()
            val target = opener.targetVersion()
            if (migrationNotice(stored, target) != null) {
                required = migrationRequiredFreeBytes(opener.databaseBytes())
                val free = opener.freeBytes()
                if (free < required) {
                    mutableState.value = DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(required, free))
                    return
                }
                mutableState.value = DatabaseOpenState.Migrating(stored, target)
            }
            opener.open()
            mutableState.value = DatabaseOpenState.Ready
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            mutableState.value = DatabaseOpenState.Failed(classify(failure, required))
        }
    }

    private fun classify(failure: Throwable, required: Long): DatabaseOpenFailure {
        var cause: Throwable? = failure
        var depth = 0
        while (cause != null && depth++ < 8) {
            if (cause is DatabaseFullException) {
                val free = runCatching { opener.freeBytes() }.getOrDefault(0L)
                // A full disk that still reports room (quota, a file-system limit): ask for more than it has.
                return DatabaseOpenFailure.NotEnoughSpace(maxOf(required, free + 1), free)
            }
            cause = cause.cause
        }
        return DatabaseOpenFailure.Other(failure::class.simpleName ?: "Failure")
    }
}
