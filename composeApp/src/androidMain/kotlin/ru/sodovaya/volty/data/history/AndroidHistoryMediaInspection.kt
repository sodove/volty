package ru.sodovaya.volty.data.history

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import ru.sodovaya.volty.domain.history.*
import java.io.File

/** Checks the copied bytes, never trusts a picker filename or its MIME hint. */
fun inspectAndroidHistoryMedia(file: File, hint: String): HistoryMediaInspection {
    fun invalid(): Nothing = throw HistoryMediaException(HistoryMediaFailure.INVALID_MEDIA)
    if (hint.startsWith("image/")) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (!historyMediaImageBoundsAllowed(bounds.outWidth, bounds.outHeight)) invalid()
        val options = BitmapFactory.Options().apply {
            inSampleSize = historyMediaImageSampleSize(bounds.outWidth, bounds.outHeight, 2048)
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: invalid()
        bitmap.recycle()
        val mime = bounds.outMimeType?.takeIf { it.startsWith("image/") } ?: invalid()
        val captured = runCatching {
            val exif = ExifInterface(file)
            // OffsetTimeOriginal is API 30. An unqualified local EXIF clock is
            // unknown, including on older Android; no current-zone fallback.
            val offset = if (Build.VERSION.SDK_INT >= 30) exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) else null
            historyMediaCaptureTime(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), offset,
                exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
        }.getOrNull()
        return HistoryMediaInspection(mime, captured)
    }
    if (!hint.startsWith("video/")) invalid()
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: invalid()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: invalid()
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: invalid()
        if (!historyMediaImageBoundsAllowed(width, height) || duration <= 0) invalid()
        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.takeIf { it.startsWith("video/") } ?: invalid()
        // Android's DATE may mean creation OR modification. It does not earn
        // a capture timestamp, and cannot place a video on the recorded route.
        return HistoryMediaInspection(mime, null)
    } catch (failure: HistoryMediaException) { throw failure }
    catch (_: RuntimeException) { invalid() }
    finally { retriever.release() }
}

internal fun historyMediaImageBoundsAllowed(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && width.toLong() * height <= 120_000_000L

internal fun historyMediaImageSampleSize(width: Int, height: Int, edge: Int): Int {
    require(edge > 0 && width > 0 && height > 0)
    var sample = 1
    while (width.toLong() > edge.toLong() * sample || height.toLong() > edge.toLong() * sample) sample *= 2
    return sample
}
