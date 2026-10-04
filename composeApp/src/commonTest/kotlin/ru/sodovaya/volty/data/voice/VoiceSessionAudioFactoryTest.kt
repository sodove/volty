package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

class VoiceSessionAudioFactoryTest {
    private val first = VoiceSessionTag(VoiceSessionId("10000000-0000-0000-0000-000000000001"), VoiceGeneration(1))
    private val second = VoiceSessionTag(VoiceSessionId("20000000-0000-0000-0000-000000000002"), VoiceGeneration(2))

    @Test fun snapshotsCurrentPreferencesOncePerAllocationWithoutChangingOutputWithInput() {
        var selected = VoiceAudioPreferences(VoiceInputPreference.HEADSET, VoiceOutputPreference.HEADSET)
        var reads = 0
        val issued = mutableListOf<VoiceAudioPreferences>()
        val factory = VoiceSessionAudioFactory({ reads++; selected }) { _, preferences, diagnostics, _ ->
            assertEquals(preferences, diagnostics.state.value.preferences)
            issued += preferences
            Session()
        }
        assertNull(factory.latestAllocation.value)
        assertEquals(0, reads)
        factory.create(first) {}
        selected = selected.copy(input = VoiceInputPreference.PHONE)
        factory.create(second) {}
        assertEquals(2, reads)
        assertEquals(listOf(VoiceInputPreference.HEADSET, VoiceInputPreference.PHONE), issued.map { it.input })
        assertEquals(listOf(VoiceOutputPreference.HEADSET, VoiceOutputPreference.HEADSET), issued.map { it.output })
        assertEquals(issued[0].outputPriority(), issued[1].outputPriority())
        assertEquals(second, assertNotNull(factory.latestAllocation.value).tag)
    }

    @Test fun lateOldDiagnosticWriterCannotContaminateNewAllocation() {
        val issued = mutableListOf<VoiceDiagnostics>()
        val factory = VoiceSessionAudioFactory({ VoiceAudioPreferences() }) { _, _, diagnostics, _ ->
            issued += diagnostics
            Session()
        }
        factory.create(first) {}
        issued[0].addCaptureBytes(8)
        factory.create(second) {}
        val latest = assertNotNull(factory.latestAllocation.value)
        assertSame(issued[1], latest.diagnostics)
        assertNotSame(issued[0], issued[1])
        issued[0].addCaptureBytes(100)
        issued[0].record(100, VoiceDiagnosticCode.CLOSED)
        assertEquals(108, issued[0].state.value.captureBytes)
        assertEquals(second.generation, latest.diagnostics.state.value.generation)
        assertEquals(0, latest.diagnostics.state.value.captureBytes)
        assertTrue(latest.diagnostics.state.value.trace.isEmpty())
        assertSame(latest, factory.latestAllocation.value)
    }

    @Test fun failedAllocationNeverPublishesNewDiagnosticOwnership() {
        var allocations = 0
        val returned = Session()
        val factory = VoiceSessionAudioFactory({ VoiceAudioPreferences() }) { _, _, _, _ ->
            if (++allocations == 2) error("Allocation failed")
            returned
        }
        assertSame(returned, factory.create(first) {})
        val previous = assertNotNull(factory.latestAllocation.value)
        assertFailsWith<IllegalStateException> { factory.create(second) {} }
        assertEquals(2, allocations)
        assertSame(previous, factory.latestAllocation.value)
        assertEquals(first, previous.tag)
    }

    private class Session : VoiceSdkSession {
        override suspend fun connect(credentials: VoiceConnectionCredentials) = Unit
        override suspend fun publishMicrophone(muted: Boolean) = true
        override suspend fun setMuted(muted: Boolean) = true
        override fun setDeafened(deafened: Boolean) = Unit
        override suspend fun close() = Unit
    }
}
