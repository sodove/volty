package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.di.nearby.NearbyCommunityAccount
import ru.sodovaya.volty.di.nearby.NearbyCommunityAccountFactory

data class ServerWorkspaceBindingState(val presenter: ServerWorkspacePresenter? = null, val failed: Boolean = false)

/** Queued screen-context owner. Exact account identity is checked again at command dispatch. */
internal class ServerWorkspaceAccountBinding(parent: CoroutineScope,
    private val accounts: StateFlow<SocialAuthenticatedAccount?>, factory: NearbyCommunityAccountFactory,
    nextId: () -> String, connect: (SocialAuthenticatedAccount, String) -> Unit) {
    init { require(parent.coroutineContext[Job]?.isActive == true) }
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private data class Binding(val account: SocialAuthenticatedAccount,
        val consumer: NearbyCommunityAccount, val presenter: ServerWorkspacePresenter)
    private var selected: Binding? = null
    private var closed = false
    private val output = MutableStateFlow(ServerWorkspaceBindingState())
    private val retryVersion = MutableStateFlow(0L)
    val state = output.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        scope.launch {
            combine(accounts, retryVersion) { account, _ -> account }.collectLatest { account ->
                if (account == null || !current(account)) return@collectLatest
                var consumer: NearbyCommunityAccount? = null
                var presenter: ServerWorkspacePresenter? = null
                var creation: ChannelCreationWorkspace? = null
                var invitations: InvitationCreationWorkspace? = null
                var acceptance:InvitationAcceptanceWorkspace?=null
                var management:InvitationManagementWorkspace?=null
                var departure:OwnerDepartureWorkspace?=null
                var binding: Binding? = null
                var retirementGuardian: Job? = null
                try {
                    consumer = factory.create(account)
                    if (!current(account)) return@collectLatest
                    val workspace = consumer.workspace(scope, nextId)
                    creation = consumer.channelCreation(scope, workspace, nextId)
                    invitations = consumer.invitationCreation(scope, workspace, nextId)
                    acceptance=consumer.invitationAcceptance(scope,workspace,nextId)
                    management=consumer.invitationManagement(scope,workspace)
                    departure=consumer.ownerDeparture(scope,workspace)
                    presenter = ServerWorkspacePresenter(scope, workspace, account.userId.value, creation, invitations, acceptance,management,departure) { channel ->
                        if (current(account) && selected?.account === account) connect(account, channel)
                    }
                    binding = Binding(account, consumer, presenter)
                    selected = binding
                    output.value = ServerWorkspaceBindingState(presenter)
                    presenter.openMine()
                    // Cancellation withdraws access now; completion may wait for unrelated held producers.
                    val retired = CompletableDeferred<Unit>()
                    retirementGuardian = CoroutineScope(scope.coroutineContext + account.job)
                        .launch(start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { retired.complete(Unit) }
                        }
                    retired.await()
                } catch (failure: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw failure
                    if (current(account)) output.value = ServerWorkspaceBindingState(failed = true)
                } catch (_: Exception) {
                    if (current(account)) output.value = ServerWorkspaceBindingState(failed = true)
                } finally {
                    retirementGuardian?.cancel()
                    if (selected === binding) {
                        selected = null
                        if (!output.value.failed) output.value = ServerWorkspaceBindingState()
                    }
                    withContext(NonCancellable) {
                        presenter?.close()
                        creation?.close()
                        invitations?.close()
                        acceptance?.close()
                        management?.close()
                        departure?.close()
                        consumer?.retire()
                    }
                }
            }
        }
    }

    private fun current(account: SocialAuthenticatedAccount) =
        !closed && lifetime.isActive && account.job.isActive && accounts.value === account

    fun retry() {
        val account = accounts.value ?: return
        if (!current(account) || !output.value.failed) return
        output.value = ServerWorkspaceBindingState()
        retryVersion.value++
    }

    suspend fun close(): Unit = withContext(NonCancellable) {
        if (closed) return@withContext
        closed = true
        val old = selected
        selected = null
        output.value = ServerWorkspaceBindingState()
        lifetime.cancel()
        old?.presenter?.close()
        old?.consumer?.retire()
    }
}
