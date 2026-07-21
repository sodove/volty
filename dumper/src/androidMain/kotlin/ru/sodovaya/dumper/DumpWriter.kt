package ru.sodovaya.dumper

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Streams the recording straight to a file in the cache directory and hands
 * the finished file to the system share sheet.
 *
 * Each recording gets its own timestamped file — never a fixed name. A fixed
 * name would be truncated by the next [begin] while the share target
 * (Telegram, mail, Drive) may still be reading the previous capture through
 * its content:// URI, corrupting the transfer. Old dumps are pruned in
 * [begin] so the cache stays bounded at [KEEP_DUMPS] files.
 *
 * The file is opened in [begin] and each line is flushed to disk as it
 * arrives, so everything captured so far is already on disk — a process death
 * mid-recording loses at most the line currently being written, never the
 * whole capture. At a few dozen bytes a couple of times per second the cost
 * of flushing per line is irrelevant.
 *
 * One line per notification rather than per frame: at MTU 23 a 24-byte frame
 * always arrives split, and exactly how it is split is itself useful evidence
 * for whoever writes the parser.
 */
class DumpWriter(private val context: Context) {

    private companion object {
        /** Upper bound on dumps kept in the cache, including the one being written. */
        const val KEEP_DUMPS = 5
        const val NAME_PREFIX = "begode-dump-"
        /**
         * Second-precision local time. Zero-padded fields make the names sort
         * chronologically, and a human sending several dumps from several
         * wheels can read which is which at a glance.
         */
        val NAME_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }

    private var writer: BufferedWriter? = null
    private var file: File? = null

    fun begin(deviceName: String?, address: String) {
        // A previous run that never reached finish() may have left a writer open.
        runCatching { writer?.close() }
        val dir = File(context.cacheDir, "dumps").apply { mkdirs() }
        pruneOldDumps(dir)
        val f = newDumpFile(dir)
        val w = f.bufferedWriter()
        w.appendLine("# volty-dumper 1.0")
        w.appendLine("# device: ${deviceName ?: "(no name)"} ($address)")
        w.appendLine("# service 0000ffe0 / char 0000ffe1")
        w.appendLine("# columns: t_ms hex")
        w.flush() // header on disk before the first notification
        file = f
        writer = w
    }

    /**
     * A fresh file for this recording. Distinct timestamped names mean a new
     * recording can never truncate a previous capture. In the unlikely case of
     * two recordings starting within the same second, an `_N` suffix keeps the
     * names distinct (and still sorting after the base name).
     */
    private fun newDumpFile(dir: File): File {
        val stamp = LocalDateTime.now().format(NAME_STAMP)
        var f = File(dir, "$NAME_PREFIX$stamp.txt")
        var n = 2
        while (f.exists()) {
            f = File(dir, "$NAME_PREFIX${stamp}_$n.txt")
            n++
        }
        return f
    }

    /**
     * Delete all but the newest [KEEP_DUMPS] - 1 dumps, so with the file about
     * to be created the cache holds at most [KEEP_DUMPS]. Sorting by name is
     * sorting by recording time — the names are built that way. The most
     * recent dumps survive, so a file a share target is still reading is not
     * pulled out from under it.
     */
    private fun pruneOldDumps(dir: File) {
        // Older builds wrote a single fixed-name file; remove the leftover.
        runCatching { File(dir, "begode-dump.txt").delete() }
        dir.listFiles { f -> f.isFile && f.name.startsWith(NAME_PREFIX) }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP_DUMPS - 1)
            ?.forEach { runCatching { it.delete() } }
    }

    /**
     * Called on every notification — appends to the single writer opened in
     * [begin] and flushes immediately. A whole session is small enough to fit
     * in the default 8 KB buffer, so without the flush a process death could
     * lose the entire capture, not just a tail.
     */
    fun append(elapsedMs: Long, chunk: ByteArray) {
        val w = writer ?: return
        w.append(elapsedMs.toString()).append('\t').appendLine(chunk.toHex())
        w.flush()
    }

    fun finish(summary: List<String>): File {
        val f = checkNotNull(file) { "finish() called before begin()" }
        writer?.let { w ->
            w.appendLine("# --- summary ---")
            summary.forEach { w.append("# ").appendLine(it) }
            w.flush()
            w.close()
        }
        writer = null
        return f
    }

    fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Begode BLE dump")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Send dump").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append("0123456789abcdef"[v ushr 4])
            out.append("0123456789abcdef"[v and 0x0F])
        }
        return out.toString()
    }
}
