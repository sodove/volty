package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import ru.sodovaya.volty.domain.voice.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDiagnosticControllerTest {
    @Test fun rejectedPreflightNeverLeavesExistingGroupVoice() = runTest {
        var joins = 0
        var leaves = 0
        val port = object : VoiceDiagnosticPort {
            override suspend fun groups() = emptyList<VoiceDiagnosticGroup>()
            override suspend fun leaveLegacyVoice() { throw VoiceEngineException(VoiceEngineError.LEGACY_ACTIVE) }
            override suspend fun join(group: String): VoiceConnectionCredentials { joins++; error("not reached") }
            override suspend fun leave(group: String) { leaves++ }
        }
        val controller = VoiceDiagnosticController(backgroundScope, port) { _, _, _ -> error("not reached") }
        controller.start("existing", VoiceAudioPreferences())
        runCurrent()
        assertEquals(0, joins)
        assertEquals(0, leaves)
        assertEquals(VoiceEngineError.LEGACY_ACTIVE, controller.state.value.error)
    }
    @Test fun stopWaitsForLateCredentialsAndNeverStartsSdk() = runTest {
        val entered = CompletableDeferred<Unit>()
        val token = CompletableDeferred<VoiceConnectionCredentials>()
        var sdkCreated = 0
        var leaves = 0
        val port = object : VoiceDiagnosticPort {
            override suspend fun groups() = emptyList<VoiceDiagnosticGroup>()
            override suspend fun leaveLegacyVoice() = Unit
            override suspend fun join(group: String): VoiceConnectionCredentials = withContext(NonCancellable) {
                entered.complete(Unit); token.await()
            }
            override suspend fun leave(group: String) { leaves++ }
        }
        val controller = VoiceDiagnosticController(backgroundScope, port) { _, _, _ ->
            sdkCreated++; error("must not allocate")
        }
        controller.start("group", VoiceAudioPreferences())
        runCurrent()
        assertTrue(entered.isCompleted)
        val stopped = async { controller.stop() }
        runCurrent()
        assertFalse(stopped.isCompleted)
        assertEquals(0, leaves)
        token.complete(VoiceConnectionCredentials("https://example.invalid", "secret"))
        runCurrent()
        stopped.await()
        assertEquals(0, sdkCreated)
        assertEquals(1, leaves)
        assertEquals(VoiceDiagnosticPhase.IDLE, controller.state.value.phase)
    }

    @Test fun cleanupFailureKeepsSessionForExplicitRetry() = runTest {
        var closes = 0
        var leaves = 0
        val port = object : VoiceDiagnosticPort {
            override suspend fun groups() = emptyList<VoiceDiagnosticGroup>()
            override suspend fun leaveLegacyVoice() = Unit
            override suspend fun join(group: String) = VoiceConnectionCredentials("url", "token")
            override suspend fun leave(group: String) { leaves++ }
        }
        val controller = VoiceDiagnosticController(backgroundScope, port) { _, _, _ ->
            VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> object : VoiceSdkSession {
                override suspend fun connect(credentials: VoiceConnectionCredentials) = Unit
                override suspend fun publishMicrophone(muted: Boolean) = true
                override suspend fun setMuted(muted: Boolean) = true
                override fun setDeafened(deafened: Boolean) = Unit
                override suspend fun close() { closes++; if (closes == 1) error("secret raw cleanup error") }
            } })
        }
        controller.start("group", VoiceAudioPreferences())
        runCurrent()
        assertEquals(VoiceDiagnosticPhase.RUNNING, controller.state.value.phase)
        assertFalse(controller.stop())
        assertEquals(VoiceDiagnosticPhase.CLEANUP_FAILED, controller.state.value.phase)
        controller.start("other", VoiceAudioPreferences())
        runCurrent()
        assertEquals(VoiceDiagnosticPhase.CLEANUP_FAILED, controller.state.value.phase)
        assertTrue(controller.stop())
        assertEquals(2, closes)
        assertEquals(1, leaves)
        assertEquals(VoiceDiagnosticPhase.IDLE, controller.state.value.phase)
    }
}
