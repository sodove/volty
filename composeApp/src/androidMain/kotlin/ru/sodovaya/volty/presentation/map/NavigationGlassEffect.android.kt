package ru.sodovaya.volty.presentation.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalDensity
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
actual fun platformNavigationGlass(modifier: Modifier, fade: MapGlassFade): Modifier {
    val hazeState = currentMapHazeState ?: return modifier
    val fadeDepth = with(LocalDensity.current) { MapGlassFadeDepth.toPx() }
    val edgeMask = remember(fade, fadeDepth) {
        if (fade == MapGlassFade.NONE) null else mapGlassFadeMask(fade, fadeDepth)
    }
    return modifier.hazeEffect(state = hazeState) {
        blurRadius = 24.dp
        inputScale = HazeInputScale.Auto
        noiseFactor = 0f
        mask = edgeMask
    }
}

private fun mapGlassFadeMask(fade: MapGlassFade, fadeDepth: Float): ShaderBrush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val distance = fadeDepth.coerceAtMost(size.height).coerceAtLeast(1f)
        val start = if (fade == MapGlassFade.TOP) 0f else size.height
        val end = if (fade == MapGlassFade.TOP) start + distance else start - distance
        return LinearGradientShader(
            from = Offset(0f, start),
            to = Offset(0f, end),
            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.50f),
                Color.White.copy(alpha = 0.84f), Color.White),
            colorStops = listOf(0f, 0.25f, 0.50f, 0.75f, 1f),
        )
    }
}
