package ru.sodovaya.volty.data.radio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.radio.RadioController
import ru.sodovaya.volty.domain.radio.RadioMember
import ru.sodovaya.volty.domain.radio.RadioMode
import ru.sodovaya.volty.domain.radio.RadioPanelHost
import ru.sodovaya.volty.domain.radio.RadioState
import ru.sodovaya.volty.domain.radio.radioRequiredPermissions

/** Glue between the radio card and the controller, runtime, settings and signed-in account. */
class AndroidRadioPanelHost(
    context: Context,
    private val controller: RadioController,
    private val runtime: RadioRuntime,
    private val prefs: AppPrefs,
    private val exporter: RadioDiagnosticExporter,
    private val actor: () -> String?,
    private val scope: CoroutineScope,
) : RadioPanelHost {
    private val app = context.applicationContext ?: context

    override val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    override val state: StateFlow<RadioState> = controller.state
    override val roster: StateFlow<List<RadioMember>> = runtime.members
    override val mode: StateFlow<RadioMode> =
        combine(controller.mode, runtime.activeMode) { chosen, active -> active ?: chosen }
            .stateIn(scope, SharingStarted.Eagerly, controller.mode.value)
    override val autoJoin: StateFlow<Boolean> = prefs.radioAutoJoin
    override val hasDiagnostics: StateFlow<Boolean> = runtime.diagnosticsAvailable

    init {
        // The rider's saved mode is what the next raise uses; the controller forwards it to a running hub.
        scope.launch { prefs.radioMode.collect { controller.setMode(it) } }
    }

    override fun missingPermissions(scanning: Boolean): List<String> =
        radioRequiredPermissions(Build.VERSION.SDK_INT, scanning).filter {
            app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    override fun raise(community: String) {
        controller.raise(actor() ?: return, community)
    }

    override fun join(community: String) {
        controller.join(actor() ?: return, community)
    }

    override fun reRaise() = controller.reRaise()

    override fun leave() {
        // Leaving must not be undone by the next auto-join poll.
        runtime.suppressAutoJoin()
        controller.leave()
    }

    override fun setMode(mode: RadioMode) {
        scope.launch { prefs.setRadioMode(mode) }
    }

    override fun setAutoJoin(enabled: Boolean) {
        scope.launch { prefs.setRadioAutoJoin(enabled) }
    }

    override fun exportDiagnostics() {
        val diagnostics = runtime.diagnostics() ?: return
        scope.launch { runCatching { exporter.export(diagnostics) } }
    }
}
