package ru.sodovaya.volty.data.nearby

import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/** Short SDK callback: metadata verification continues in a valid WorkManager process lifetime. */
class VoltyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Treat the callback as a hint; query the SDK's current token instead of applying a late value.
        GlobalContext.getOrNull()?.get<AndroidPushStartup>()?.tokenHint()
    }
    override fun onMessageReceived(message: RemoteMessage) {
        val hint = IncomingCallPushHint.decode(message.data) ?: return
        val data = Data.Builder().apply {message.data.forEach {(key, value) -> putString(key, value)}}.build()
        val request = OneTimeWorkRequestBuilder<IncomingCallPushWorker>().setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // WorkManager's default 30s backoff consumes the entire backend ringing window.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
        // Pre-31 expedited work requires a foreground notification; no unverified call notification is created.
        if (Build.VERSION.SDK_INT >= 31 && message.priority == RemoteMessage.PRIORITY_HIGH)
            request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "nearby-call:${hint.bindingId}:${hint.generation}:${hint.callId}:${hint.revision}", ExistingWorkPolicy.KEEP, request.build())
    }
}
