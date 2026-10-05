package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import ru.sodovaya.volty.data.nearby.DirectCallReply
import ru.sodovaya.volty.data.nearby.DirectCallSnapshot
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.voice.VoiceOperationId
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

internal class IncomingCallActionBinding internal constructor(val id: String)
/** Queued SERIAL native-action lookup. It owns notifications, never borrowed voice/account. */
internal class IncomingCallActionRegistry(private val queuedDispatcher: CoroutineDispatcher, private val nextId: () -> String) {
    private class Entry(val account: SocialAuthenticatedAccount, val owner: IncomingCallNotificationOwner,
                        val binding: IncomingCallActionBinding)
    private var selected: Entry? = null
    private var rootJob: Job? = null
    private var closed = false
    private val issuedIds = mutableSetOf<String>()

    suspend fun bind(account: SocialAuthenticatedAccount, owner: IncomingCallNotificationOwner): IncomingCallActionBinding =
        withContext(queuedDispatcher) {
            currentCoroutineContext().ensureActive()
            check(!closed && account.job.isActive && !owner.state.value.retired) {"Incoming binding retired"}
            require(owner.belongsTo(account)) {"Notification owner belongs to another account"}
            val parent = account.retirementParentScope()
            val root = requireNotNull(parent.coroutineContext[Job]) {"Incoming binding requires application Job"}
            check(root.isActive)
            require(root !== account.job && (rootJob == null || rootJob === root)) {"Incoming binding root does not match"}
            require(queuedDispatcher.isDispatchNeeded(parent.coroutineContext)) {"Queued serial dispatcher required"}
            selected?.takeIf {it.account === account && it.owner === owner}?.let {return@withContext it.binding}
            val id = nextId().also(::invitationDraftId)
            require(issuedIds.add(id)) {"Incoming binding cannot be reused"}
            val entry = Entry(account, owner, IncomingCallActionBinding(id))
            val old = selected
            rootJob = root; selected = entry
            // Fence before suspension. Only this unique notification child closes, not its runtime.
            old?.owner?.close()
            entry.binding
        }

    suspend fun accept(bindingId: String, ticketId: String): DirectCallSnapshot? = withContext(queuedDispatcher) {
        val (entry, ticket) = find(bindingId, ticketId) ?: return@withContext null
        val call = entry.owner.acceptTarget(ticket)
        call.takeIf {selected === entry && live(entry)}
    }

    suspend fun decline(bindingId: String, ticketId: String, operation: VoiceOperationId): Deferred<DirectCallReply>? =
        withContext(queuedDispatcher) {
            val (entry, ticket) = find(bindingId, ticketId) ?: return@withContext null
            entry.owner.decline(ticket, operation)
        }

    suspend fun detach(binding: IncomingCallActionBinding): Boolean = withContext(NonCancellable) {
        withContext(queuedDispatcher) {
            val entry = selected?.takeIf {it.binding === binding} ?: return@withContext false
            selected = null
            entry.owner.close()
            true
        }
    }

    suspend fun close() = withContext(NonCancellable) {withContext(queuedDispatcher) {
        closed = true
        val entry = selected; selected = null
        entry?.owner?.close()
    }}

    private fun live(entry: Entry) = !closed && entry.account.job.isActive && !entry.owner.state.value.retired
    private fun find(bindingId: String, ticketId: String): Pair<Entry, IncomingCallNotificationTicket>? {
        try {invitationDraftId(bindingId); invitationDraftId(ticketId)} catch (_: IllegalArgumentException) {return null}
        val entry = selected?.takeIf {it.binding.id == bindingId && live(it)} ?: return null
        val ticket = entry.owner.state.value.ticket?.takeIf {it.id == ticketId} ?: return null
        return entry to ticket
    }
}
