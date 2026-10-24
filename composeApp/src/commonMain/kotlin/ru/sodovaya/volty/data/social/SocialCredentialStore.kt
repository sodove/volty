package ru.sodovaya.volty.data.social

import ru.sodovaya.volty.domain.social.SocialSession

/**
 * The social session is deliberately separate from vehicle persistence.
 * Implementations must protect tokens with the platform secure-storage
 * facility; email/password is never a client-side credential payload here.
 */
data class SocialCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
)

interface SocialCredentialStore {
    suspend fun read(): SocialCredentials?
    suspend fun write(credentials: SocialCredentials)
    /** Forgets the tokens and the remembered profile together. */
    suspend fun clear()

    /**
     * The profile the server last confirmed for these tokens. A start with no internet opens the
     * account from it instead of looking signed out; only a server rejection signs out. A store
     * that cannot remember one reports none, and an offline start then stays signed out.
     */
    suspend fun readProfile(): SocialSession.Authenticated? = null
    suspend fun writeProfile(profile: SocialSession.Authenticated) {}
}
