package ru.sodovaya.volty.data.social

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
    suspend fun clear()
}
