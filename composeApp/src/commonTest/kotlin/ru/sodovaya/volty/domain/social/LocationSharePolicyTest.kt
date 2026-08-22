package ru.sodovaya.volty.domain.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocationSharePolicyTest {
    @Test
    fun loggedOutUserCannotStartGroupScopedSharing() {
        val result = LocationSharePolicy.start(
            session = SocialSession.LoggedOut,
            audienceGroupId = RideGroupId("group-1"),
            nowEpochMillis = 1_000L,
            ttlMillis = 60_000L,
        )

        assertEquals(LocationShareStartResult.NotAuthenticated, result)
    }

    @Test
    fun sharingRequiresFinitePositiveTtlWithinConfiguredLimit() {
        val session = authenticatedSession()
        val group = RideGroupId("group-1")

        assertEquals(
            LocationShareStartResult.InvalidTtl,
            LocationSharePolicy.start(session, group, 1_000L, 0L),
        )
        assertEquals(
            LocationShareStartResult.InvalidTtl,
            LocationSharePolicy.start(
                session,
                group,
                1_000L,
                LocationSharePolicy.maxTtlMillis + 1L,
            ),
        )
        assertEquals(
            LocationShareStartResult.InvalidTtl,
            LocationSharePolicy.start(
                session,
                group,
                Long.MAX_VALUE,
                1L,
            ),
        )
    }

    @Test
    fun activeWindowExpiresAndDoesNotKeepPublishing() {
        val result = LocationSharePolicy.start(
            session = authenticatedSession(),
            audienceGroupId = RideGroupId("group-1"),
            nowEpochMillis = 1_000L,
            ttlMillis = 60_000L,
        )
        val window = assertNotNull((result as LocationShareStartResult.Started).window)
        val location = LocationSnapshot(
            latitude = 56.8389,
            longitude = 60.6057,
            accuracyMeters = 8.0,
            capturedAtEpochMillis = 30_000L,
            staleAfterEpochMillis = 61_000L,
        )

        assertEquals(LocationShareStatus.ACTIVE, LocationSharePolicy.status(window, 60_999L))
        assertTrue(LocationSharePolicy.shouldPublish(window, location, 60_999L))
        assertEquals(LocationShareStatus.EXPIRED, LocationSharePolicy.status(window, 61_000L))
        assertFalse(LocationSharePolicy.shouldPublish(window, location, 61_000L))
    }

    @Test
    fun locationSnapshotBecomesStaleWithoutBeingDeleted() {
        val location = LocationSnapshot(
            latitude = 56.8389,
            longitude = 60.6057,
            accuracyMeters = 8.0,
            capturedAtEpochMillis = 10_000L,
            staleAfterEpochMillis = 20_000L,
        )

        assertEquals(LocationSnapshotStatus.STALE, LocationSharePolicy.snapshotStatus(location, 20_000L))
        assertEquals(LocationSnapshotStatus.STALE, LocationSharePolicy.snapshotStatus(location, 20_001L))
    }

    @Test
    fun revocationIsAnExplicitTerminalState() {
        assertEquals(
            LocationShareStatus.REVOKED,
            LocationSharePolicy.status(
                window = LocationShareWindow(
                    audienceGroupId = RideGroupId("group-1"),
                    startedAtEpochMillis = 1_000L,
                    expiresAtEpochMillis = 61_000L,
                ),
                nowEpochMillis = 2_000L,
                revoked = true,
            ),
        )
    }

    private fun authenticatedSession() = SocialSession.Authenticated(
        userId = SocialUserId("user-1"),
        displayName = "Rider",
        tokenState = SessionTokenState.ACTIVE,
    )
}
