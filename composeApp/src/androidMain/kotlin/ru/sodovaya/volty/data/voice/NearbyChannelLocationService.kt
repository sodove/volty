package ru.sodovaya.volty.data.voice

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.koin.android.ext.android.inject
import ru.sodovaya.volty.MainActivity
import ru.sodovaya.volty.R

/** Location lifetime is independent of voice and retains original native cleanup owners. */
class NearbyChannelLocationService : Service() {
    private val controller: AndroidChannelLocationForegroundController by inject()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val owners=mutableSetOf<ChannelLocationForegroundOwner>()
    private var bound: ChannelLocationForegroundTicket?=null
    private var foregroundStarted=false
    private var finishing=false
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onCreate() {
        super.onCreate()
        scope.launch {controller.current.collect {maybeFinish()}}
        scope.launch {while(isActive) {delay(1_000);controller.refreshPermissions();maybeFinish()}}
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val ticket=intent?.getStringExtra(TICKET)?.let(controller::find)
        if(intent?.action==STOP) {
            if(ticket!=null && bound===ticket) controller.stop(ticket)
            maybeFinish();if(!foregroundStarted) stopSelf(startId);return START_NOT_STICKY
        }
        val owner=ticket?.owner as? ChannelLocationForegroundOwner
        if(intent?.action!=START || ticket==null || owner==null) {
            if(!foregroundStarted) stopSelf(startId);return START_NOT_STICKY
        }
        observe(owner)
        try {
            val notification=notification(ticket)
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)
                startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION,notification)
            foregroundStarted=true;finishing=false
            if(!controller.acknowledge(ticket,this)) {controller.stop(ticket);maybeFinish();return START_NOT_STICKY}
            bound=ticket
        } catch(_: Exception) {
            controller.stop(ticket);if(!foregroundStarted) stopSelf(startId) else maybeFinish()
        }
        return START_NOT_STICKY
    }
    private fun observe(owner: ChannelLocationForegroundOwner) {
        if(!owners.add(owner)) return
        scope.launch {owner.locationForegroundState.collect {state ->
            if(!state.wanted || state.retired) controller.current.value?.takeIf {it.owner===owner}?.let(controller::stop)
            maybeFinish()
        }}
    }
    private fun maybeFinish() {
        if(!foregroundStarted || finishing) return
        if(canReleaseChannelLocationForeground(controller.current.value!=null,owners.map {it.locationForegroundState.value})) {
            finishing=true;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        }
    }
    private fun notification(ticket: ChannelLocationForegroundTicket): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.nearby_location_notification_title),NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,NOTIFICATION,Intent(this,javaClass).setAction(STOP)
            .setData(Uri.parse("volty://channel-location/${ticket.id}/hide")).putExtra(TICKET,ticket.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,NOTIFICATION,Intent(this,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.nearby_location_notification_title))
            .setContentText(getString(R.string.nearby_location_notification_body)).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,getString(R.string.nearby_location_notification_hide),stop).build()
    }
    override fun onDestroy() {try {controller.destroyed(this)} finally {scope.cancel();super.onDestroy()}}
    companion object {
        const val START="ru.sodovaya.volty.nearby.location.START"
        const val STOP="ru.sodovaya.volty.nearby.location.STOP"
        const val TICKET="location_foreground_ticket"
        private const val CHANNEL="nearby_channel_location"
        private const val NOTIFICATION=1044
    }
}
