package ru.sodovaya.volty.data.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.region.OfflineNetworkAvailability
import ru.sodovaya.volty.domain.navigation.region.OfflineNetworkStatus

class AndroidHybridNavigationRepositoryTest {
    @Test
    fun missing_local_coverage_does_not_enter_online_adapter_offline() = kotlinx.coroutines.test.runTest {
        var onlineCalls = 0

        val result = onlineOrOfflineNavigation(
            network = OfflineNetworkStatus { OfflineNetworkAvailability.OFFLINE },
        ) {
            onlineCalls += 1
            NavigationResult.Success(Unit)
        }

        assertEquals(NavigationFailure.Offline, assertIs<NavigationResult.Failure>(result).reason)
        assertEquals(0, onlineCalls)
    }

    @Test
    fun retryable_local_failure_does_not_enter_online_adapter_offline() = kotlinx.coroutines.test.runTest {
        var onlineCalls = 0

        val result = onlineOrOfflineNavigation(
            network = OfflineNetworkStatus { OfflineNetworkAvailability.OFFLINE },
        ) {
            onlineCalls += 1
            NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        }

        assertEquals(NavigationFailure.Offline, assertIs<NavigationResult.Failure>(result).reason)
        assertEquals(0, onlineCalls)
    }
}
