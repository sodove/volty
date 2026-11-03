package ru.sodovaya.volty

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.arkivanov.decompose.retainedComponent
import ru.sodovaya.volty.data.db.DatabaseOpenGate
import ru.sodovaya.volty.data.db.DatabaseOpenState
import ru.sodovaya.volty.diagnostics.DatabaseShareOutcome
import ru.sodovaya.volty.diagnostics.DatabaseSnapshotExporter
import ru.sodovaya.volty.diagnostics.LogExporter
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.presentation.root.Config
import ru.sodovaya.volty.presentation.root.DatabaseGateScreen
import ru.sodovaya.volty.presentation.root.DefaultRootComponent
import ru.sodovaya.volty.presentation.root.databaseGateScreenModel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.android.getKoin

class MainActivity : ComponentActivity() {

    private val bmsRepository: BmsRepository by inject()
    private val appVisibility: ru.sodovaya.volty.data.radio.AppVisibility by inject()
    private val historyLocation: ru.sodovaya.volty.data.history.AndroidHistoryLocationForegroundController by inject()
    private val databaseGate: DatabaseOpenGate by inject()
    /**
     * Null until the database is open: the root builds the screens, which read it. Written on the
     * main thread only, from [attachRootWhenDatabaseReady].
     */
    private var rootComponent: DefaultRootComponent? by mutableStateOf(null)
    private var databaseShareBusy by mutableStateOf(false)
    private var databaseShareOutcome: DatabaseShareOutcome? by mutableStateOf(null)
    private var nearbyPermissions: ru.sodovaya.volty.presentation.root.nearby.AndroidNearbyPermissionBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        historyLocation.attachHost(this)
        // Must stay here: it registers an activity-result launcher, which is refused once STARTED.
        nearbyPermissions = ru.sodovaya.volty.presentation.root.nearby.AndroidNearbyPermissionBridge(
            this,getKoin().get(),getKoin().get(),getKoin().get(),getKoin().get(),
        )
        setContent {
            val databaseState by databaseGate.state.collectAsState()
            val root = rootComponent
            if (databaseState is DatabaseOpenState.Ready && root != null) {
                App(
                    root = root,
                    onOpenLocationSettings = {
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    },
                )
            } else {
                // Not a dialog and no dismiss action: the app behind it does not exist yet.
                DatabaseGateScreen(
                    model = databaseGateScreenModel(databaseState, databaseShareBusy, databaseShareOutcome),
                    onRetry = databaseGate::retry,
                    onSendLogs = { getKoin().get<LogExporter>().exportLogs() },
                    onShareDatabase = ::shareUnopenedDatabase,
                )
            }
        }
        keepScreenOnWhileMigrating()
        attachRootWhenDatabaseReady()

        // MapLibre's native surface can consume the system Back event before
        // Compose's BackHandler gets a chance to see it. Keep the Activity
        // fallback narrowly scoped to the real GroupMap route; all other
        // screens continue through their existing Decompose/Compose handlers.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val root = rootComponent
                    if (root != null && root.stack.value.active.configuration is Config.GroupMap) {
                        root.onBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )

        // API 33+ routes the gesture/Back button through OnBackInvoked before
        // the legacy dispatcher. Register at overlay priority so a focused
        // native MapView cannot terminate this Activity. Outside GroupMap we
        // hand the event back to the regular dispatcher unchanged.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                OnBackInvokedCallback {
                    val root = rootComponent
                    if (root != null && root.stack.value.active.configuration is Config.GroupMap) {
                        root.onBack()
                    } else {
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            )
        }
    }

    /**
     * The root, and the first intent, wait for the database: the root's screens query it, and a
     * query on this thread would open (and migrate) it here. The database is already open on every
     * start after the one that follows an update, so this is normally a launch that does not suspend.
     */
    private fun attachRootWhenDatabaseReady() {
        lifecycleScope.launch {
            databaseGate.awaitReady()
            if (rootComponent != null) return@launch
            // Retained across configuration changes: a rotation used to rebuild the whole root, and with it
            // the navigator's route, which lives in memory only. The root holds application-scoped
            // dependencies alone, so outliving one Activity instance leaks nothing.
            rootComponent = retainedComponent { context -> DefaultRootComponent(context) }
            acceptIncomingCall(intent)
        }
    }

    private fun keepScreenOnWhileMigrating() {
        lifecycleScope.launch {
            databaseGate.state.collect { state ->
                if (state is DatabaseOpenState.Migrating) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun shareUnopenedDatabase() {
        if (databaseShareBusy) return
        databaseShareBusy = true
        databaseShareOutcome = null
        lifecycleScope.launch {
            val outcome = runCatching { getKoin().get<DatabaseSnapshotExporter>().shareUnopened() }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else DatabaseShareOutcome.Failed }
            databaseShareBusy = false
            databaseShareOutcome = outcome
        }
    }

    // Some MapLibre/Android combinations deliver adb/legacy Back as a key
    // event to the focused native map view instead of the dispatcher. Consume
    // both key phases while GroupMap is active so the Activity cannot finish.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val root = rootComponent
        if (
            root != null &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            root.stack.value.active.configuration is Config.GroupMap
        ) {
            if (event.action == KeyEvent.ACTION_UP) root.onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        // Defense-in-depth: when Doze / App-Standby / a killed foreground
        // service suspends our dispatchers, the in-session watchdog can't
        // tick. On resume we re-validate sample freshness and force the same
        // drop pathway if we've been stuck on Connected with stale data.
        lifecycleScope.launch {
            databaseGate.awaitReady()
            runCatching { bmsRepository.onAppResumed() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Before the root exists, attachRootWhenDatabaseReady reads the (updated) activity intent itself.
        if (rootComponent != null) acceptIncomingCall(intent)
    }

    private fun acceptIncomingCall(incoming: Intent?) {
        if(incoming?.action != ru.sodovaya.volty.data.voice.AndroidIncomingCallPendingIntents.ACCEPT) return
        val address=incoming.dataString ?: return
        // Consume the Activity delivery; the original registry ticket also rejects recreation/replay.
        incoming.action=null;incoming.data=null
        lifecycleScope.launch {
            runCatching {
                getKoin().get<ru.sodovaya.volty.presentation.root.nearby.NearbyIncomingCallAcceptance>()
                    .accept(address,getString(R.string.nearby_incoming_notification_title))
            }
        }
    }

    override fun onDestroy() {
        nearbyPermissions?.close()
        nearbyPermissions=null
        super.onDestroy()
    }

    override fun onStop() {
        lifecycleScope.launch {
            // The ride camera is a separate Activity. Leaving the dashboard for it must
            // not downgrade the live BLE connection while another app screen is visible.
            runCatching { if (!appVisibility.isVisible) bmsRepository.onAppPaused() }
        }
        super.onStop()
    }
}
