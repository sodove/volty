package ru.sodovaya.volty.presentation.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun rememberHistoryMediaPicker(owner: HistoryComponent, onUnavailable: () -> Unit): () -> Unit

@Composable
expect fun HistoryMediaThumbnail(uri: String?, mime: String, description: String, modifier: Modifier = Modifier)

@Composable
expect fun HistoryMediaViewer(open: HistoryMediaOpen, onDismiss: () -> Unit)
