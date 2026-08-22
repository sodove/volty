package ru.sodovaya.volty.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityRulesTest {
    @Test
    fun emailAndPasswordValidationIsStrictAndNormalizesEmail() {
        assertEquals("rider@example.com", Validation.email(" Rider@Example.com ").getOrThrow())
        assertTrue(Validation.password("correct horse battery staple").isSuccess)
        assertFalse(Validation.email("not-an-email").isSuccess)
        assertFalse(Validation.password("short").isSuccess)
    }

    @Test
    fun refreshTokensAreOneWayAndRotationRejectsReuse() {
        val service = RefreshTokenService(secret = ByteArray(32) { 7 }, accessTtlSeconds = 60, refreshTtlSeconds = 3600)
        val first = service.issueRefreshToken("user-1", nowEpochSeconds = 100)
        assertFalse(first.raw == first.hash)
        assertTrue(service.verifyHash(first.raw, first.hash))
        val rotated = service.rotate(first, nowEpochSeconds = 101)
        assertTrue(rotated.isSuccess)
        assertFalse(service.rotate(first, nowEpochSeconds = 102).isSuccess)
    }

    @Test
    fun rateLimiterReturnsRetryAfterAfterBucketIsExhausted() {
        val limiter = RateLimiter(maxRequests = 2, windowSeconds = 60)
        assertTrue(limiter.allow("ip", nowEpochSeconds = 10))
        assertTrue(limiter.allow("ip", nowEpochSeconds = 10))
        assertFalse(limiter.allow("ip", nowEpochSeconds = 10))
        assertEquals(60L, limiter.retryAfterSeconds("ip", nowEpochSeconds = 10))
        assertTrue(limiter.allow("ip", nowEpochSeconds = 70))
    }
}
