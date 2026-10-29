package ru.sodovaya.volty.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import java.io.File
import java.util.Calendar
import java.util.zip.GZIPOutputStream

/**
 * Android [DatabaseSnapshotExporter]. A WAL-mode database is never copied naively: the WAL is
 * checkpointed (TRUNCATE) first, then the main file is copied inside one `BEGIN IMMEDIATE`
 * transaction (SQLDelight's Android driver opens write transactions that way), so no writer can
 * commit mid-copy. The lock is held for the raw copy only; gzip runs after it is released.
 * `VACUUM INTO` needs SQLite 3.27 and minSdk 26 ships 3.19. Read-only on the database: the only
 * statements are the checkpoint and a transaction with no writes.
 */
class AndroidDatabaseSnapshotExporter(
    private val context: Context,
    private val provider: VoltyDatabaseProvider,
) : DatabaseSnapshotExporter {

    override suspend fun share(recording: Boolean): DatabaseShareOutcome = withContext(Dispatchers.IO) {
        try {
            val dbFile = context.getDatabasePath(DB_NAME)
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            // Older snapshots go first: they are the rider's private data and cost cache space.
            dir.listFiles()?.forEach { it.delete() }
            val plan = databaseSnapshotPlan(recording, dbFile.length(), dir.usableSpace)
            if (plan is SnapshotPlan.Refuse) return@withContext DatabaseShareOutcome.Refused(plan.reason)

            val raw = File(dir, "snapshot.tmp")
            checkpoint()
            // Block the live writer only for the copy itself.
            provider.database.transaction {
                dbFile.inputStream().use { input -> raw.outputStream().use { input.copyTo(it) } }
            }
            val compress = shouldCompressSnapshot(raw.length())
            val now = Calendar.getInstance()
            val target = File(dir, databaseSnapshotFileName(
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND), compress,
            ))
            if (compress) {
                raw.inputStream().use { input ->
                    GZIPOutputStream(target.outputStream().buffered()).use { input.copyTo(it) }
                }
                raw.delete()
            } else {
                check(raw.renameTo(target))
            }
            startShare(target, if (compress) "application/gzip" else "application/vnd.sqlite3")
            DatabaseShareOutcome.Shared
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DatabaseShareOutcome.Failed
        }
    }

    private fun checkpoint() {
        provider.driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(cursor.next().value)
        }, 0)
    }

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
        const val DB_NAME = "volty.db"
        /** Matches the `db-share` entry in file_paths.xml. */
        const val DIR = "db-share"
    }
}
