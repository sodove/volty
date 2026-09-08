package ru.sodovaya.volty.presentation.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifest

class OfflineRegionDisplayNamePolicyTest {
    @Test
    fun `keeps configured human name`() {
        val region = OfflineRegionManifest(
            regionId = "g1-146-240",
            displayName = "Екатеринбург",
            bounds = OfflineRegionBounds(56.0, 60.0, 57.0, 61.0),
        )
        assertEquals("Екатеринбург", OfflineRegionDisplayNamePolicy.displayName(region))
    }

    @Test
    fun `expands legacy grid label to geographic bounds`() {
        val region = OfflineRegionManifest(
            regionId = "g1-125-358",
            displayName = "Регион 125–358",
            bounds = OfflineRegionBounds(35.0, 178.0, 36.0, 179.0),
        )
        assertEquals(
            "35–36° с.ш. · 178–179° в.д.",
            OfflineRegionDisplayNamePolicy.displayName(region),
        )
    }

    @Test
    fun `also replaces english grid label from older catalog`() {
        val region = OfflineRegionManifest(
            regionId = "g1-146-240",
            displayName = "Region 146-240",
            bounds = OfflineRegionBounds(56.0, 60.0, 57.0, 61.0),
        )
        assertEquals(
            "56–57° с.ш. · 60–61° в.д.",
            OfflineRegionDisplayNamePolicy.displayName(region),
        )
    }
}
