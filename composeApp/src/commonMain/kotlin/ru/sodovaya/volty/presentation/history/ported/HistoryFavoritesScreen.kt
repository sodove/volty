// SPDX-License-Identifier: GPL-3.0-or-later
// Favorites/list workflow adapted from Vescape HistorySessionSheet.tsx and
// useHistoryFavorites.ts, revision 2527c346bbdb25d4e018fdd68e916621e20fac27.
// Original author Kacper Kozak. See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import volty.composeapp.generated.resources.*

@Composable
fun HistoryFavoritesScreen(state: HistoryFavoritesState, selected: String?, onOpen: (HistoryFavorite) -> Unit,
    onRefresh: () -> Unit, onOlder: () -> Unit, onDelete: (HistoryFavorite) -> Unit, modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()) {
    var deleting by remember { mutableStateOf<HistoryFavorite?>(null) }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.history_favorites), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(Res.string.history_refresh)) }
        } }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.error != null) item { HistoryFavoriteError(state.error) }
        if (state.loaded && state.items.isEmpty()) item { Text(stringResource(Res.string.history_favorites_empty)) }
        items(state.items, key = { it.id }) { favorite ->
            Surface(onClick = { onOpen(favorite) }, shape = MaterialTheme.shapes.medium,
                color = if (favorite.id == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(favorite.name, style = MaterialTheme.typography.titleMedium)
                        Text(historyRideDate(favorite.range.startEpochMillis), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(Res.string.history_favorite_length,
                            ((favorite.range.endEpochMillis.toDouble() - favorite.range.startEpochMillis) / 1000).toLong().toString()),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { deleting = favorite }, enabled = !state.busy) {
                        Icon(Icons.Default.DeleteOutline, stringResource(Res.string.history_favorite_delete))
                    }
                }
            }
        }
        if (state.next != null) item { TextButton(onClick = onOlder, enabled = !state.busy) { Text(stringResource(Res.string.history_load_older)) } }
    }
    deleting?.let { favorite -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(stringResource(Res.string.history_favorite_delete)) },
        text = { Text(stringResource(Res.string.history_favorite_delete_explanation, favorite.name)) },
        confirmButton = { TextButton(onClick = { deleting = null; onDelete(favorite) }) { Text(stringResource(Res.string.history_favorite_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.history_favorite_cancel)) } }) }
}

@Composable
internal fun HistoryFavoriteError(error: HistoryFavoritesError) {
    Text(stringResource(when (error) {
        HistoryFavoritesError.INVALID_NAME -> Res.string.history_favorite_invalid_name
        HistoryFavoritesError.INVALID_RANGE -> Res.string.history_favorite_invalid_range
        HistoryFavoritesError.SOURCE_UNAVAILABLE, HistoryFavoritesError.SOURCE_CHANGED -> Res.string.history_favorite_source_missing
        HistoryFavoritesError.CONFLICT -> Res.string.history_favorite_conflict
        HistoryFavoritesError.STORAGE -> Res.string.history_favorite_storage_error
    }), color = MaterialTheme.colorScheme.error)
}
