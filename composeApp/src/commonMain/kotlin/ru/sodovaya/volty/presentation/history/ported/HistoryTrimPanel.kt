// SPDX-License-Identifier: GPL-3.0-or-later
// Trim workflow adapted from Vescape useHistoryFavorites.ts, revision
// 2527c346bbdb25d4e018fdd68e916621e20fac27, author Kacper Kozak.
// See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import volty.composeapp.generated.resources.*

/** Only edits the reference interval. The recorder and its rows are untouched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryTrimPanel(bounds: HistoryRange, initialRange: HistoryRange, initialName: String,
    editing: Boolean, state: HistoryFavoritesState, onSave: (String, HistoryRange) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    val span = bounds.endEpochMillis.toDouble() - bounds.startEpochMillis.toDouble()
    fun fraction(epoch: Long) = ((epoch.toDouble() - bounds.startEpochMillis.toDouble()) / span).toFloat().coerceIn(0f, 1f)
    var range by remember { mutableStateOf(initialRange) }
    val selected = fraction(range.startEpochMillis)..fraction(range.endEpochMillis)
    fun epoch(fraction: Float) = (bounds.startEpochMillis.toDouble() + span * fraction).toLong()
        .coerceIn(bounds.startEpochMillis, bounds.endEpochMillis)
    val valid = name.trim().isNotEmpty() && name.trim().length <= 120 && range.endEpochMillis > range.startEpochMillis
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (editing) Res.string.history_favorite_edit else Res.string.history_favorite_add), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !state.busy,
                label = { Text(stringResource(Res.string.history_favorite_name)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            Text(stringResource(Res.string.history_favorite_reference_only), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.history_favorite_start, historyRideDate(range.startEpochMillis, true)))
            Text(stringResource(Res.string.history_favorite_end, historyRideDate(range.endEpochMillis, true)))
            Text(stringResource(Res.string.history_favorite_length,
                ((range.endEpochMillis.toDouble() - range.startEpochMillis) / 1000).toLong().toString()))
            RangeSlider(selected, { range = HistoryRange(epoch(it.start), epoch(it.endInclusive)) }, enabled = !state.busy,
                modifier = Modifier.windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal)),
                valueRange = 0f..1f)
            TextButton(onClick = { range = bounds }, enabled = !state.busy) { Text(stringResource(Res.string.history_window_reset)) }
            state.error?.let { HistoryFavoriteError(it) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = { onSave(name, range) }, enabled = valid && !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.history_favorite_save))
            }
        }
    }
}
