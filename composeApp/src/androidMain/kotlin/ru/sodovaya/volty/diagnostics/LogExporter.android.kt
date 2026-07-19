package ru.sodovaya.volty.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android [LogExporter]: dumps this process's own logcat (an app can always read
 * its own logs via the `logcat` binary without READ_LOGS), writes it to a file
 * in the cache dir, and opens the system share sheet backed by a FileProvider.
 *
 * A file (not EXTRA_TEXT) is used deliberately: a full session log easily
 * exceeds the binder transaction limit that plain-text sharing would hit.
 */
actual class LogExporter(private val context: Context) {

    actual fun exportLogs() {
        // logcat dump + file IO must not touch the main thread. Self-contained
        // so the common caller stays a trivial fire-and-forget.
        Thread {
            runCatching {
                val file = writeLogFile(header() + readLogcat())
                shareFile(file)
            }
        }.start()
    }

    /** `-d` = dump and exit; `-v time` prefixes each line with a timestamp. */
    private fun readLogcat(): String =
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { "logcat read failed: ${it.message}\n" }

    private fun writeLogFile(contents: String): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        // Fixed name — overwritten each export, so the cache never grows.
        return File(dir, "volty-log.txt").apply { writeText(contents) }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Volty logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Send app logs")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** A short banner so support can see build/device without asking. */
    private fun header(): String {
        val pkg = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val version = pkg?.let { "${it.versionName} (${versionCode(it)})" } ?: "unknown"
        return buildString {
            appendLine("==== Volty diagnostics ====")
            appendLine("app: $version")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("===========================")
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
}
