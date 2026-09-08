package ru.sodovaya.volty.presentation.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifest
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageState
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageStatus

class OfflineRegionListPolicyTest {
    @Test
    fun `empty query keeps installed entries and limits suggestions`() {
        val states = (0 until 12).map { index -> state("region-$index", OfflineRegionPackageStatus.NOT_INSTALLED) } +
            state("ekb", OfflineRegionPackageStatus.READY)

        val result = OfflineRegionListPolicy.filterAndOrder(states, "", maxSuggestions = 8)

        assertEquals("ekb", result.first().region.regionId)
        assertEquals(9, result.size)
    }

    @Test
    fun `query matches display name and region id`() {
        val states = listOf(state("g1-ekb", OfflineRegionPackageStatus.NOT_INSTALLED, "Екатеринбург"))

        assertEquals(1, OfflineRegionListPolicy.filterAndOrder(states, "екат").size)
        assertEquals(1, OfflineRegionListPolicy.filterAndOrder(states, "g1-ekb").size)
    }

    @Test
    fun `empty query collapses duplicate automatic region names`() {
        val states = listOf(
            state("g1-125-358", OfflineRegionPackageStatus.NOT_INSTALLED, "Россия"),
            state("g1-125-359", OfflineRegionPackageStatus.NOT_INSTALLED, "Россия"),
            state("g1-146-240", OfflineRegionPackageStatus.NOT_INSTALLED, "Екатеринбург"),
        )

        val result = OfflineRegionListPolicy.filterAndOrder(states, "", maxSuggestions = 8)

        assertEquals(listOf("g1-146-240", "g1-125-358"), result.map { it.region.regionId })
    }

    private fun state(id: String, status: OfflineRegionPackageStatus, name: String = id) = OfflineRegionPackageState(
        region = OfflineRegionManifest(id, name, OfflineRegionBounds(56.0, 60.0, 57.0, 61.0)),
        latestRelease = null,
        status = status,
        onDemand = true,
    )
}
