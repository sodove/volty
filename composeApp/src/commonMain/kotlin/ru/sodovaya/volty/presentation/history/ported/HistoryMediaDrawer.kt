// SPDX-License-Identifier: GPL-3.0-or-later
// Gallery workflow/grid adapted from Vescape HistoryRideMediaDrawer.tsx and
// MediaHistoryGallery.tsx, revision 2527c346bbdb25d4e018fdd68e916621e20fac27.
// Original author Kacper Kozak. See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import volty.composeapp.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistoryMediaDrawer(owner: HistoryComponent, state: HistoryMediaState, positionedIds: Set<String>, onDismiss: () -> Unit) {
    var deleting by remember(state.favoriteId) { mutableStateOf<HistoryMediaRecord?>(null) }
    var pickerUnavailable by remember(state.favoriteId) { mutableStateOf(false) }
    val pick = rememberHistoryMediaPicker(owner) { pickerUnavailable = true }
    val tileWidth = 104.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    LaunchedEffect(state.favoriteId, state.loaded, state.busy) {
        if (state.favoriteId != null && !state.loaded && !state.busy && state.error == null) owner.loadMedia()
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding()) {
        LazyVerticalGrid(GridCells.Adaptive(tileWidth), Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(Res.string.history_media_title), style = MaterialTheme.typography.headlineSmall)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(Res.string.history_media_limits), style = MaterialTheme.typography.bodySmall)
            }
            if (state.busy) item(span = { GridItemSpan(maxLineSpan) }) { Column {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = owner::cancelMediaImport) { Text(stringResource(Res.string.history_favorite_cancel)) }
            } }
            if (state.error != null || pickerUnavailable) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(if (pickerUnavailable) Res.string.history_media_picker_unavailable else when (state.error) {
                    HistoryMediaFailure.LIMIT -> Res.string.history_media_limit
                    HistoryMediaFailure.ACCESS_DENIED -> Res.string.history_media_access
                    HistoryMediaFailure.INVALID_MEDIA -> Res.string.history_media_invalid
                    HistoryMediaFailure.FAVORITE_UNAVAILABLE -> Res.string.history_media_favorite_unavailable
                    else -> Res.string.history_media_storage
                }), color = MaterialTheme.colorScheme.error)
            }
            if (state.recovery.removedItems > 0 || state.recovery.pendingCleanup > 0) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(Res.string.history_media_recovery, state.recovery.removedItems.toString(),
                    state.recovery.pendingCleanup.toString()), style = MaterialTheme.typography.bodySmall)
            }
            if (state.loaded && state.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(Res.string.history_media_empty))
            }
            items(state.items, key = { it.id }) { item -> Column {
                Surface(onClick = { owner.openMedia(item) }, enabled = !state.busy,
                    shape = MaterialTheme.shapes.small) {
                    HistoryMediaThumbnail(state.previewUris[item.id], item.mime,
                        stringResource(Res.string.history_media_open), Modifier.fillMaxWidth().aspectRatio(1f))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (item.capturedEpochMillis == null) stringResource(Res.string.history_media_time_unknown)
                            else historyRideDate(item.capturedEpochMillis, true), style = MaterialTheme.typography.labelSmall)
                        Text(stringResource(if (item.id in positionedIds) Res.string.history_media_on_map else Res.string.history_media_unpositioned),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { deleting = item }, enabled = !state.busy) {
                        Icon(Icons.Default.DeleteOutline, stringResource(Res.string.history_media_delete))
                    }
                }
            } }
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickerUnavailable = false; pick() }, enabled = !state.busy && state.picker == null) {
                    Text(stringResource(Res.string.history_media_add))
                }
                TextButton(onClick = owner::loadMedia, enabled = !state.busy && state.picker == null) {
                    Text(stringResource(Res.string.history_refresh))
                }
            }
        }
    }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(stringResource(Res.string.history_media_delete)) },
        text = { Text(stringResource(Res.string.history_media_delete_explanation)) },
        confirmButton = { TextButton(onClick = { deleting = null; owner.deleteMedia(item) }) { Text(stringResource(Res.string.history_media_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.history_favorite_cancel)) } }) }
}
