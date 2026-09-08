package ru.sodovaya.volty.presentation.settings

import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageState
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageStatus

object OfflineRegionListPolicy {
    private val activeStatuses = setOf(
        OfflineRegionPackageStatus.READY,
        OfflineRegionPackageStatus.UPDATE_AVAILABLE,
        OfflineRegionPackageStatus.PREPARING,
        OfflineRegionPackageStatus.QUEUED,
        OfflineRegionPackageStatus.WAITING_FOR_NETWORK,
        OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL,
        OfflineRegionPackageStatus.DOWNLOADING,
        OfflineRegionPackageStatus.PAUSED,
        OfflineRegionPackageStatus.VERIFYING,
        OfflineRegionPackageStatus.INSTALLING,
    )

    fun filterAndOrder(
        states: List<OfflineRegionPackageState>,
        query: String,
        maxSuggestions: Int = 8,
    ): List<OfflineRegionPackageState> {
        val normalized = query.trim().lowercase()
        val filtered = if (normalized.isBlank()) states else states.filter { state ->
            state.region.displayName.lowercase().contains(normalized) ||
                state.region.regionId.lowercase().contains(normalized)
        }
        val ordered = filtered.sortedWith(
            compareByDescending<OfflineRegionPackageState> { it.status in activeStatuses }
                .thenBy { it.region.displayName.lowercase() }
                .thenBy { it.region.regionId },
        )
        return if (normalized.isBlank()) {
            val active = ordered.filter { it.status in activeStatuses }
            val suggestions = ordered
                .filterNot { it.status in activeStatuses }
                .distinctBy { it.region.displayName.trim().lowercase() }
                .take(maxSuggestions)
            active + suggestions
        } else {
            ordered.take(maxSuggestions)
        }
    }
}
