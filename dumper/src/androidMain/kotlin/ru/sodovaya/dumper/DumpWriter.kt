package ru.sodovaya.dumper

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.BufferedWriter
import java.io.File

/**
 * Streams the recording straight to a file in the cache directory and hands
 * the finished file to the system share sheet.
 *
 * The file is opened in [begin] and each line is appended as it arrives, so
 * everything captured so far is already on disk (modulo the write buffer) —
 * a process death mid-recording loses at most the buffered tail, never the
 * whole capture.
 *
 * One line per notification rather than per frame: at MTU 23 a 24-byte frame
 * always arrives split, and exactly how it is split is itself useful evidence
 * for whoever writes the parser.
 */
class DumpWriter(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var file: File? = null

    fun begin(deviceName: String?, address: String) {
        // A previous run that never reached finish() may have left a writer open.
        runCatching { writer?.close() }
        val dir = File(context.cacheDir, "dumps").apply { mkdirs() }
        // Fixed name — overwritten each run, so the cache never grows.
        val f = File(dir, "begode-dump.txt")
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
     * Called on every notification — appends to the single writer opened in
     * [begin]; never reopens the file per line.
     */
    fun append(elapsedMs: Long, chunk: ByteArray) {
        val w = writer ?: return
        w.append(elapsedMs.toString()).append('\t').appendLine(chunk.toHex())
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
