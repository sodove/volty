package ru.sodovaya.volty.presentation.root.nearby

import kotlinx.coroutines.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.data.voice.*
import ru.sodovaya.volty.domain.voice.VoiceOperationId
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

/** Registry of actual original preparations. No UI scope, authentication, voice or navigation ownership. */
internal class NearbySharingSwitchGuard(private val repository: DefaultSocialRepository,private val voice: VoiceConversationStartup,
    private val queued: CoroutineDispatcher) : NearbyVoiceSwitchGuard {
    private val root=checkNotNull(repository.applicationScope().coroutineContext[Job])
    init {require(root.isActive && queued.isDispatchNeeded(repository.applicationScope().coroutineContext))}
    private val handles=mutableMapOf<VoiceOperationId,MutableSet<ChannelSharingStartHandle>>()
    suspend fun remember(account: SocialAuthenticatedAccount,operation: VoiceOperationId,handle: ChannelSharingStartHandle):Boolean=withContext(queued) {
        val source=voice.state.value
        val session=source.voice?.sessionId ?: return@withContext false
        val original=VoiceSessionTag(session,source.voice.generation)
        if(!root.isActive || !account.job.isActive || repository.authenticatedAccount.value!==account ||
            source.operationId!=operation || source.target !is VoiceConversationTarget.Channel ||
            source.phase!=VoiceConversationPhase.JOIN_SUBMITTED || !handle.belongsTo(account,original)) return@withContext false
        handles.getOrPut(operation) {mutableSetOf()}.add(handle)
        true
    }
    override fun requiresConfirmation(operation: VoiceOperationId?)=handles[operation]?.any {
        it.state.value.let {state -> state.wanted || state.preparing}
    }==true
    override fun withdrawSharing(operation: VoiceOperationId?) {
        handles[operation]?.toList()?.forEach {it.requestHide()}
    }
    override suspend fun awaitSharingCleanup(operation: VoiceOperationId?):Boolean=withContext(queued) {
        val originals=handles[operation]?.toList().orEmpty()
        var clean=true
        for(handle in originals) {
            // close() proves actual producer/native/child release, never remote acknowledgement.
            val local=handle.close()
            // An HTTP negative may still have failed its durable ACK. The original journal is authoritative.
            val originalClean=local && handle.recoverOriginalCleanup()
            if(local && originalClean) {
                handles[operation]?.remove(handle)
            } else clean=false
        }
        if(handles[operation]?.isEmpty()==true) handles.remove(operation)
        clean
    }
}
