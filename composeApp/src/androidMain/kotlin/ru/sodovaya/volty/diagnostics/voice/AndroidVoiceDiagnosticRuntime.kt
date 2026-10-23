package ru.sodovaya.volty.diagnostics.voice

import android.content.Context
import kotlinx.coroutines.*
import ru.sodovaya.volty.data.voice.*
import ru.sodovaya.volty.domain.social.*

/** Process-local owner shared by Activity and service. Never restores a call after death. */
class AndroidVoiceDiagnosticRuntime(
    context: Context, social: SocialRepository, legacy: VoiceRoomRepository,
    listen: kotlinx.coroutines.flow.StateFlow<ru.sodovaya.volty.domain.audio.VoiceListenSettings>,
    mic: kotlinx.coroutines.flow.StateFlow<ru.sodovaya.volty.domain.audio.MicProcessingSettings>,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val controller: VoiceDiagnosticController = VoiceDiagnosticController(scope, object : VoiceDiagnosticPort {
        override suspend fun groups(): List<VoiceDiagnosticGroup> = when (val result = social.listGroups()) {
            is SocialResult.Success -> result.value.map { VoiceDiagnosticGroup(it.id.value, it.name) }
            is SocialResult.Failure -> throw VoiceEngineException(VoiceEngineError.CONNECTION)
        }
        override suspend fun leaveLegacyVoice() {
            // Legacy leave cannot cancel its pending token/connect; do not overlap adapters.
            if (legacy.state.value is VoiceRoomState.Joining || legacy.state.value is VoiceRoomState.Joined || social.activeSharing.value != null)
                throw VoiceEngineException(VoiceEngineError.LEGACY_ACTIVE)
            if (legacy.leave() is SocialResult.Failure) throw VoiceEngineException(VoiceEngineError.CLEANUP)
        }
        override suspend fun join(group: String): VoiceConnectionCredentials = when (val result = social.joinVoice(RideGroupId(group))) {
            is SocialResult.Success -> VoiceConnectionCredentials(result.value.serverUrl, result.value.participantToken)
            is SocialResult.Failure -> throw VoiceEngineException(VoiceEngineError.CONNECTION)
        }
        override suspend fun leave(group: String) {
            if (social.leaveVoice(RideGroupId(group)) is SocialResult.Failure) throw VoiceEngineException(VoiceEngineError.CLEANUP)
        }
    }) { _, preferences, diagnostics ->
        VoiceEngineDriver(scope, AndroidVoiceSdkSessionFactory(context, preferences, listen, mic, diagnostics), ::diagnosticAuthorized)
    }
    private fun diagnosticAuthorized(tag: ru.sodovaya.volty.domain.voice.VoiceSessionTag): Boolean =
        scope.isActive && controller.authorizesEngine(tag)
}
