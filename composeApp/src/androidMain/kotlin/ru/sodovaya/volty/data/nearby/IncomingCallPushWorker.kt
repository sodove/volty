package ru.sodovaya.volty.data.nearby

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

/** One bounded metadata attempt plus at most one backoff retry; backend ring credit remains authoritative. */
class IncomingCallPushWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        AndroidCallPushDiagnostics.record(CallPushDiagnosticEvent.WORK_STARTED)
        val data = inputData.keyValueMap.mapValues {(_, value) -> value as? String ?: return Result.success()}
        if (IncomingCallPushHint.decode(data) == null) return Result.success()
        return try {
            val startup = GlobalContext.getOrNull()?.get<AndroidPushStartup>()
            if (startup?.receive(data) == true) Result.success()
            else incomplete()
        } catch (cancelled: CancellationException) {throw cancelled}
          catch (_: Exception) {incomplete()}
    }
    private fun incomplete(): Result {
        AndroidCallPushDiagnostics.record(if (runAttemptCount < 1) CallPushDiagnosticEvent.WORK_RETRY
            else CallPushDiagnosticEvent.WORK_EXHAUSTED)
        return if (runAttemptCount < 1) Result.retry() else Result.success()
    }
}
