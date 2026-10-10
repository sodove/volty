package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAudioRouteTransactionTest {
    @Test fun retiredLeaseCannotIssueNativeAudioChange() = runTest {
        var issued=0
        val session=object : VoiceSdkSession {
            override suspend fun connect(credentials:VoiceConnectionCredentials) {}
            override suspend fun publishMicrophone(muted:Boolean)=true
            override suspend fun setMuted(muted:Boolean)=true
            override fun setDeafened(deafened:Boolean) {}
            override suspend fun close() {}
            override suspend fun configureAudio(preferences:VoiceAudioPreferences):VoiceAudioChangeResult {issued++;return VoiceAudioChangeResult.REQUESTED}
        }
        assertEquals(VoiceAudioChangeResult.STALE,session.configureAudioIfCurrent(VoiceAudioPreferences()) {false})
        assertEquals(0,issued)
    }
    private val old = VoiceAudioPreferences()
    private val requested = VoiceAudioPreferences(input = VoiceInputPreference.PHONE)
    @Test fun rejectedChangeRestoresOriginalPairAndKeepsSessionSafe() = runTest {
        val issued = mutableListOf<VoiceAudioPreferences>(); val safe = mutableListOf<Boolean>()
        val result = voiceAudioRouteTransaction(requested, old, { true }, { p, _ ->
            issued += p; if (p == requested) VoiceAudioChangeResult.FAILED else VoiceAudioChangeResult.REQUESTED
        }, safe::add)
        assertEquals(VoiceAudioChangeResult.FAILED, result)
        assertEquals(listOf(requested, old), issued); assertEquals(listOf(true), safe)
    }
    @Test fun cancelledNativeChangeFinishesRollbackBeforeNextOwner() = runTest {
        val entered = CompletableDeferred<Unit>(); val issued = mutableListOf<VoiceAudioPreferences>(); val safe = mutableListOf<Boolean>()
        val job = backgroundScope.launch { voiceAudioRouteTransaction(requested, old, { true }, { p, _ ->
            issued += p
            if (p == requested) { entered.complete(Unit); awaitCancellation() }
            assertTrue(currentCoroutineContext().isActive)
            VoiceAudioChangeResult.REQUESTED
        }, safe::add) }
        runCurrent(); assertTrue(entered.isCompleted); job.cancel(); runCurrent()
        assertEquals(listOf(requested, old), issued); assertEquals(listOf(true), safe); assertTrue(job.isCompleted)
    }
    @Test fun retiredSessionNeverIssuesPositiveRollback() = runTest {
        var owned = true; val issued = mutableListOf<VoiceAudioPreferences>(); val safe = mutableListOf<Boolean>()
        voiceAudioRouteTransaction(requested, old, { owned }, { p, _ ->
            issued += p; owned = false; VoiceAudioChangeResult.STALE
        }, safe::add)
        assertEquals(listOf(requested), issued); assertEquals(listOf(false), safe)
    }
    @Test fun successfulChangeDoesNotRestoreOldPair() = runTest {
        val issued = mutableListOf<VoiceAudioPreferences>(); val safe = mutableListOf<Boolean>()
        voiceAudioRouteTransaction(requested, old, { true }, { p, _ -> issued += p; VoiceAudioChangeResult.REQUESTED }, safe::add)
        assertEquals(listOf(requested), issued); assertEquals(listOf(true), safe)
    }
}
