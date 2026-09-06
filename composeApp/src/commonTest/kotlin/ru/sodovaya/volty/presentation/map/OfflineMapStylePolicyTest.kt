package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineMapStylePolicyTest {
    @Test
    fun `building extrusion has a sane visual ceiling`() {
        assertEquals(60.0, OfflineMapStylePolicy.maxBuildingExtrusionHeightMeters)
    }

    @Test
    fun `offline map keeps all road classes emitted by the tile compiler`() {
        assertEquals(
            setOf(
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
            ),
            OfflineMapStylePolicy.roadClasses.toSet(),
        )
    }

    @Test
    fun `building layer is inserted below roads and labels`() {
        assertEquals(
            "roads-major-casing",
            OfflineMapStylePolicy.buildingAnchor(
                listOf("background", "buildings", "roads-major-casing", "roads-major", "road-labels"),
            ),
        )
        assertEquals(
            "highway_minor",
            OfflineMapStylePolicy.buildingAnchor(
                listOf("background", "building", "highway_minor", "highway_major_inner"),
            ),
        )
    }

    @Test
    fun `road class filter uses a literal set accepted by maplibre`() {
        assertEquals(
            "[\"in\",[\"get\",\"class\"],[\"literal\",[\"motorway\",\"trunk\"]]]",
            OfflineMapStylePolicy.roadClassFilterJson(listOf("motorway", "trunk")),
        )
    }

    @Test
    fun `offline style keeps the visual road hierarchy from the online map`() {
        assertEquals(
            listOf(
                "road_area_pier",
                "road_pier",
                "highway_path",
                "highway_minor",
                "highway_major_casing",
                "highway_major_inner",
                "highway_motorway_casing",
                "highway_motorway_inner",
            ),
            OfflineMapStylePolicy.roadLayerIds,
        )
    }
}
