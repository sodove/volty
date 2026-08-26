package ru.sodovaya.volty.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharingRulesTest {
    @Test
    fun profileAllowsOnlyItsContractedPayload() {
        assertTrue(SharingRules.allowsLocation("LOCATION"))
        assertFalse(SharingRules.allowsTelemetry("LOCATION"))
        assertTrue(SharingRules.allowsTelemetry("RIDE"))
        assertFalse(SharingRules.allowsFullMetrics("RIDE"))
        assertTrue(SharingRules.allowsFullMetrics("FULL"))
    }

    @Test
    fun expiredOrFutureLocationCannotBePublished() {
        assertTrue(SharingRules.isPublishable(startedAt = 100, expiresAt = 200, capturedAt = 150, now = 160))
        assertFalse(SharingRules.isPublishable(startedAt = 100, expiresAt = 200, capturedAt = 150, now = 200))
        assertFalse(SharingRules.isPublishable(startedAt = 100, expiresAt = 200, capturedAt = 90, now = 160))
    }

    @Test
    fun locationProfileRejectsTelemetryAndStaleLocations() {
        assertTrue(SharingRules.acceptsTelemetry("LOCATION", hasTelemetry = false))
        assertFalse(SharingRules.acceptsTelemetry("LOCATION", hasTelemetry = true))
        assertTrue(SharingRules.isLocationFresh(staleAfter = 201, now = 200))
        assertFalse(SharingRules.isLocationFresh(staleAfter = 200, now = 200))
    }

    @Test
    fun ttlValidationIsFiniteAndBoundedForStartAndRenew() {
        assertTrue(SharingRules.isTtlValid(1, 86_400_000))
        assertFalse(SharingRules.isTtlValid(0, 86_400_000))
        assertFalse(SharingRules.isTtlValid(86_400_001, 86_400_000))
    }

    @Test
    fun websocketEventsUseExplicitRevokeAndExpiryKinds() {
        assertEquals("share_revoked", LiveEventKind.REVOKED.wireName)
        assertEquals("share_expired", LiveEventKind.EXPIRED.wireName)
        assertEquals("subscription_terminated", LiveEventKind.TERMINATED.wireName)
    }

    @Test
    fun livePresenceUsesTheNamedFifteenSecondFreshnessPolicy() {
        assertEquals("STALE", SharingRules.presence(hasActiveShare = true, lastSeenAt = 1000L, now = 16_000L))
        assertEquals("ONLINE", SharingRules.presence(hasActiveShare = true, lastSeenAt = 1001L, now = 16_000L))
    }
}
