package ru.sodovaya.volty.presentation.permissions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.model.DemoProfile
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.permission_bluetooth_action
import volty.composeapp.generated.resources.permission_bluetooth_body
import volty.composeapp.generated.resources.permission_bluetooth_denied
import volty.composeapp.generated.resources.permission_bluetooth_note
import volty.composeapp.generated.resources.permission_bluetooth_note_only
import volty.composeapp.generated.resources.permission_bluetooth_settings
import volty.composeapp.generated.resources.permission_bluetooth_title
import volty.composeapp.generated.resources.permission_cancel
import volty.composeapp.generated.resources.permission_demo_action
import volty.composeapp.generated.resources.permission_demo_body
import volty.composeapp.generated.resources.permission_demo_error
import volty.composeapp.generated.resources.permission_demo_scooter
import volty.composeapp.generated.resources.permission_demo_starting
import volty.composeapp.generated.resources.permission_demo_title
import volty.composeapp.generated.resources.permission_demo_wheel
import volty.composeapp.generated.resources.permission_find_transport
import volty.composeapp.generated.resources.permission_legacy_body
import volty.composeapp.generated.resources.permission_legacy_title
import volty.composeapp.generated.resources.permission_notification_status
import volty.composeapp.generated.resources.permission_notification_warnings
import volty.composeapp.generated.resources.permission_notifications_action
import volty.composeapp.generated.resources.permission_notifications_body
import volty.composeapp.generated.resources.permission_notifications_note
import volty.composeapp.generated.resources.permission_notifications_title
import volty.composeapp.generated.resources.permission_open_settings
import volty.composeapp.generated.resources.permission_receive_data
import volty.composeapp.generated.resources.permission_retry
import volty.composeapp.generated.resources.permission_skip_notifications
import volty.composeapp.generated.resources.permission_step_bluetooth
import volty.composeapp.generated.resources.permission_step_count
import volty.composeapp.generated.resources.permission_step_notifications

/** Platform requests are explicit button actions. All permission decisions live in the component. */
@Composable
fun PermissionsGateScreen(component: PermissionsGateComponent) {
    val state by component.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val activity = context.permissionActivity()
        val blocked = result.any { (permission, granted) ->
            !granted && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
        component.onPermissionResult(result, permanentlyDenied = blocked)
    }
    DisposableEffect(component, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) component.onRefreshPermissions()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    PermissionStepView(
        state = state,
        onPrimary = {
            if (state.openSettings) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            } else {
                val request = component.onRequestPermissions()
                if (request.isNotEmpty()) launcher.launch(request.toTypedArray())
            }
        },
        onSkipNotifications = component::onSkipNotifications,
        onDemo = component::onShowDemo,
    )
    if (state.showDemoPicker) PermissionDemoSheet(component, state)
}

@Composable
private fun PermissionStepView(
    state: PermissionsGateComponent.State,
    onPrimary: () -> Unit,
    onSkipNotifications: () -> Unit,
    onDemo: () -> Unit,
) {
    val bluetooth = state.step == PermissionsGateComponent.Step.BLUETOOTH
    val icon = if (bluetooth) Icons.Default.Bluetooth else Icons.Default.NotificationsNone
    val title = when {
        !bluetooth -> Res.string.permission_notifications_title
        state.usesLegacyLocation -> Res.string.permission_legacy_title
        else -> Res.string.permission_bluetooth_title
    }
    val body = when {
        !bluetooth -> Res.string.permission_notifications_body
        state.usesLegacyLocation -> Res.string.permission_legacy_body
        else -> Res.string.permission_bluetooth_body
    }
    val primary = when {
        state.openSettings -> Res.string.permission_open_settings
        !bluetooth -> Res.string.permission_notifications_action
        state.denied -> Res.string.permission_retry
        else -> Res.string.permission_bluetooth_action
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    PermissionSteps(state)
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(if (bluetooth) Res.string.permission_step_bluetooth else Res.string.permission_step_notifications),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(stringResource(title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PermissionBenefit(
                            if (bluetooth) Icons.Default.Search else Icons.Default.Sensors,
                            stringResource(if (bluetooth) Res.string.permission_find_transport else Res.string.permission_notification_status),
                        )
                        PermissionBenefit(
                            if (bluetooth) Icons.Default.BatteryChargingFull else Icons.Default.NotificationsNone,
                            stringResource(if (bluetooth) Res.string.permission_receive_data else Res.string.permission_notification_warnings),
                        )
                    }
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(
                            stringResource(when {
                                state.openSettings -> Res.string.permission_bluetooth_settings
                                state.denied -> Res.string.permission_bluetooth_denied
                                bluetooth && state.hasNotificationStep -> Res.string.permission_bluetooth_note
                                bluetooth -> Res.string.permission_bluetooth_note_only
                                else -> Res.string.permission_notifications_note
                            }),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(primary))
                    }
                    if (!bluetooth) {
                        TextButton(onClick = onSkipNotifications, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(Res.string.permission_skip_notifications))
                        }
                    }
                    TextButton(onClick = onDemo, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(Res.string.permission_demo_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionSteps(state: PermissionsGateComponent.State) {
    val count = if (state.hasNotificationStep) 2 else 1
    val current = if (state.step == PermissionsGateComponent.Step.BLUETOOTH) 1 else count
    val description = stringResource(Res.string.permission_step_count, current, count)
    Row(Modifier.semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            Box(Modifier.size(width = 32.dp, height = 4.dp).background(
                if (index < current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(2.dp),
            ))
        }
    }
}

@Composable
private fun PermissionBenefit(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionDemoSheet(component: PermissionsGateComponent, state: PermissionsGateComponent.State) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !component.state.value.demoStarting },
    )
    ModalBottomSheet(onDismissRequest = component::onDismissDemo, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(Res.string.permission_demo_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.permission_demo_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.demoStarting) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(Res.string.permission_demo_starting))
                }
            }
            if (state.demoFailed) Text(stringResource(Res.string.permission_demo_error), color = MaterialTheme.colorScheme.error)
            for (profile in DemoProfile.entries) {
                OutlinedButton(
                    onClick = { scope.launch { component.onTryDemo(profile) } },
                    enabled = !state.demoStarting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(stringResource(if (profile == DemoProfile.SCOOTER) Res.string.permission_demo_scooter else Res.string.permission_demo_wheel))
                }
            }
            TextButton(onClick = component::onDismissDemo, enabled = !state.demoStarting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.permission_cancel))
            }
        }
    }
}

private tailrec fun Context.permissionActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.permissionActivity()
    else -> null
}
