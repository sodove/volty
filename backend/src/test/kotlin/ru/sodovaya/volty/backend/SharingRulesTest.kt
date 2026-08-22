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
    fun websocketEventsUseExplicitRevokeAndExpiryKinds() {
        assertEquals("share_revoked", LiveEventKind.REVOKED.wireName)
        assertEquals("share_expired", LiveEventKind.EXPIRED.wireName)
    }
}
