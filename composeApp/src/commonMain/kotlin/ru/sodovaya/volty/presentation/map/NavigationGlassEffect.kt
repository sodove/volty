package ru.sodovaya.volty.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class MapGlassFade { NONE, TOP, BOTTOM }

internal val MapGlassFadeDepth = 56.dp

/** Applies the native map snapshot blur only to the glass panel itself. */
@Composable
expect fun platformNavigationGlass(modifier: Modifier = Modifier, fade: MapGlassFade = MapGlassFade.NONE): Modifier
