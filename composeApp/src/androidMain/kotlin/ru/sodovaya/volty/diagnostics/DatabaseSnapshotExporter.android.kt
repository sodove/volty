package ru.sodovaya.volty.diagnostics

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabaseLockedException
import android.util.Log
import androidx.core.content.FileProvider
import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import java.io.File
import java.util.Calendar
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Android [DatabaseSnapshotExporter]. Everything that decides what is copied happens inside one
 * write transaction (SQLDelight's Android driver opens it with `beginTransactionNonExclusive`,
 * i.e. `BEGIN IMMEDIATE`), so no writer can commit between the decision and the copy:
 * the recording check is repeated, `PRAGMA wal_checkpoint(TRUNCATE)` is tried and its result
 * read, and [snapshotFilesDecision] picks `volty.db` alone or `volty.db` + `volty.db-wal`
 * (zipped). A snapshot is never produced that could miss committed data. `VACUUM INTO` needs
 * SQLite 3.27 and minSdk 26 ships 3.18.2. Read-only on the database.
 *
 * Lock duration: the raw copy runs under the write lock. For a 600 MB file that is seconds.
 * Android's native busy timeout is 2.5 s and the driver does not expose it, so a writer other
 * than the (refused-while-recording) history recorder, such as a settings or receipt write,
 * can throw SQLiteDatabaseLockedException if it starts during the copy. We chose to document
 * that rather than change the driver: the action is debug-only, user-initiated, and refused
 * while a ride records. If the lock cannot be taken here the rider sees "busy, try again".
 * Gzip/zip runs after the lock is released.
 */
class AndroidDatabaseSnapshotExporter(
    private val context: Context,
    private val provider: VoltyDatabaseProvider,
) : DatabaseSnapshotExporter {

    override suspend fun share(isRecording: suspend () -> Boolean): DatabaseShareOutcome = copyAndShare(isRecording, opened = true)

    override suspend fun shareUnopened(): DatabaseShareOutcome = copyAndShare({ false }, opened = false)

    private suspend fun copyAndShare(isRecording: suspend () -> Boolean, opened: Boolean): DatabaseShareOutcome = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR)
        val rawDb = File(dir, "snapshot.tmp")
        val rawWal = File(dir, "snapshot.tmp-wal")
        try {
            if (isRecording()) return@withContext DatabaseShareOutcome.Refused(SnapshotRefusal.RECORDING)
            val dbFile = context.getDatabasePath(DB_NAME)
            val walFile = File(dbFile.path + "-wal")
            dir.mkdirs()
            val old = dir.listFiles().orEmpty()
            // Space held by the previous snapshots comes back when they are deleted below.
            val free = dir.usableSpace + old.sumOf { it.length() }
            val plan = databaseSnapshotPlan(false, dbFile.length() + walFile.length(), free)
            if (plan is SnapshotPlan.Refuse) return@withContext DatabaseShareOutcome.Refused(plan.reason)
            // Only now: a refused attempt must not destroy a file a receiving app may still read.
            old.forEach { it.delete() }

            // Best effort outside the lock, only to keep the WAL (and so the lock time) small.
            if (opened) runCatching { checkpointBusy() }

            var refusal: SnapshotRefusal? = null
            var files: SnapshotFiles = SnapshotFiles.DbOnly
            if (!opened) {
                // The gate's error screen: nothing has the database open, so there is no lock to take and
                // no checkpoint to run; a WAL left behind travels with the main file.
                files = if (walFile.exists() && walFile.length() > 0L) SnapshotFiles.DbAndWal else SnapshotFiles.DbOnly
                copy(dbFile, rawDb)
                if (files == SnapshotFiles.DbAndWal) copy(walFile, rawWal)
            } else try {
                provider.database.transaction {
                    if (runBlocking { isRecording() }) { refusal = SnapshotRefusal.RECORDING; return@transaction }
                    val busy = runCatching { checkpointBusy() }.getOrNull()
                    files = snapshotFilesDecision(busy, if (walFile.exists()) walFile.length() else 0L)
                    when (val f = files) {
                        is SnapshotFiles.Refuse -> refusal = f.reason
                        SnapshotFiles.DbOnly -> copy(dbFile, rawDb)
                        SnapshotFiles.DbAndWal -> { copy(dbFile, rawDb); copy(walFile, rawWal) }
                    }
                }
            } catch (e: SQLiteDatabaseLockedException) {
                return@withContext DatabaseShareOutcome.Refused(SnapshotRefusal.BUSY)
            }
            refusal?.let { return@withContext DatabaseShareOutcome.Refused(it) }

            val withWal = files == SnapshotFiles.DbAndWal
            val compress = shouldCompressSnapshot(rawDb.length())
            val now = Calendar.getInstance()
            val target = File(dir, databaseSnapshotFileName(
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND), compress, withWal,
            ))
            when {
                withWal -> ZipOutputStream(target.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(DB_NAME)); rawDb.inputStream().use { it.copyTo(zip, BUFFER) }; zip.closeEntry()
                    zip.putNextEntry(ZipEntry("$DB_NAME-wal")); rawWal.inputStream().use { it.copyTo(zip, BUFFER) }; zip.closeEntry()
                }
                compress -> rawDb.inputStream().use { input ->
                    GZIPOutputStream(target.outputStream().buffered(BUFFER)).use { input.copyTo(it, BUFFER) }
                }
                else -> check(rawDb.renameTo(target))
            }
            val mime = when { withWal -> "application/zip"; compress -> "application/gzip"; else -> "application/vnd.sqlite3" }
            startShare(target, mime)
            DatabaseShareOutcome.Shared
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Class only: messages can carry paths.
            Log.w(TAG, "database share failed: ${e.javaClass.simpleName}")
            DatabaseShareOutcome.Failed
        } finally {
            rawDb.delete()
            rawWal.delete()
        }
    }

    private fun copy(from: File, to: File) {
        from.inputStream().use { input -> to.outputStream().use { input.copyTo(it, BUFFER) } }
    }

    /** The `busy` column of the checkpoint row: true when it could not finish. */
    private fun checkpointBusy(): Boolean =
        provider.driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) != 0L else true)
        }, 0).value

    private fun startShare(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Volty database")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Send database").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val TAG = "DbShare"
        const val DB_NAME = "volty.db"
        const val BUFFER = 256 * 1024
        /** Matches the `db-share` entry in file_paths.xml. */
        const val DIR = "db-share"
    }
}
