package ru.sodovaya.volty.presentation.map

/** Native MapLibre packs cache these same URLs; location and connectivity never select a style. */
object MapRenderSourcePolicy {
    fun styleUrl(darkTheme: Boolean): String =
        if (darkTheme) "https://tiles.openfreemap.org/styles/dark"
        else "https://tiles.openfreemap.org/styles/bright"
}

internal data class LegacyBuildingLayerDefinition(
    val id: String,
    val sourceId: String,
    val sourceLayer: String,
    val heightProperty: String,
    val baseProperty: String,
    val color: String,
    val opacity: Float,
    val minZoom: Float,
)

internal object LegacyBuildingLayerPolicy {
    private const val LAYER_ID = "volty-buildings-3d"

    /** The renderer supplies an append operation, so all original OFM layers keep their order. */
    fun appendIfMissing(
        existingLayerIds: List<String>,
        darkTheme: Boolean,
        appendLayer: (LegacyBuildingLayerDefinition) -> Unit,
    ) {
        if (LAYER_ID in existingLayerIds) return
        appendLayer(
            LegacyBuildingLayerDefinition(
                id = LAYER_ID,
                sourceId = "openmaptiles",
                sourceLayer = "building",
                heightProperty = "render_height",
                baseProperty = "render_min_height",
                color = if (darkTheme) "#31424B" else "#D5DCE0",
                opacity = 0.82f,
                minZoom = 13f,
            ),
        )
    }
}
