package ru.sodovaya.volty.data.radio

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.BuildConfig
import ru.sodovaya.volty.domain.radio.RadioDiagnostics

/** Shares a radio run's measurements as a text file, the same way the voice diagnostics are shared. */
class RadioDiagnosticExporter(context: Context) {
    private val context = context.applicationContext ?: context

    suspend fun export(diagnostics: RadioDiagnostics) {
        val file = withContext(Dispatchers.IO) {
            val header = "Volty ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "model=${safe(Build.MANUFACTURER)} ${safe(Build.MODEL)}\n" +
                "android=${Build.VERSION.SDK_INT}\n"
            val dir = File(context.cacheDir, "logs").apply { check(mkdirs() || isDirectory) }
            File(dir, "volty-radio-diagnostics.txt").apply { writeText(header + diagnostics.format()) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                null,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun safe(value: String): String = value.filter { !it.isISOControl() }.take(160)
}
