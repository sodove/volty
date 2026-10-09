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
    private val receiver = IncomingCallPushWake(repository.authenticatedAccount, owner, gate)
    fun start() = owner.start()
    fun tokenHint() {events.tokens.tryEmit(Unit)}
    suspend fun receive(data: Map<String, String>): Boolean = receiver.receive(data)
}
