package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineResourceFallbackPolicyTest {
    @Test
    fun local_coverage_always_wins_for_successful_resources() {
        OfflineResourceKind.entries.forEach { resource ->
            assertEquals(
                OfflineResourceDecision.UseLocal,
                OfflineResourceFallbackPolicy.decide(
                    localCoverage = true,
                    network = OfflineNetworkAvailability.UNMETERED,
                    resource = resource,
                ),
            )
        }
    }

    @Test
    fun missing_coverage_uses_online_on_validated_network() {
        assertEquals(
            OfflineResourceDecision.UseOnline,
            OfflineResourceFallbackPolicy.decide(
                localCoverage = false,
                network = OfflineNetworkAvailability.UNMETERED,
                resource = OfflineResourceKind.SEARCH,
            ),
        )
        assertEquals(
            OfflineResourceDecision.UseOnline,
            OfflineResourceFallbackPolicy.decide(
                localCoverage = false,
                network = OfflineNetworkAvailability.METERED,
                resource = OfflineResourceKind.ROUTING,
            ),
        )
    }

    @Test
    fun missing_coverage_is_unavailable_without_network() {
        assertEquals(
            OfflineResourceDecision.Unavailable,
            OfflineResourceFallbackPolicy.decide(
                localCoverage = false,
                network = OfflineNetworkAvailability.OFFLINE,
                resource = OfflineResourceKind.MAP,
            ),
        )
    }

    @Test
    fun retryable_local_failure_uses_online_but_no_route_stays_local() {
        assertEquals(
            OfflineResourceDecision.UseOnline,
            OfflineResourceFallbackPolicy.decide(
                localCoverage = true,
                network = OfflineNetworkAvailability.UNMETERED,
                localFailure = LocalFailure.Retryable,
                resource = OfflineResourceKind.ROUTING,
            ),
        )
        assertEquals(
            OfflineResourceDecision.UseLocal,
            OfflineResourceFallbackPolicy.decide(
                localCoverage = true,
                network = OfflineNetworkAvailability.UNMETERED,
                localFailure = LocalFailure.NoRoute,
                resource = OfflineResourceKind.ROUTING,
            ),
        )
    }

    @Test
    fun empty_local_search_is_only_online_when_network_exists() {
        assertEquals(
            OfflineResourceDecision.UseOnline,
            OfflineResourceFallbackPolicy.decide(
                localCoverage = true,
                network = OfflineNetworkAvailability.METERED,
                localFailure = LocalFailure.Empty,
                resource = OfflineResourceKind.SEARCH,
            ),
        )
        assertEquals(
            OfflineResourceDecision.Unavailable,
            OfflineResourceFallbackPolicy.decide(
                localCoverage = true,
                network = OfflineNetworkAvailability.OFFLINE,
                localFailure = LocalFailure.Empty,
                resource = OfflineResourceKind.SEARCH,
            ),
        )
    }
}
