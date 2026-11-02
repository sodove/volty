package ru.sodovaya.volty.video

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import ru.sodovaya.volty.domain.video.PendingVideoAction
import ru.sodovaya.volty.domain.video.PendingVideoRow
import ru.sodovaya.volty.domain.video.pendingVideoAction

/**
 * Startup sweep for ride videos a crash left behind: CameraX marks a MediaStore row `IS_PENDING` when a recording
 * starts and clears it on finalize, so a process that died in between leaves a row (and its bytes) hidden from the
 * gallery. The decision per row is the pure [pendingVideoAction]; this is only the MediaStore plumbing, and it can
 * only be judged on a device (Q and later; below that there is no pending flag and the file simply sits in
 * Movies/Volty).
 *
 * The size comes from the opened file, never from the SIZE column: for a pending row that column may still say 0
 * while the file holds minutes of video, and acting on it would delete the rider's footage. Anything that cannot
 * be read is left alone.
 */
internal object PendingVideoSweeper {
    private const val TAG = "RideVideoSweep"

    /** Returns how many rows were published or removed. Never throws. */
    fun run(context: Context, nowMillis: Long = System.currentTimeMillis()): Int = try {
        if (Build.VERSION.SDK_INT < 29) 0 else sweep(context, nowMillis)
    } catch (error: Exception) {
        Log.w(TAG, "Pending video sweep failed", error)
        0
    }

    private fun sweep(context: Context, nowMillis: Long): Int {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val ids = mutableListOf<Pair<Long, Long>>()
        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(context.packageName, "Movies/Volty%")
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED)
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            val extras = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            }
            resolver.query(collection, projection, extras, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(collection), projection, selection, args, null)
        }
        cursor?.use {
            while (it.moveToNext()) ids += it.getLong(0) to it.getLong(1) * 1000L
        }
        var handled = 0
        for ((id, modifiedMillis) in ids) {
            val uri = ContentUris.withAppendedId(collection, id)
            val size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
            if (size == null || size < 0) {
                Log.w(TAG, "Cannot read pending row $id; leaving it")
                continue
            }
            when (pendingVideoAction(PendingVideoRow(id, size, modifiedMillis), nowMillis)) {
                PendingVideoAction.LEAVE -> Unit
                PendingVideoAction.DELETE_EMPTY -> {
                    Log.i(TAG, "Removing empty pending row $id ($size bytes)")
                    if (runCatching { resolver.delete(uri, null, null) }.getOrDefault(0) > 0) handled++
                }
                PendingVideoAction.PUBLISH -> {
                    Log.i(TAG, "Publishing unfinished recording $id ($size bytes)")
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    if (runCatching { resolver.update(uri, values, null, null) }.getOrDefault(0) > 0) handled++
                }
            }
        }
        return handled
    }
}
