package ru.sodovaya.volty

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.arkivanov.decompose.defaultComponentContext
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.presentation.root.Config
import ru.sodovaya.volty.presentation.root.DefaultRootComponent
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.android.getKoin

class MainActivity : ComponentActivity() {

    private val bmsRepository: BmsRepository by inject()
    private lateinit var rootComponent: DefaultRootComponent
    private var nearbyPermissions: ru.sodovaya.volty.presentation.root.nearby.AndroidNearbyPermissionBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        rootComponent = DefaultRootComponent(defaultComponentContext())
        nearbyPermissions = ru.sodovaya.volty.presentation.root.nearby.AndroidNearbyPermissionBridge(
            this,getKoin().get(),getKoin().get(),getKoin().get(),getKoin().get(),
        )
        acceptIncomingCall(intent)
        setContent {
            App(
                root = rootComponent,
                onOpenLocationSettings = {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
            )
        }

        // MapLibre's native surface can consume the system Back event before
        // Compose's BackHandler gets a chance to see it. Keep the Activity
        // fallback narrowly scoped to the real GroupMap route; all other
        // screens continue through their existing Decompose/Compose handlers.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (rootComponent.stack.value.active.configuration is Config.GroupMap) {
                        rootComponent.onBack()
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
                    if (rootComponent.stack.value.active.configuration is Config.GroupMap) {
                        rootComponent.onBack()
                    } else {
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            )
        }
    }

    // Some MapLibre/Android combinations deliver adb/legacy Back as a key
    // event to the focused native map view instead of the dispatcher. Consume
    // both key phases while GroupMap is active so the Activity cannot finish.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            ::rootComponent.isInitialized &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            rootComponent.stack.value.active.configuration is Config.GroupMap
        ) {
            if (event.action == KeyEvent.ACTION_UP) rootComponent.onBack()
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
            runCatching { bmsRepository.onAppResumed() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIncomingCall(intent)
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
            runCatching { bmsRepository.onAppPaused() }
        }
        super.onStop()
    }
}
