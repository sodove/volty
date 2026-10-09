package ru.sodovaya.volty.data.voice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import ru.sodovaya.volty.MainActivity
import ru.sodovaya.volty.R
import ru.sodovaya.volty.domain.voice.VoiceOperationId
import ru.sodovaya.volty.presentation.common.VoltyTheme
import java.util.UUID

/** Opening/locking/recreating this screen cannot answer or allocate media. */
class IncomingCallActivity : ComponentActivity() {
    private val registry: IncomingCallActionRegistry by inject()
    private var observation: Job? = null
    private var address: IncomingCallActionAddress? = null
    private var offer by mutableStateOf<IncomingCallOffer?>(null)
    private var busy by mutableStateOf(false)
    private var failed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            // API 26 predates the Activity methods; keep its equivalent window flags.
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        observe(intent)
        setContent { VoltyTheme {
            IncomingCallScreen(offer?.callerName, busy, failed, ::answer, ::decline)
        } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        observe(intent)
    }

    private fun observe(incoming: Intent) {
        observation?.cancel()
        val target = IncomingCallActionAddress.parse(incoming.dataString ?: "")
            ?.takeIf {incoming.action == AndroidIncomingCallPendingIntents.OPEN && it.kind == IncomingCallActionKind.OPEN}
        address = target; offer = null; busy = false; failed = false
        if (target == null) {finish(); return}
        observation = lifecycleScope.launch {
            // Recheck the original native ticket, including when caller/account state changes.
            registry.offer.collect {
                val current = registry.displayTicket(target)
                if (current == null) {finish(); return@collect}
                offer = current
            }
        }
    }

    private fun answer() {
        val target = address?.takeIf {offer != null && !busy} ?: return
        busy = true; failed = false
        // Only this explicit tap creates ACCEPT. MainActivity retains permission/admission gates.
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .setAction(AndroidIncomingCallPendingIntents.ACCEPT)
                .setData(Uri.parse(target.copy(kind = IncomingCallActionKind.ACCEPT).uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        } catch (_: Exception) {busy = false; failed = true}
    }

    private fun decline() {
        val target = address?.takeIf {offer != null && !busy} ?: return
        busy = true; failed = false
        lifecycleScope.launch {
            try {
                // Submission transfers to the existing account-owned producer, not Activity lifetime.
                registry.decline(target.bindingId, target.ticketId, VoiceOperationId(UUID.randomUUID().toString()))
                finish()
            } catch (cancelled: CancellationException) {throw cancelled}
            catch (_: Exception) {busy = false; failed = true}
        }
    }
}

@Composable
private fun IncomingCallScreen(name: String?, busy: Boolean, failed: Boolean, answer: () -> Unit, decline: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.safeDrawingPadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.nearby_incoming_screen_brand), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(40.dp))
            Surface(Modifier.size(112.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {Icon(Icons.Default.Person, null, Modifier.size(56.dp))}
            }
            Spacer(Modifier.height(28.dp))
            AnimatedContent(name, label = "Incoming caller") {caller ->
                Text(caller ?: stringResource(R.string.nearby_incoming_notification_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.nearby_incoming_notification_title),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(64.dp))
            Row(Modifier.fillMaxWidth().widthIn(max = 420.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallAction(stringResource(R.string.nearby_incoming_notification_decline), Icons.Default.CallEnd,
                    MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, !busy, decline)
                CallAction(stringResource(R.string.nearby_incoming_notification_accept), Icons.Default.Call,
                    MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, !busy, answer)
            }
            if (failed) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.nearby_incoming_screen_launch_failed), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CallAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color,
    enabled: Boolean, click: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = click, enabled = enabled, modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = content)) {
            Icon(icon, label, Modifier.size(32.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
