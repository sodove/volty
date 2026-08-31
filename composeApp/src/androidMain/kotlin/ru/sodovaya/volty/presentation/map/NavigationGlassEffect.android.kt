package ru.sodovaya.volty.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

private var currentMapHazeState: HazeState? by mutableStateOf(null)

/** The map and overlay live in sibling composition branches, so the live Haze source is shared here. */
internal fun publishMapHazeState(state: HazeState?) {
    currentMapHazeState = state
}

@Composable
actual fun platformNavigationGlass(modifier: Modifier): Modifier {
    val hazeState = currentMapHazeState ?: return modifier
    return modifier.hazeEffect(state = hazeState) {
        blurRadius = 24.dp
        inputScale = HazeInputScale.Auto
        noiseFactor = 0f
    }
}
