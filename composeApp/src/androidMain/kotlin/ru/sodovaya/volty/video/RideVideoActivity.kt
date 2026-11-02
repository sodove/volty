package ru.sodovaya.volty.video

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.arkivanov.decompose.defaultComponentContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.video.*
import ru.sodovaya.volty.presentation.common.VoltyTheme
import ru.sodovaya.volty.presentation.video.VideoDutyHold
import ru.sodovaya.volty.presentation.video.VideoOverlayComponent
import ru.sodovaya.volty.presentation.video.videoTelemetrySnapshot

class RideVideoActivity : ComponentActivity() {
    private val repository: BmsRepository by inject()
    private val appVisibility: ru.sodovaya.volty.data.radio.AppVisibility by inject()
    private val appPrefs: ru.sodovaya.volty.data.prefs.AppPrefs by inject()
    private lateinit var camera: VideoCameraController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val store = getSharedPreferences("ride_video_overlay", MODE_PRIVATE)
        val component = VideoOverlayComponent(defaultComponentContext(),
            OverlayCodec.decode(store.getString("document", "").orEmpty())) { document ->
            store.edit().putString("document", OverlayCodec.encode(document)).apply()
        }
        camera = VideoCameraController(this, this)
        camera.setSegmentMinutes(store.getInt(SEGMENT_MINUTES_KEY, VideoSegmentPolicy.DEFAULT_SEGMENT_MINUTES))
        lifecycleScope.launch {
            appPrefs.unitSystem.collect { camera.unitSystem = it }
        }
        lifecycleScope.launch {
            component.document.collect { camera.preset = it.selected }
        }
        lifecycleScope.launch {
            camera.state.collect { state ->
                requestedOrientation = if (canEditVideoOverlay(state.phase)) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    else ActivityInfo.SCREEN_ORIENTATION_LOCKED
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.onAppResumed()
                val dutyHold = VideoDutyHold()
                combine(repository.activeVehicleData, repository.activeVehicle, repository.connectionState) { data, vehicle, connection ->
                    videoTelemetrySnapshot(data, vehicle, connection, SystemClock.elapsedRealtime(), System.currentTimeMillis(), dutyHold)
                }.collect(camera::offer)
            }
        }
        setContent {
            VoltyTheme {
                VideoCameraScreen(camera, component,
                    onClose = { camera.closeWhenFinalized { finish() } },
                    onSegmentMinutes = { minutes ->
                        camera.setSegmentMinutes(minutes)
                        store.edit().putInt(SEGMENT_MINUTES_KEY, camera.state.value.segmentMinutes).apply()
                    })
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        @Suppress("DEPRECATION") val rotation = windowManager.defaultDisplay.rotation
        camera.updateRotation(rotation)
    }

    override fun onStop() {
        if (::camera.isInitialized) camera.stop()
        lifecycleScope.launch {
            runCatching { if (!appVisibility.isVisible) repository.onAppPaused() }
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::camera.isInitialized) camera.dispose()
        super.onDestroy()
    }

    private companion object {
        const val SEGMENT_MINUTES_KEY = "segment_minutes"
    }
}
