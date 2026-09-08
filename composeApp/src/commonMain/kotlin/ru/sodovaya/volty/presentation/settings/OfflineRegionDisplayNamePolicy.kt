package ru.sodovaya.volty.presentation.settings

import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifest

/** Uses the server's automatically generated region label without allowing user naming. */
internal object OfflineRegionDisplayNamePolicy {
    fun displayName(region: OfflineRegionManifest): String {
        return region.displayName.trim()
    }
}
