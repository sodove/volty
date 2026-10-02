package ru.sodovaya.volty.diagnostics.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import ru.sodovaya.volty.domain.voice.VoiceAudioPreferences

class VoiceDiagnosticViewModel(val runtime: AndroidVoiceDiagnosticRuntime) : ViewModel()

class VoiceDiagnosticActivity : ComponentActivity() {
    private val runtime: AndroidVoiceDiagnosticRuntime by inject()
    private var pending: Pair<String, VoiceAudioPreferences>? = null
    private val permissionError = mutableStateOf(false)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.all { it }
        val request = pending.also { pending = null }
        if (granted && request != null) startVisible(request.first, request.second)
        else permissionError.value = !granted
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = VoiceDiagnosticViewModel(runtime) as T
        })[VoiceDiagnosticViewModel::class.java]
        val controller = model.runtime.controller
        controller.loadGroups()
        setContent {
            val state by controller.state.collectAsState()
            VoiceDiagnosticScreen(state, permissionError.value,
                onStart = { group, preferences ->
                    permissionError.value = false
                    val needed = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
                    }.filter { ContextCompat.checkSelfPermission(this@VoiceDiagnosticActivity, it) != PackageManager.PERMISSION_GRANTED }
                    if (needed.isEmpty())
                        startVisible(group, preferences)
                    else { pending = group to preferences; permission.launch(needed.toTypedArray()) }
                },
                onStop = { runtime.scope.launch { if (controller.stop()) stopService(Intent(this@VoiceDiagnosticActivity, VoiceDiagnosticService::class.java)) } },
                onReload = controller::loadGroups,
                onMuted = controller::setMuted, onDeafened = controller::setDeafened,
                onExport = { state.diagnostics?.let { diagnostics -> lifecycleScope.launch {
                    VoiceDiagnosticExporter(this@VoiceDiagnosticActivity).export(diagnostics.state.value)
                } } },
            )
        }
    }

    private fun startVisible(group: String, preferences: VoiceAudioPreferences) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, VoiceDiagnosticService::class.java)
                .setAction(VoiceDiagnosticService.START).putExtra(VoiceDiagnosticService.GROUP, group)
                .putExtra(VoiceDiagnosticService.INPUT, preferences.input.name)
                .putExtra(VoiceDiagnosticService.OUTPUT, preferences.output.name))
        } catch (_: Exception) { permissionError.value = true }
    }

    override fun onDestroy() {
        if (isFinishing) runtime.scope.launch {
            if (runtime.controller.stop()) stopService(Intent(this@VoiceDiagnosticActivity, VoiceDiagnosticService::class.java))
        }
        super.onDestroy()
    }
}
