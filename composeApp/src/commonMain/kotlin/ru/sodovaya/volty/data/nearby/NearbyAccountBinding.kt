package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.flow.MutableStateFlow
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

/** Supplied by authenticated account establishment, never by decoding an unverified JWT. */
class NearbyAccountCredentials(val actorId: String, private val token: String) {
    init { invitationDraftId(actorId); require(token.isNotBlank()) }
    internal fun bearer(): String = token
    override fun toString(): String = "NearbyAccountCredentials(actorId=$actorId, token=<redacted>)"
}

/** Immutable principal for a lifetime; same-actor refresh cannot revive a retired lifetime. */
class NearbyAccountBinding(initial: NearbyAccountCredentials) {
    val actorId: String = initial.actorId
    private val credentials = MutableStateFlow<NearbyAccountCredentials?>(initial)

    fun bearer(): String? = credentials.value?.bearer()

    fun refresh(updated: NearbyAccountCredentials) {
        require(updated.actorId == actorId)
        while (true) {
            val previous = checkNotNull(credentials.value) { "Nearby account retired" }
            if (credentials.compareAndSet(previous, updated)) return
        }
    }

    fun retire() { credentials.value = null }
}
