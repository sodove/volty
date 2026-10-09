package ru.sodovaya.volty.data.nearby

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** SDK Tasks may finish after caller cancellation; the account owner fences every returned value. */
internal class AndroidFcmTokenSource(context: Context) : PushTokenSource {
    private val context = context.applicationContext
    private fun messaging(): FirebaseMessaging? = if (FirebaseApp.getApps(context).any {it.name == FirebaseApp.DEFAULT_APP_NAME})
        FirebaseMessaging.getInstance() else null
    override fun setActive(active: Boolean) {messaging()?.isAutoInitEnabled = active}
    override suspend fun current(): String? = try {
        val token = messaging()?.token?.privateAwait()
        AndroidCallPushDiagnostics.record(if (token == null) CallPushDiagnosticEvent.TOKEN_UNAVAILABLE
            else CallPushDiagnosticEvent.TOKEN_AVAILABLE)
        token
    } catch (failure: Exception) {
        AndroidCallPushDiagnostics.record(if (failure is CancellationException) CallPushDiagnosticEvent.CANCELLED
            else CallPushDiagnosticEvent.TOKEN_UNAVAILABLE)
        throw failure
    }
    override suspend fun renew(): String? {
        val messaging = messaging() ?: return null
        messaging.deleteToken().privateAwait()
        return messaging.token.privateAwait()
    }
    private suspend fun <T> Task<T>.privateAwait(): T = suspendCancellableCoroutine {continuation ->
        addOnCompleteListener {task ->
            if (continuation.isActive) when {
                task.isCanceled -> continuation.cancel(CancellationException("Firebase token operation cancelled"))
                task.isSuccessful -> continuation.resume(task.result)
                else -> continuation.resumeWithException(IOException("Firebase token unavailable"))
            }
        }
    }
}
