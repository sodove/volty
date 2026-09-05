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
        assertFalse(config.toString().contains("external"))
    }

    @Test
    fun unsupported_provider_is_rejected_until_a_self_hosted_provider_is_installed() {
        val error = assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(baseEnvironment() + mapOf("VOLTY_NAV_PROVIDER" to "third-party"))
        }

        assertTrue("disabled" in (error.message ?: ""))
        assertFalse("third-party" in (error.message ?: ""))
    }

    private fun baseEnvironment() = mapOf(
        "VOLTY_JWT_SECRET" to "test-secret-that-is-long-enough-for-hmac",
        "VOLTY_NAV_PROVIDER" to "disabled",
    )
}
