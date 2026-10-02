package ru.sodovaya.volty.diagnostics

import android.content.Context
import android.content.Intent
import ru.sodovaya.volty.diagnostics.voice.VoiceDiagnosticActivity

actual class VoiceDiagnosticLauncher(private val context: Context) {
    actual fun launch() {
        context.startActivity(Intent(context, VoiceDiagnosticActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
