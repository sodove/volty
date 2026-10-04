package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

/** Supplied by authenticated account establishment, never by decoding an unverified JWT. */
class NearbyAccountCredentials(val actorId: String, private val token: String) {
    init { invitationDraftId(actorId); require(token.isNotBlank()) }
    internal fun bearer(): String = token
    override fun toString(): String = "NearbyAccountCredentials(actorId=$actorId, token=<redacted>)"
}

/** Immutable principal for a lifetime; same-actor refresh cannot revive a retired lifetime. */
class NearbyAccountBinding(
    initial: NearbyAccountCredentials,
    private val accountJob: Job? = null,
) {
    val actorId: String = initial.actorId
    private val credentials = MutableStateFlow<NearbyAccountCredentials?>(initial)

    fun bearer(): String? {
        if (!accountActive()) return null
        val current = credentials.value ?: return null
        if (!accountActive()) return null
        return current.bearer()
    }

    fun refresh(updated: NearbyAccountCredentials) {
        require(updated.actorId == actorId)
        while (true) {
            ensureAccountActive()
            val previous = checkNotNull(credentials.value) { "Nearby account retired" }
            if (credentials.compareAndSet(previous, updated)) {
                ensureAccountActive()
                return
            }
        }
    }

    fun retire() { credentials.value = null }

    private fun accountActive(): Boolean {
        if (accountJob?.isActive == false) {
            retire()
            return false
        }
        return true
    }

    private fun ensureAccountActive() {
        if (!accountActive()) throw IllegalStateException("Nearby account retired")
    }
}
