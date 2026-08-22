package ru.sodovaya.volty.domain.social

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialSessionPolicyTest {
    @Test
    fun loggedOutSessionRequiresAuthenticationForSocial() {
        assertTrue(SocialSessionPolicy.requiresAuthentication(SocialSession.LoggedOut))
    }

    @Test
    fun authenticatingSessionStillBlocksSocialActions() {
        assertTrue(SocialSessionPolicy.requiresAuthentication(SocialSession.Authenticating))
    }

    @Test
    fun authenticatedSessionCanUseSocial() {
        val session = SocialSession.Authenticated(
            userId = SocialUserId("user-1"),
            displayName = "Rider",
            tokenState = SessionTokenState.ACTIVE,
            emailVerified = true,
        )

        assertFalse(SocialSessionPolicy.requiresAuthentication(session))
    }

    @Test
    fun expiredOrRevokedTokenDoesNotSatisfySocialGate() {
        val expired = SocialSession.Authenticated(
            userId = SocialUserId("user-1"),
            displayName = "Rider",
            tokenState = SessionTokenState.EXPIRED,
        )
        val revoked = expired.copy(tokenState = SessionTokenState.REVOKED)

        assertTrue(SocialSessionPolicy.requiresAuthentication(expired))
        assertTrue(SocialSessionPolicy.requiresAuthentication(revoked))
    }

    @Test
    fun activeUnverifiedSessionIsDistinctFromLoggedOutAndCanUseSocial() {
        val pending = SocialSession.Authenticated(
            userId = SocialUserId("user-1"),
            displayName = "Rider",
            tokenState = SessionTokenState.ACTIVE,
            emailVerified = false,
        )

        assertFalse(SocialSessionPolicy.requiresAuthentication(pending))
        assertFalse(SocialSessionPolicy.requiresEmailVerification(pending))
    }
}
