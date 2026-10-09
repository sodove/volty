package ru.sodovaya.volty.data.voice

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import ru.sodovaya.volty.MainActivity

internal class AndroidIncomingCallPendingIntents(context: Context,
    private val registry: IncomingCallActionRegistry) : IncomingCallNotificationPendingIntents {
    private val application = context.applicationContext
    override fun open(ticket: IncomingCallNotificationTicket): PendingIntent {
        val address = address(ticket, IncomingCallActionKind.OPEN)
        return PendingIntent.getActivity(application, REQUEST,
            Intent(application, IncomingCallActivity::class.java).setAction(OPEN).setData(Uri.parse(address.uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), FLAGS)
    }
    override fun accept(ticket: IncomingCallNotificationTicket): PendingIntent {
        val address = address(ticket, IncomingCallActionKind.ACCEPT)
        return PendingIntent.getActivity(application, REQUEST,
            Intent(application, MainActivity::class.java).setAction(ACCEPT).setData(Uri.parse(address.uri))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), FLAGS)
    }
    override fun decline(ticket: IncomingCallNotificationTicket): PendingIntent {
        val address = address(ticket, IncomingCallActionKind.DECLINE)
        return PendingIntent.getBroadcast(application, REQUEST,
            Intent(application, IncomingCallDeclineReceiver::class.java).setAction(DECLINE).setData(Uri.parse(address.uri)), FLAGS)
    }
    private fun address(ticket: IncomingCallNotificationTicket, kind: IncomingCallActionKind): IncomingCallActionAddress {
        val binding = requireNotNull(registry.bindingFor(ticket)) {"Incoming ticket is not bound for publication"}
        return IncomingCallActionAddress(binding.id, ticket.id, kind)
    }
    companion object {
        const val OPEN = "ru.sodovaya.volty.nearby.call.OPEN"
        const val ACCEPT = "ru.sodovaya.volty.nearby.call.ACCEPT"
        const val DECLINE = "ru.sodovaya.volty.nearby.call.DECLINE"
        private const val REQUEST = 1043
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
