package ru.sodovaya.volty.presentation.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Paints only a fully decoded background; the caller keeps the Canvas fallback behind it. */
@Composable
expect fun PlatformHistoryPreviewImage(image: HistoryPreviewImage, modifier: Modifier = Modifier)
