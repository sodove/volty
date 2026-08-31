package ru.sodovaya.volty.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Applies the native map snapshot blur only to the glass panel itself. */
@Composable
expect fun platformNavigationGlass(modifier: Modifier = Modifier): Modifier
