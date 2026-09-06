package ru.sodovaya.volty.presentation.map

/** Shared invariants for the deliberately small offline vector-map style. */
internal object OfflineMapStylePolicy {
    const val maxBuildingExtrusionHeightMeters: Double = 60.0

    val roadClasses: List<String> = listOf(
        "motorway",
        "trunk",
        "primary",
        "secondary",
        "tertiary",
        "minor",
        "service",
        "living_street",
        "track",
        "path",
        "footway",
        "cycleway",
        "pedestrian",
        "steps",
    )

    fun roadClassFilterJson(classes: List<String>): String =
        "[\"in\",[\"get\",\"class\"],[\"literal\",[${classes.joinToString(",") { "\"$it\"" }}]]]"

    fun buildingAnchor(existingLayerIds: List<String>): String? = listOf(
        "roads-major-casing",
        "roads-major",
        "road_area_pier",
        "road_pier",
        "highway_path",
        "highway_minor",
        "roads",
    ).firstOrNull(existingLayerIds::contains)
}
