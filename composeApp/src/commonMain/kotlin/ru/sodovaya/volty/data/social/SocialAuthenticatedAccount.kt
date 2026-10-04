package ru.sodovaya.volty.data.social

import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import ru.sodovaya.volty.domain.social.SocialUserId

/** Created only after a verified profile and its exact credentials are committed. */
class SocialAuthenticatedAccount internal constructor(
    val userId: SocialUserId,
    val job: Job,
    private val parentContext: CoroutineContext,
    private val tokenSource: suspend () -> String?,
) {
    /** Borrows the real account Job and repository dispatcher, allocating no new Job. */
    internal fun scope(): CoroutineScope = CoroutineScope(parentContext + job)

    /** Original repository/application root, borrowed only for explicit retirement work. */
    internal fun retirementParentScope(): CoroutineScope = CoroutineScope(parentContext)

    /** Source retains its original authentication generation, including during refresh. */
    internal suspend fun bearer(): String? {
        if (!job.isActive) return null
        val token = tokenSource()
        return if (job.isActive) token else null
    }

    override fun toString(): String = "SocialAuthenticatedAccount(userId=$userId, token=<redacted>)"
}
