package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.social.DefaultSocialRepository

internal class AndroidPushEvents {
    val tokens = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
/** Application singleton; constructing the repository starts its generation-owned credential restoration. */
internal class AndroidPushStartup(private val repository: DefaultSocialRepository,
    private val owner: PushRegistrationOwner, private val gate: IncomingCallPushGate, private val events: AndroidPushEvents) {
    fun start() = owner.start()
    fun tokenHint() {events.tokens.tryEmit(Unit)}
    suspend fun receive(data: Map<String, String>): Boolean {
        val hint = IncomingCallPushHint.decode(data) ?: return false
        return withTimeoutOrNull(15_000) {
            repository.authenticatedAccount.filterNotNull().first()
            gate.receive(hint)
        } ?: false
    }
}
