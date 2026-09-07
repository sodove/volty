package ru.sodovaya.volty.domain.navigation.offline

import ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds

enum class OfflineMapStyleVariant {
    BRIGHT,
    DARK,
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
