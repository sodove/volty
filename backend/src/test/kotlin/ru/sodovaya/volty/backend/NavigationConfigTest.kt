package ru.sodovaya.volty.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationConfigTest {
    @Test
    fun disabled_navigation_does_not_require_a_provider_key() {
        val config = AppConfig.fromEnvironment(baseEnvironment())

        assertEquals("disabled", config.navigationProvider)
        assertFalse(config.navigationEnabled)
        assertEquals(null, config.graphHopperApiKey)
    }

    @Test
    fun graphhopper_requires_key_and_one_generic_profile_mapping() {
        val missingKey = assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(baseEnvironment() + mapOf("VOLTY_NAV_PROVIDER" to "graphhopper"))
        }
        assertTrue("GRAPHHOPPER_API_KEY" in (missingKey.message ?: ""))
        assertFalse("secret" in (missingKey.message ?: ""))

        val missingMapping = assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(
                baseEnvironment() + mapOf(
                    "VOLTY_NAV_PROVIDER" to "graphhopper",
                    "GRAPHHOPPER_API_KEY" to "do-not-log-this-key",
                ),
            )
        }
        assertTrue("VOLTY_NAV_PROFILE" in (missingMapping.message ?: ""))
        assertFalse("do-not-log-this-key" in (missingMapping.message ?: ""))
    }

    @Test
    fun configured_graphhopper_profile_is_normalized_without_exposing_secrets() {
        val config = AppConfig.fromEnvironment(
            baseEnvironment() + mapOf(
                "VOLTY_NAV_PROVIDER" to "GRAPHhopper",
                "GRAPHHOPPER_API_KEY" to "do-not-log-this-key",
                "VOLTY_NAV_PROFILE" to "personal-mobility",
            ),
        )

        assertEquals("graphhopper", config.navigationProvider)
        assertTrue(config.navigationEnabled)
        assertEquals("personal-mobility", config.navigationProfileId)
        assertFalse(config.toString().contains("do-not-log-this-key"))
    }

    private fun baseEnvironment() = mapOf(
        "VOLTY_JWT_SECRET" to "test-secret-that-is-long-enough-for-hmac",
        "VOLTY_NAV_PROVIDER" to "disabled",
    )
}
