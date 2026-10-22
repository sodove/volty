package ru.sodovaya.volty.presentation.history

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** Registration is inert. Only the explicit GPS action opens native permission/settings UI. */
@Composable
actual fun rememberHistoryRecordingLocationRequest(owner: HistoryComponent): () -> Unit {
    val context = LocalContext.current
    var pending by remember(owner) { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val permissions = remember { arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pending = false; owner.retryRecordingLocation()
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending = false; owner.retryRecordingLocation()
    }
    return action@{
        if (pending) return@action
        try {
            val granted = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            when {
                granted && manager != null && LocationManagerCompat.isLocationEnabled(manager) -> owner.retryRecordingLocation()
                granted -> {
                    pending = true
                    settings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                attempted && context.recordingActivity()?.let { activity ->
                    permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
                } == true -> {
                    pending = true
                    settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
                else -> { pending = true; attempted = true; request.launch(permissions) }
            }
        } catch (_: Exception) { pending = false; owner.retryRecordingLocation() }
    }
}

private tailrec fun Context.recordingActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.recordingActivity() else null
    else -> null
}
