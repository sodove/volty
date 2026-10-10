package ru.sodovaya.volty.diagnostics.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.BuildConfig
import ru.sodovaya.volty.domain.voice.VoiceDiagnosticSnapshot
import ru.sodovaya.volty.domain.voice.formatVoiceDiagnostics
import java.io.File
import java.security.MessageDigest

/** Dedicated typed export, never reads logcat or retains PCM. */
class VoiceDiagnosticExporter(private val context: Context) {
    suspend fun export(snapshot: VoiceDiagnosticSnapshot) {
        val file = withContext(Dispatchers.IO) {
            val header = "Volty ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "model=${safeMetadata(Build.MANUFACTURER)} ${safeMetadata(Build.MODEL)}\n" +
                "android=${Build.VERSION.SDK_INT} build=${safeMetadata(Build.DISPLAY)}\n" +
                "base_apk_sha256=${apkHash()}\n" +
                "LiveKit=2.28.0 WebRTC=144.7559.09\n"
            val dir = File(context.cacheDir, "logs").apply { check(mkdirs() || isDirectory) }
            File(dir, "volty-voice-diagnostics.txt").apply { writeText(header + snapshot.formatVoiceDiagnostics()) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null))
    }
    private fun safeMetadata(value: String): String = value.filter { !it.isISOControl() }.take(160)
    private fun apkHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use { stream ->
            val buffer = ByteArray(16384)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
