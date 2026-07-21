package ru.sodovaya.dumper

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Accumulates the recorded stream in memory, writes it to a file in the cache
 * directory and hands it to the system share sheet.
 *
 * One line per notification rather than per frame: at MTU 23 a 24-byte frame
 * always arrives split, and exactly how it is split is itself useful evidence
 * for whoever writes the parser.
 */
class DumpWriter(private val context: Context) {

    private val lines = StringBuilder()

    fun begin(deviceName: String?, address: String) {
        lines.setLength(0)
        lines.appendLine("# volty-dumper 1.0")
        lines.appendLine("# device: ${deviceName ?: "(no name)"} ($address)")
        lines.appendLine("# service 0000ffe0 / char 0000ffe1")
        lines.appendLine("# columns: t_ms hex")
    }

    fun append(elapsedMs: Long, chunk: ByteArray) {
        lines.append(elapsedMs).append('\t').appendLine(chunk.toHex())
    }

    fun finish(summary: List<String>): File {
        lines.appendLine("# --- summary ---")
        summary.forEach { lines.append("# ").appendLine(it) }
        val dir = File(context.cacheDir, "dumps").apply { mkdirs() }
        // Fixed name — overwritten each run, so the cache never grows.
        return File(dir, "begode-dump.txt").apply { writeText(lines.toString()) }
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
