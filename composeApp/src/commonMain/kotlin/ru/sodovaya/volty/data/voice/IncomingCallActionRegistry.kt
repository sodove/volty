package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import ru.sodovaya.volty.data.nearby.DirectCallReply
import ru.sodovaya.volty.data.nearby.DirectCallSnapshot
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.voice.VoiceOperationId
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

internal class IncomingCallActionBinding internal constructor(val id: String)
internal data class IncomingCallRegistryOffer(val account: SocialAuthenticatedAccount,
    val binding: IncomingCallActionBinding, val original: IncomingCallOffer)
/** Queued SERIAL native-action lookup. It owns notifications, never borrowed voice/account. */
internal class IncomingCallActionRegistry(private val queuedDispatcher: CoroutineDispatcher, private val nextId: () -> String) {
    private class Entry(val account: SocialAuthenticatedAccount, val owner: IncomingCallNotificationOwner,
                        val binding: IncomingCallActionBinding) { var observer: Job? = null }
    private val offerOutput = MutableStateFlow<IncomingCallRegistryOffer?>(null)
    val offer = offerOutput.asStateFlow()
    private var selected: Entry? = null
    private var rootJob: Job? = null
    private var closed = false
    private val issuedIds = mutableSetOf<String>()
    /** Same queued serial caller as the notification port; never a cross-thread token lookup. */
    fun bindingFor(ticket: IncomingCallNotificationTicket): IncomingCallActionBinding? =
        selected?.takeIf {live(it) && it.owner.mayIssueAction(ticket)}?.binding
    internal fun matches(binding: IncomingCallActionBinding, account: SocialAuthenticatedAccount,
                         owner: IncomingCallNotificationOwner): Boolean =
        selected?.let {it.binding === binding && it.account === account && it.owner === owner && live(it)} == true

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
            offerOutput.value = null
            old?.observer?.cancel()
            entry.observer = CoroutineScope(account.scope().coroutineContext + queuedDispatcher).launch {
                try {owner.offer.collect {original ->
                    if (selected === entry)
                        offerOutput.value = original?.takeIf {live(entry)}?.let {IncomingCallRegistryOffer(account, entry.binding, it)}
                }} finally {withContext(NonCancellable + queuedDispatcher) {
                    if (selected === entry) offerOutput.value = null
                }}
            }
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

    suspend fun callerName(bindingId: String, offerId: String): String? = withContext(queuedDispatcher) {
        findOffer(bindingId, offerId)?.second?.callerName
    }

    suspend fun acceptOffer(bindingId: String, offerId: String): DirectCallSnapshot? = withContext(queuedDispatcher) {
        val (entry, original) = findOffer(bindingId, offerId) ?: return@withContext null
        val call = entry.owner.acceptOffer(original)
        call.takeIf {selected === entry && live(entry)}
    }
    suspend fun declineOffer(bindingId: String, offerId: String, operation: VoiceOperationId): Deferred<DirectCallReply>? =
        withContext(queuedDispatcher) {
            val (entry, original) = findOffer(bindingId, offerId) ?: return@withContext null
            entry.owner.declineOffer(original, operation)
        }

    suspend fun detach(binding: IncomingCallActionBinding): Boolean = withContext(NonCancellable) {
        withContext(queuedDispatcher) {
            val entry = selected?.takeIf {it.binding === binding} ?: return@withContext false
            selected = null
            offerOutput.value = null; entry.observer?.cancel()
            entry.owner.close()
            true
        }
    }

    suspend fun close() = withContext(NonCancellable) {withContext(queuedDispatcher) {
        closed = true
        val entry = selected; selected = null
        offerOutput.value = null; entry?.observer?.cancel()
        entry?.owner?.close()
    }}

    private fun live(entry: Entry) = !closed && entry.account.job.isActive && !entry.owner.state.value.retired
    internal fun matchesOffer(original: IncomingCallRegistryOffer): Boolean = selected?.let {
        live(it) && it.account === original.account && it.binding === original.binding && it.owner.offer.value === original.original
    } == true
    private fun findOffer(bindingId: String, offerId: String): Pair<Entry, IncomingCallOffer>? {
        try {invitationDraftId(bindingId); invitationDraftId(offerId)} catch (_: IllegalArgumentException) {return null}
        val entry = selected?.takeIf {it.binding.id == bindingId && live(it)} ?: return null
        val original = entry.owner.offer.value?.takeIf {it.id == offerId} ?: return null
        return entry to original
    }
    private fun find(bindingId: String, ticketId: String): Pair<Entry, IncomingCallNotificationTicket>? {
        try {invitationDraftId(bindingId); invitationDraftId(ticketId)} catch (_: IllegalArgumentException) {return null}
        val entry = selected?.takeIf {it.binding.id == bindingId && live(it)} ?: return null
        val ticket = entry.owner.state.value.ticket?.takeIf {it.id == ticketId} ?: return null
        return entry to ticket
    }
}
