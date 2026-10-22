package ru.sodovaya.volty.presentation.history

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.data.history.historyMediaImageBoundsAllowed
import ru.sodovaya.volty.data.history.historyMediaImageSampleSize
import volty.composeapp.generated.resources.*

@Composable
actual fun rememberHistoryMediaPicker(owner: HistoryComponent, onUnavailable: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val unavailable by rememberUpdatedState(onUnavailable)
    var token by remember(owner) { mutableStateOf<HistoryMediaPickerToken?>(null) }
    DisposableEffect(owner) { onDispose { token?.let { owner.mediaPicked(it, null, null) }; token = null } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val captured = token; token = null
        if (captured != null) {
            val mime = try { uri?.let { context.contentResolver.getType(it) } } catch (_: SecurityException) { null }
            owner.mediaPicked(captured, uri?.toString(), mime)
        }
    }
    return {
        owner.beginMediaPick()?.let { captured ->
            token = captured
            try { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
            catch (_: ActivityNotFoundException) { token = null; owner.mediaPicked(captured, null, null); unavailable() }
        }
    }
}

@Composable
actual fun HistoryMediaThumbnail(uri: String?, mime: String, description: String, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri, mime) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri, mime) {
        if (uri == null || !uri.startsWith("content://")) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            try {
                if (mime.startsWith("video/")) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, Uri.parse(uri))
                        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                    } finally { retriever.release() }
                } else decodeHistoryImage(context, uri, 320)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
    }
    // Compose may still draw the previous frame after disposal. Let Android GC
    // retire these bounded thumbnails rather than recycling a displayed bitmap.
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Default.Image, description)
        if (mime.startsWith("video/")) Icon(Icons.Default.PlayArrow, description,
            Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape).padding(2.dp), tint = Color.White)
    }
}

@Composable
actual fun HistoryMediaViewer(open: HistoryMediaOpen, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var image by remember(open) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(open) { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open.mime.startsWith("image/")) {
            image = withContext(Dispatchers.IO) {
                try { decodeHistoryImage(context, open.uri, 2048) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            }
            failed = image == null
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.history_media_close)) }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (failed) Text(stringResource(Res.string.history_media_unavailable), Modifier.padding(24.dp))
                    else if (open.mime.startsWith("video/")) AndroidView(
                        factory = { androidContext -> HistoryVideoView(androidContext).apply {
                            setOnErrorListener { _, _, _ -> failed = true; true }
                            setVideoURI(Uri.parse(open.uri))
                            // Playback is explicit; opening a ride never starts audio.
                            setOnPreparedListener { seekTo(1); controls.show(0) }
                        } }, modifier = Modifier.fillMaxSize(), onRelease = { it.release() })
                    else image?.let { Image(it.asImageBitmap(), stringResource(Res.string.history_media_open),
                        Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: CircularProgressIndicator()
                }
            }
        }
    }
}

private class HistoryVideoView(context: android.content.Context) : VideoView(context) {
    val controls = MediaController(context).also { it.setAnchorView(this); setMediaController(it) }
    fun release() { controls.hide(); stopPlayback(); setMediaController(null) }
}

private fun decodeHistoryImage(context: android.content.Context, uri: String, edge: Int): Bitmap? {
    val parsed = Uri.parse(uri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds); Unit } ?: return null
    if (!historyMediaImageBoundsAllowed(bounds.outWidth, bounds.outHeight)) return null
    val options = BitmapFactory.Options().apply { inSampleSize = historyMediaImageSampleSize(bounds.outWidth, bounds.outHeight, edge) }
    val bitmap = context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val orientation = runCatching { context.contentResolver.openInputStream(parsed)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } }.getOrNull()
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
    }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
}
