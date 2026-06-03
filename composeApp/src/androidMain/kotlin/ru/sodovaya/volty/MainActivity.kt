package ru.sodovaya.volty

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.arkivanov.decompose.defaultComponentContext
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.presentation.root.DefaultRootComponent
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val bmsRepository: BmsRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = DefaultRootComponent(defaultComponentContext())
        setContent { App(root) }
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
}
