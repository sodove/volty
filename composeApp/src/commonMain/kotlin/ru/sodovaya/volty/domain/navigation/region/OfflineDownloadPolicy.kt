package ru.sodovaya.volty.domain.navigation.region

enum class OfflineNetworkAvailability {
    OFFLINE,
    UNMETERED,
    METERED,
}

enum class OfflineRegionDownloadTrigger {
    SEARCH,
    ROUTE,
    MAP,
    SETTINGS,
}

data class OfflineDownloadPreferences(
    /** The product setting behind «Не спрашивать при загрузке через мобильную сеть». */
    val skipMeteredConfirmation: Boolean = false,
)

enum class OfflineDownloadBlockReason {
    NO_NETWORK,
}

sealed interface OfflineDownloadDecision {
    data object Allowed : OfflineDownloadDecision

    data object RequiresMeteredConfirmation : OfflineDownloadDecision

    data class Blocked(val reason: OfflineDownloadBlockReason) : OfflineDownloadDecision
}

/**
 * Decides whether a missing region may be fetched. The trigger is part of the contract so
 * automatic requests from search, route planning, and map rendering remain distinguishable
 * from an explicit Settings download without leaking that distinction into the UI layer.
 */
object OfflineRegionDownloadPolicy {
    fun decide(
        network: OfflineNetworkAvailability,
        trigger: OfflineRegionDownloadTrigger,
        preferences: OfflineDownloadPreferences,
        meteredConfirmed: Boolean = false,
    ): OfflineDownloadDecision = when (network) {
        OfflineNetworkAvailability.OFFLINE ->
            OfflineDownloadDecision.Blocked(OfflineDownloadBlockReason.NO_NETWORK)

        OfflineNetworkAvailability.UNMETERED -> OfflineDownloadDecision.Allowed

        OfflineNetworkAvailability.METERED -> if (
            preferences.skipMeteredConfirmation || meteredConfirmed
        ) {
            OfflineDownloadDecision.Allowed
        } else {
            OfflineDownloadDecision.RequiresMeteredConfirmation
        }
    }
}
