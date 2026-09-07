package ru.sodovaya.volty.data.navigation.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.navigation.region.OfflineNetworkAvailability

class AndroidOfflineNetworkStatusTest {
    @Test
    fun internet_without_validation_is_offline() {
        assertEquals(
            OfflineNetworkAvailability.OFFLINE,
            offlineNetworkAvailability(
                hasInternet = true,
                isValidated = false,
                isMetered = false,
            ),
        )
    }

    @Test
    fun validated_internet_preserves_metered_classification() {
        assertEquals(
            OfflineNetworkAvailability.METERED,
            offlineNetworkAvailability(
                hasInternet = true,
                isValidated = true,
                isMetered = true,
            ),
        )
    }
}
