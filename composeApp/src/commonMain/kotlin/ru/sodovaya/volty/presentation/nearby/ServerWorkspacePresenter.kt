package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.domain.nearby.community.*

enum class ServerWorkspaceSection { MY_SERVERS, CATALOG, CREATE, SERVER }
data class ServerCreationDraft(val name: String = "", val description: String = "",
    val visibility: CommunityVisibility = CommunityVisibility.PRIVATE)
data class ChannelCreationForm(val visible: Boolean = false, val community: String? = null, val name: String = "")
data class ServerWorkspaceUiState(
    val actorId: String? = null,
    val section: ServerWorkspaceSection = ServerWorkspaceSection.MY_SERVERS,
    val query: String = "",
    val draft: ServerCreationDraft = ServerCreationDraft(),
    val browser: CommunityBrowserState = CommunityBrowserState(),
    val commands: CommunityWorkspaceState = CommunityWorkspaceState(),
    val origin: ServerWorkspaceSection = ServerWorkspaceSection.MY_SERVERS,
    val channelForm: ChannelCreationForm = ChannelCreationForm(),
    val channelCreation: ChannelCreationState = ChannelCreationState(),
)

/** Screen projection/commands; connecting is delegated to the root conversation owner. */
class ServerWorkspacePresenter(parent: CoroutineScope, private val workspace: CommunityWorkspace,
    private val actorId: String, private val creation: ChannelCreationWorkspace, private val connectChannel: (String) -> Unit) {
    init { invitationDraftId(actorId);require(parent.coroutineContext[Job]?.isActive == true && workspace.active) }
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private data class Form(val section: ServerWorkspaceSection = ServerWorkspaceSection.MY_SERVERS,
        val returnTo: ServerWorkspaceSection = ServerWorkspaceSection.MY_SERVERS,
        val query: String = "", val draft: ServerCreationDraft = ServerCreationDraft(),
        val channel: ChannelCreationForm = ChannelCreationForm())
    private val form = MutableStateFlow(Form())
    private val output = MutableStateFlow(ServerWorkspaceUiState(actorId = actorId))
    val state = output.asStateFlow()
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
        scope.launch { workspace.retired.first {it};close() }
        scope.launch {
            workspace.browser.state.map { browser ->
                browser.selectedCommunity to (browser.detail?.let {it.community.id==browser.selectedCommunity && it.ownerId==actorId} == true &&
                    !browser.detailLoading && browser.detailError==null)
            }.distinctUntilChanged().collect { (selected, owner) ->
                form.update { current ->
                    val next = if (current.channel.community != selected || !owner) current.copy(channel=ChannelCreationForm()) else current
                    if (selected != null) next.copy(section = ServerWorkspaceSection.SERVER)
                    else if (next.section == ServerWorkspaceSection.SERVER) next.copy(section = next.returnTo)
                    else next
                }
            }
        }
        scope.launch {
            combine(form, workspace.browser.state, workspace.state, creation.state) { form, browser, commands, channel ->
                ServerWorkspaceUiState(actorId,form.section,form.query,form.draft,browser,commands,form.returnTo,form.channel,channel)
            }.collect { if(lifetime.isActive) output.value=it }
        }
        scope.launch {
            creation.state.map {it.created}.distinctUntilChanged().collect { receipt ->
                if(receipt != null) form.update { current ->
                    if(current.channel.community==receipt.communityId && current.channel.name.trim()==receipt.name)
                        current.copy(channel=ChannelCreationForm()) else current
                }
            }
        }
    }
    private fun enabled(allowCreation: Boolean = false) = lifetime.isActive && workspace.active &&
        !workspace.state.value.busy && (allowCreation || !creation.state.value.busy)
    fun openChannelCreation() {
        if(!enabled() || form.value.section!=ServerWorkspaceSection.SERVER) return
        val browser=workspace.browser.state.value
        val detail=browser.detail?.takeIf {!browser.detailLoading && browser.detailError==null &&
            it.community.id==browser.selectedCommunity && it.ownerId==actorId} ?: return
        form.update {it.copy(channel=ChannelCreationForm(true,detail.community.id))}
    }
    fun setChannelName(value: String) {
        if(enabled() && form.value.channel.visible && value.length<=80)
            form.update {it.copy(channel=it.channel.copy(name=value))}
    }
    fun dismissChannelCreation() {if(enabled(true)) form.update {it.copy(channel=ChannelCreationForm())}}
    fun submitChannelCreation() {
        if(!enabled()) return
        // Capture the original form before dispatch, never resolve a replacement server in the queued body.
        val original=form.value.channel
        if(!original.visible) return
        action {
            if(form.value.channel==original && workspace.browser.state.value.selectedCommunity==original.community)
                creation.create(original.name)
        }
    }
    private fun action(allowCreation: Boolean = false, work: suspend () -> Unit) {
        if (!enabled(allowCreation)) return
        scope.launch { if (enabled(allowCreation)) work() }
    }
    fun openMine() = action(true) {
        form.update { it.copy(section=ServerWorkspaceSection.MY_SERVERS,returnTo=ServerWorkspaceSection.MY_SERVERS) }
        workspace.dismissRemoval();workspace.browser.clearSelection();workspace.browser.refreshMemberships()
    }
    fun openCatalog() = action(true) {
        form.update { it.copy(section=ServerWorkspaceSection.CATALOG,returnTo=ServerWorkspaceSection.CATALOG) }
        workspace.dismissRemoval();workspace.browser.clearSelection();workspace.browser.search(form.value.query)
    }
    fun openCreate() {
        if(!enabled()) return
        val origin=if(form.value.section==ServerWorkspaceSection.CATALOG) ServerWorkspaceSection.CATALOG else ServerWorkspaceSection.MY_SERVERS
        form.update { it.copy(section=ServerWorkspaceSection.CREATE,returnTo=origin,draft=ServerCreationDraft()) }
        action {workspace.dismissRemoval();workspace.browser.clearSelection()}
    }
    fun openServer(id: String) = action(true) { workspace.dismissRemoval();workspace.browser.openCommunity(id) }
    fun joinPublic(id: String) = action { workspace.join(id) }
    fun setQuery(value: String) {
        if (!enabled() || value.length>80) return
        form.update {it.copy(query=value)}
        action {workspace.browser.search(value)}
    }
    fun setName(value: String) {if(enabled()) form.update {it.copy(draft=it.draft.copy(name=value))}}
    fun setDescription(value: String) {if(enabled()) form.update {it.copy(draft=it.draft.copy(description=value))}}
    fun setVisibility(value: CommunityVisibility) {if(enabled()) form.update {it.copy(draft=it.draft.copy(visibility=value))}}
    fun submitCreate() = action {
        val draft=form.value.draft
        workspace.create(draft.name,draft.description,draft.visibility)
    }
    fun loadMore() = action {
        if(form.value.section==ServerWorkspaceSection.CATALOG ||
            (form.value.section==ServerWorkspaceSection.SERVER && form.value.returnTo==ServerWorkspaceSection.CATALOG)) workspace.browser.loadMoreCatalog()
        else workspace.browser.loadMoreMemberships()
    }
    fun refreshList(catalog: Boolean) = action {
        if(catalog) workspace.browser.search(form.value.query) else workspace.browser.refreshMemberships()
    }
    fun requestLeave() = action {workspace.browser.state.value.selectedCommunity?.let {workspace.requestRemoval(it,false)}}
    fun requestDelete() = action {
        val detail=workspace.browser.state.value.detail ?: return@action
        if(detail.ownerId==actorId) workspace.requestRemoval(detail.community.id,true)
    }
    fun dismissRemoval() = action {workspace.dismissRemoval()}
    fun confirmRemoval() = action {workspace.confirmRemoval()}
    fun refresh() = action {
        when(form.value.section) {
            ServerWorkspaceSection.SERVER -> workspace.browser.state.value.selectedCommunity?.let {workspace.browser.openCommunity(it)}
            ServerWorkspaceSection.CATALOG -> workspace.browser.search(form.value.query)
            else -> workspace.browser.refreshMemberships()
        }
    }
    fun back() = action(true) {
        val origin=form.value.returnTo
        form.update {it.copy(section=origin)}
        workspace.dismissRemoval();workspace.browser.clearSelection()
    }
    fun connect(channel: String) {
        if(!enabled() || form.value.section!=ServerWorkspaceSection.SERVER) return
        val browser=workspace.browser.state.value
        val detail=browser.detail ?: return
        if(!browser.detailLoading && detail.community.id==browser.selectedCommunity && detail.channels.any {it.id==channel}) connectChannel(channel)
    }
    suspend fun close(): Unit = withContext(NonCancellable) {
        lifetime.cancel();creation.close();workspace.close();form.value=Form();output.value=ServerWorkspaceUiState()
    }
}
