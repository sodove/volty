package ru.sodovaya.volty.domain.navigation.offline

import ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds
import kotlinx.coroutines.flow.StateFlow

enum class OfflineMapStyleVariant(val ofmStyleUrl: String) {
    BRIGHT("https://tiles.openfreemap.org/styles/bright"),
    DARK("https://tiles.openfreemap.org/styles/dark");

    companion object {
        fun forTheme(dark: Boolean): OfflineMapStyleVariant = if (dark) DARK else BRIGHT
    }
}

data class OfflineMapPackKey(
    val regionId: String,
    val style: OfflineMapStyleVariant,
    val ofmStyleUrl: String,
)

data class OfflineMapPackDefinition(
    val key: OfflineMapPackKey,
    val bounds: OfflineRegionBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val tileLimit: Int,
)

sealed interface OfflineMapPackState {
    data object Missing : OfflineMapPackState
    data object Preparing : OfflineMapPackState

    data class Downloading(
        val completedResources: Long,
        val completedTiles: Long,
        val totalResources: Long?,
    ) : OfflineMapPackState

    data object Ready : OfflineMapPackState
    data class Failed(val code: String) : OfflineMapPackState
}

/** The platform-independent lifecycle boundary for native map packs. */
interface OfflineMapPackManager {
    val states: StateFlow<Map<OfflineMapPackKey, OfflineMapPackState>>

    fun state(key: OfflineMapPackKey): OfflineMapPackState? = states.value[key]

    suspend fun prepare(definition: OfflineMapPackDefinition)
    suspend fun pause(key: OfflineMapPackKey)
    suspend fun resume(key: OfflineMapPackKey)
    suspend fun invalidate(key: OfflineMapPackKey)
    suspend fun delete(key: OfflineMapPackKey)
}
