package ru.sodovaya.volty.presentation.nearby

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.data.voice.INCOMING_CALL_NOTIFICATION_CHANNEL
import ru.sodovaya.volty.data.nearby.AndroidCallPushDiagnostics
import volty.composeapp.generated.resources.*

private enum class NotificationSettingsState { ALLOWED, APP_BLOCKED, CHANNEL_BLOCKED, UNKNOWN }

private fun fullScreenAllowed(context: Context): Boolean? = try {
    Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
} catch (_: Exception) {null}

/** Android eligibility is not evidence of notification delivery or background process lifetime. */
private fun notificationSettings(context: Context): NotificationSettingsState = try {
    when {
        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> NotificationSettingsState.APP_BLOCKED
        !NotificationManagerCompat.from(context).areNotificationsEnabled() -> NotificationSettingsState.APP_BLOCKED
        context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(INCOMING_CALL_NOTIFICATION_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE -> NotificationSettingsState.CHANNEL_BLOCKED
        else -> NotificationSettingsState.ALLOWED
    }
} catch (_: Exception) { NotificationSettingsState.UNKNOWN }

@Composable
internal actual fun NearbyCallNotificationSettings(modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var availability by remember(context) { mutableStateOf(notificationSettings(context)) }
    var fullScreen by remember(context) { mutableStateOf(fullScreenAllowed(context)) }
    var launchFailed by remember(context) { mutableStateOf(false) }
    var diagnosticCopied by remember(context) { mutableStateOf(false) }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                availability = notificationSettings(context)
                fullScreen = fullScreenAllowed(context)
            }
        }
        lifecycle.addObserver(observer)
        availability = notificationSettings(context)
        onDispose { lifecycle.removeObserver(observer) }
    }
    OutlinedCard(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.nearby_notifications_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(when (availability) {
                NotificationSettingsState.ALLOWED -> Res.string.nearby_notifications_allowed
                NotificationSettingsState.APP_BLOCKED, NotificationSettingsState.CHANNEL_BLOCKED -> Res.string.nearby_notifications_blocked
                NotificationSettingsState.UNKNOWN -> Res.string.nearby_notifications_unknown
            }))
            Text(stringResource(Res.string.nearby_notifications_hint), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                // Re-read before navigation: settings may have changed since the last RESUME.
                availability = notificationSettings(context)
                val settings = Intent(if (availability == NotificationSettingsState.CHANNEL_BLOCKED)
                    Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                if (availability == NotificationSettingsState.CHANNEL_BLOCKED)
                    settings.putExtra(Settings.EXTRA_CHANNEL_ID, INCOMING_CALL_NOTIFICATION_CHANNEL)
                fun open(intent: Intent): Boolean = try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                } catch (_: Exception) { false }
                launchFailed = !open(settings) && !open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.packageName)))
            }) { Text(stringResource(Res.string.nearby_notifications_settings)) }
            if (launchFailed) Text(stringResource(Res.string.nearby_notifications_launch_failed),
                color = MaterialTheme.colorScheme.error)
            if (Build.VERSION.SDK_INT >= 34) {
                HorizontalDivider()
                Text(stringResource(Res.string.nearby_calls_full_screen_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(when (fullScreen) {
                    true -> Res.string.nearby_calls_full_screen_allowed
                    false -> Res.string.nearby_calls_full_screen_blocked
                    null -> Res.string.nearby_notifications_unknown
                }), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    launchFailed = try {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        false
                    } catch (_: Exception) {true}
                }) {Text(stringResource(Res.string.nearby_calls_full_screen_settings))}
            }
            TextButton(onClick = {
                availability = notificationSettings(context)
                val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                fullScreen = fullScreenAllowed(context)
                val report = "volty=$version; android=${Build.VERSION.SDK_INT}; notification_settings=$availability; full_screen=$fullScreen\n" +
                    AndroidCallPushDiagnostics.export()
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Volty call diagnostics", report))
                diagnosticCopied = true
            }) { Text(stringResource(Res.string.nearby_notifications_copy_diagnostics)) }
            if (diagnosticCopied) Text(stringResource(Res.string.nearby_notifications_diagnostics_copied),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
