package ru.sodovaya.volty.data.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.sodovaya.volty.domain.voice.VoiceOperationId
import java.util.UUID

/** No media allocation. The receiver owns only submission, never the negative HTTP producer. */
class IncomingCallDeclineReceiver : BroadcastReceiver(), KoinComponent {
    private val registry: IncomingCallActionRegistry by inject()
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidIncomingCallPendingIntents.DECLINE) return
        val address = IncomingCallActionAddress.parse(intent.dataString ?: return) ?: return
        if (address.kind != IncomingCallActionKind.DECLINE) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            try {
                registry.decline(address.bindingId, address.ticketId, VoiceOperationId(UUID.randomUUID().toString()))
            } catch (_: Exception) {
                Log.w("NearbyIncoming", "Incoming decline submission failed")
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
