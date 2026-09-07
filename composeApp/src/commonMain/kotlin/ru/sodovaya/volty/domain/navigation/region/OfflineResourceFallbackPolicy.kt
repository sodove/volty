package ru.sodovaya.volty.domain.navigation.region

/** A local resource which may be resolved independently from the map pack. */
enum class OfflineResourceKind {
    MAP,
    SEARCH,
    ROUTING,
}

/** A local result which is safe to replace with a validated online request. */
enum class LocalFailure {
    None,
    Empty,
    Retryable,
    NoRoute,
}

sealed interface OfflineResourceDecision {
    data object UseLocal : OfflineResourceDecision
    data object UseOnline : OfflineResourceDecision
    data object Unavailable : OfflineResourceDecision
}

/**
 * Selects a source without performing acquisition. Downloading is owned by
 * region preparation and Settings; resource requests are deliberately pure.
 */
object OfflineResourceFallbackPolicy {
    fun decide(
        localCoverage: Boolean,
        network: OfflineNetworkAvailability,
        localFailure: LocalFailure = LocalFailure.None,
        resource: OfflineResourceKind,
    ): OfflineResourceDecision {
        // A successful local result, including a valid route miss, is final.
        if (localCoverage && localFailure != LocalFailure.Empty && localFailure != LocalFailure.Retryable) {
            return OfflineResourceDecision.UseLocal
        }
        return if (network != OfflineNetworkAvailability.OFFLINE) {
            OfflineResourceDecision.UseOnline
        } else {
            OfflineResourceDecision.Unavailable
        }
    }
}
