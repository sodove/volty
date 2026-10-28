package ru.sodovaya.volty.presentation.history

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.data.history.HistoryExportFileCache
import ru.sodovaya.volty.domain.history.HistoryExportFormat
import ru.sodovaya.volty.domain.history.HistoryExportResult
import volty.composeapp.generated.resources.*

@Composable
actual fun HistoryExportActions(artifact: HistoryExportResult.Success) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember(artifact) { mutableStateOf(false) }
    var failed by remember(artifact) { mutableStateOf(false) }
    var saved by remember(artifact) { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<HistoryExportResult.Success?>(null) }
    val chooserTitle = stringResource(Res.string.history_export_share)
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(artifact.mime)) { uri ->
        val selected = pendingSave; pendingSave = null
        if (uri != null && selected != null) scope.launch {
            working = true; failed = false; saved = false
            try {
                withContext(Dispatchers.IO) {
                    try {
                        val file = checkedExportFile(File(context.cacheDir, "history-exports-v1"), selected)
                        file.inputStream().use { input ->
                            (context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Destination unavailable")).use { output ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                }
                                output.flush()
                            }
                        }
                    } catch (failure: Exception) {
                        // This URI was just created by this save operation. Never
                        // delete the original recording or another chosen document.
                        withContext(NonCancellable) { runCatching { context.contentResolver.delete(uri, null, null) } }
                        throw failure
                    }
                }
                saved = true
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
            finally { working = false }
        }
    }
    Column {
        FilledTonalButton(onClick = {
            scope.launch {
                working = true; failed = false; saved = false
                try {
                    val uri = withContext(Dispatchers.IO) {
                        val file = checkedExportFile(File(context.cacheDir, "history-exports-v1"), artifact)
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = artifact.mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("Volty ride", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { failed = true }
                finally { working = false }
            }
        }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(chooserTitle) }
        OutlinedButton(onClick = {
            failed = false; saved = false; pendingSave = artifact
            try { destination.launch(artifact.relativePath) }
            catch (_: Exception) { pendingSave = null; failed = true }
        }, enabled = !working && pendingSave == null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.history_export_save))
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (saved) Text(stringResource(Res.string.history_export_saved))
        if (failed) Text(stringResource(Res.string.history_export_unavailable), color = MaterialTheme.colorScheme.error)
    }
}

/** Verify the exact completed artifact again before granting access or copying. */
internal suspend fun checkedExportFile(directory: File, artifact: HistoryExportResult.Success): File {
    if (HistoryExportFormat.entries.none { artifact.relativePath.endsWith(".${it.extension}") && artifact.mime == it.mime })
        throw IOException("Unsupported export")
    if (!HistoryExportFileCache.isReadyName(artifact.relativePath) ||
        artifact.bytes !in 1..64L * 1024 * 1024) throw IOException("Invalid export")
    val root = directory.canonicalFile
    val file = File(root, artifact.relativePath).canonicalFile
    if (file.parentFile != root || !file.isFile || file.length() != artifact.bytes) throw IOException("Export no longer available")
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val size = input.read(buffer)
            if (size < 0) break
            total += size
            if (total > artifact.bytes) throw IOException("Export changed")
            digest.update(buffer, 0, size)
        }
        if (total != artifact.bytes) throw IOException("Export changed")
    }
    if (digest.digest().joinToString("") { "%02x".format(it) } != artifact.sha256) throw IOException("Export changed")
    return file
}
