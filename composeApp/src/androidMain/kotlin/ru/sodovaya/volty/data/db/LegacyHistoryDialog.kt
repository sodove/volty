package ru.sodovaya.volty.data.db

import android.app.AlertDialog
import android.content.DialogInterface
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.R

internal fun ComponentActivity.observeLegacyHistory(gate: DatabaseOpenGate, opener: AndroidDatabaseOpener) {
    lifecycleScope.launch {
        gate.awaitReady()
        suspend fun remaining() = withContext(Dispatchers.IO) { pendingLegacyRideCount(opener.rawDriver) }
        val initial = remaining()
        if (initial == 0L || isFinishing || isDestroyed) return@launch

        fun message(count: Long) = getString(R.string.legacy_history_message, count)
        val dialog = AlertDialog.Builder(this@observeLegacyHistory)
            .setTitle(R.string.legacy_history_title)
            .setMessage(message(initial))
            .setPositiveButton(R.string.legacy_history_resume, null)
            .setNegativeButton(R.string.legacy_history_later) { _, _ ->
                LegacyHistoryWorker.pause(this@observeLegacyHistory)
            }
            .setNeutralButton(R.string.legacy_history_hide, null)
            .create()
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            LegacyHistoryWorker.enqueue(this@observeLegacyHistory)
            it.isEnabled = false
        }
        try {
            while (dialog.isShowing) {
                delay(1_500)
                val count = remaining()
                if (count == 0L) break
                dialog.setMessage(message(count))
            }
        } finally {
            dialog.dismiss()
        }
    }
}
