package ru.sodovaya.volty.data.social

import kotlinx.coroutines.Job
import ru.sodovaya.volty.domain.social.SocialUserId

/** Created only after a verified profile and its exact credentials are committed. */
class SocialAuthenticatedAccount internal constructor(
    val userId: SocialUserId,
    val job: Job,
    private val tokenSource: suspend () -> String?,
) {
    /** Source retains its original authentication generation, including during refresh. */
    internal suspend fun bearer(): String? {
        if (!job.isActive) return null
        val token = tokenSource()
        return if (job.isActive) token else null
    }

    override fun toString(): String = "SocialAuthenticatedAccount(userId=$userId, token=<redacted>)"
}
