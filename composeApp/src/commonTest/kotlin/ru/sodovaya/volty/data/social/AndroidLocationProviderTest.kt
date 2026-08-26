package ru.sodovaya.volty.data.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidLocationProviderTest {
    @Test
    fun replayedFixFromBeforeRestartIsRejectedByGenerationTimestamp() {
        assertEquals(false, isLocationFromCurrentGeneration(99L, 100L))
        assertEquals(true, isLocationFromCurrentGeneration(100L, 100L))
        assertEquals(true, isLocationFromCurrentGeneration(101L, 100L))
    }

    @Test
    fun providerRegistrationRollsBackOnlyProvidersRegisteredBeforeFailure() {
        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            registerProvidersTransactional(
                providers = listOf("gps", "network", "passive"),
                register = { provider ->
                    if (provider == "passive") throw IllegalStateException("registration failed")
                    registered += provider
                },
                unregister = { provider -> unregistered += provider },
            )
        }

        assertEquals(listOf("gps", "network"), registered)
        assertEquals(listOf("network", "gps"), unregistered)
    }
}
