package ru.sodovaya.volty.data.db

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import ru.sodovaya.volty.data.history.convertLegacyRide

/** Each committed ride is its own checkpoint. WorkManager can restart interrupted work. */
class LegacyHistoryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val opener = GlobalContext.getOrNull()?.get<AndroidDatabaseOpener>() ?: return@withContext Result.retry()
        val driver = opener.rawDriver
        val database = VoltyDatabase(driver)
        val deadline = SystemClock.elapsedRealtime() + RUN_BUDGET_MILLIS
        try {
            if (!hasLegacyHistory(driver)) return@withContext Result.success()
            while (SystemClock.elapsedRealtime() < deadline) {
                ensureActive()
                val converted = database.transactionWithResult {
                    val rideId = nextLegacyRide(driver) ?: return@transactionWithResult false
                    convertLegacyRide(driver, rideId)
                    true
                }
                if (!converted) return@withContext Result.success()
            }
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("VoltyDb", "Legacy history worker interrupted", failure)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "legacy-history-conversion"
        private const val RUN_BUDGET_MILLIS = 360_000L

        internal fun pause(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        internal fun enqueue(context: Context) {
            val work = OneTimeWorkRequestBuilder<LegacyHistoryWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, work)
        }
    }
}
