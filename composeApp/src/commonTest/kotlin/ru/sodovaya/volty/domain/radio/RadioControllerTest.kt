package ru.sodovaya.volty.domain.radio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RadioControllerTest {
    private class FakeLink(private val now: () -> Long = { 0L }) : RadioLink {
        override var onDuplicateHub: ((String) -> Unit)? = null
        val calls = mutableListOf<String>()
        var joinResult: RadioLinkResult = RadioLinkResult.NotFound
        var hostResult: RadioLinkResult = RadioLinkResult.Hosting(4000)
        val joinTimeouts = mutableListOf<Long>()
        val joinAttempts = mutableListOf<Pair<Long, Long>>() // (time, timeout)

        /** When set, join/host/leave suspend here (uncancellably) until completed. */
        var gate: CompletableDeferred<Unit>? = null
        var inFlight = 0
        var maxInFlight = 0

        private suspend fun <T> tracked(block: () -> T): T {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                gate?.let { g -> withContext(NonCancellable) { g.await() } }
                return block()
            } finally {
                inFlight--
            }
        }

        override suspend fun host(community: String): RadioLinkResult = tracked {
            calls += "host"
            hostResult
        }
        override suspend fun join(community: String, timeoutMillis: Long): RadioLinkResult {
            calls += "join"
            joinTimeouts += timeoutMillis
            joinAttempts += now() to timeoutMillis
            return tracked { joinResult }
        }
        override suspend fun leave() { tracked { calls += "leave" } }
        override fun radioNearby(community: String): Boolean = false
        fun count(name: String) = calls.count { it == name }
    }

    private class FakeVoice(var active: Boolean) : RadioServerVoice {
        var disconnects = 0
        override fun isActive() = active
        override fun disconnect() { disconnects++; active = false }
    }

    private class FakeKeys(var stored: StoredRadioKey?) : RadioKeyStore {
        override suspend fun get(actor: String, community: String) = stored
        override suspend fun put(actor: String, key: RadioKey, fetchedAtMillis: Long) {
            stored = StoredRadioKey(key, fetchedAtMillis)
        }
    }

    private val hub = RadioAddress("192.168.49.1", 4000)
    private fun key(fetchedAt: Long) = StoredRadioKey(RadioKey("c", 1, ByteArray(32)), fetchedAt)

    private class Rig(val link: FakeLink, val voice: FakeVoice, val keys: FakeKeys, val controller: RadioController)

    private fun TestScope.rig(
        stored: StoredRadioKey? = key(0),
        voiceActive: Boolean = false,
    ): Rig {
        val link = FakeLink { testScheduler.currentTime }
        val voice = FakeVoice(voiceActive)
        val keys = FakeKeys(stored)
        val controller = RadioController(link, keys, voice, { testScheduler.currentTime }, backgroundScope)
        return Rig(link, voice, keys, controller)
    }

    @Test
    fun raiseJoinsExistingRadioInsteadOfHosting() = runTest {
        val r = rig()
        r.link.joinResult = RadioLinkResult.Joined(hub)
        r.controller.raise("a", "c")
        runCurrent()
        assertEquals(0, r.link.count("host"))
        assertEquals(listOf(5_000L), r.link.joinTimeouts)
        assertEquals(RadioState.Client("c", RadioClientPhase.JOINING), r.controller.state.value)
    }

    @Test
    fun raiseHostsWhenNoneFound() = runTest {
        val r = rig()
        r.controller.raise("a", "c")
        runCurrent()
        assertEquals(listOf("join", "host"), r.link.calls)
        assertEquals(RadioState.Hub("c"), r.controller.state.value)
    }

    @Test
    fun raiseWithoutKeyErrors() = runTest {
        val r = rig(stored = null)
        r.controller.raise("a", "c")
        runCurrent()
        assertEquals(emptyList(), r.link.calls)
        assertEquals(RadioState.Error("c", RadioError.NO_KEY), r.controller.state.value)
    }

    @Test
    fun joinWithoutKeyErrors() = runTest {
        val r = rig(stored = null)
        r.controller.join("a", "c")
        runCurrent()
        assertEquals(emptyList(), r.link.calls)
        assertEquals(RadioState.Error("c", RadioError.NO_KEY), r.controller.state.value)
    }

    @Test
    fun notFoundWithOldKeySuggestsRefresh() = runTest {
        val r = rig(stored = key(fetchedAt = -25L * 3_600_000L))
        r.controller.join("a", "c")
        runCurrent()
        assertEquals(listOf(30_000L), r.link.joinTimeouts)
        assertEquals(RadioState.Error("c", RadioError.STALE_KEY), r.controller.state.value)
    }

    @Test
    fun notFoundWithFreshKeyIsNotFound() = runTest {
        val r = rig(stored = key(fetchedAt = -23L * 3_600_000L))
        r.controller.join("a", "c")
        runCurrent()
        assertEquals(RadioState.Error("c", RadioError.NOT_FOUND), r.controller.state.value)
    }

    @Test
    fun clientJoinTimesOutAsNotFound() = runTest {
        val r = rig()
        r.controller.join("a", "c")
        runCurrent()
        assertEquals(listOf("join"), r.link.calls)
        assertEquals(RadioState.Error("c", RadioError.NOT_FOUND), r.controller.state.value)
    }

    @Test
    fun joinSuccessBecomesClient() = runTest {
        val r = rig()
        r.link.joinResult = RadioLinkResult.Joined(hub)
        r.controller.join("a", "c")
        runCurrent()
        assertEquals(RadioState.Client("c", RadioClientPhase.JOINING), r.controller.state.value)
        r.controller.onClientPhase(RadioClientPhase.CONNECTED)
        assertEquals(RadioState.Client("c", RadioClientPhase.CONNECTED), r.controller.state.value)
    }

    @Test
    fun linkFailuresMapToErrors() = runTest {
        val r = rig()
        r.link.joinResult = RadioLinkResult.Unsupported
        r.controller.join("a", "c")
        runCurrent()
        assertEquals(RadioState.Error("c", RadioError.UNSUPPORTED_ANDROID), r.controller.state.value)
        r.link.joinResult = RadioLinkResult.Failed("x")
        r.controller.join("a", "c")
        runCurrent()
        assertEquals(RadioState.Error("c", RadioError.WIFI_FAILED), r.controller.state.value)
        r.link.joinResult = RadioLinkResult.NotFound
        r.link.hostResult = RadioLinkResult.Failed("x")
        r.controller.raise("a", "c")
        runCurrent()
        assertEquals(RadioState.Error("c", RadioError.WIFI_FAILED), r.controller.state.value)
    }

    @Test
    fun joiningDisconnectsServerVoice() = runTest {
        val r = rig(voiceActive = true)
        r.controller.join("a", "c")
        runCurrent()
        assertEquals(1, r.voice.disconnects)
        val r2 = rig(voiceActive = true)
        r2.controller.raise("a", "c")
        runCurrent()
        assertEquals(1, r2.voice.disconnects)
    }

    @Test
    fun inactiveServerVoiceIsLeftAlone() = runTest {
        val r = rig(voiceActive = false)
        r.controller.raise("a", "c")
        runCurrent()
        assertEquals(0, r.voice.disconnects)
    }

    private fun TestScope.becomeLost(r: Rig) {
        r.link.joinResult = RadioLinkResult.Joined(hub)
        r.controller.join("a", "c")
        runCurrent()
        r.link.calls.clear()
        r.link.joinTimeouts.clear()
        r.link.joinResult = RadioLinkResult.NotFound
        r.controller.onClientPhase(RadioClientPhase.LOST)
        runCurrent()
    }

    @Test
    fun lostKeepsRejoiningFor120Seconds() = runTest {
        val r = rig()
        becomeLost(r)
        assertEquals(RadioState.Lost("c", rejoinUntilMillis = 120_000), r.controller.state.value)
        assertEquals(true, r.link.count("join") >= 1)
        assertEquals(true, r.link.joinTimeouts.all { it == 5_000L })
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(true, r.link.count("join") > 10)
        assertEquals(RadioState.Lost("c", 120_000), r.controller.state.value)
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(RadioState.Error("c", RadioError.NOT_FOUND), r.controller.state.value)
        val final = r.link.count("join")
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(final, r.link.count("join"))
    }

    @Test
    fun lostRejoinSuccessReturnsToClient() = runTest {
        val r = rig()
        becomeLost(r)
        r.link.joinResult = RadioLinkResult.Joined(hub)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(RadioState.Client("c", RadioClientPhase.JOINING), r.controller.state.value)
        val joins = r.link.count("join")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(joins, r.link.count("join"))
    }

    @Test
    fun reRaiseFromLostHosts() = runTest {
        val r = rig()
        becomeLost(r)
        r.controller.reRaise()
        runCurrent()
        assertEquals(1, r.link.count("host"))
        assertEquals(RadioState.Hub("c"), r.controller.state.value)
        val joins = r.link.count("join")
        advanceTimeBy(200_000)
        runCurrent()
        assertEquals(joins, r.link.count("join"))
    }

    @Test
    fun reRaiseOutsideLostDoesNothing() = runTest {
        val r = rig()
        r.controller.reRaise()
        runCurrent()
        assertEquals(emptyList(), r.link.calls)
        assertEquals(RadioState.Idle, r.controller.state.value)
    }

    @Test
    fun serverVoiceStartLeavesRadio() = runTest {
        val r = rig()
        r.controller.raise("a", "c")
        runCurrent()
        r.controller.onServerVoiceStarted()
        runCurrent()
        assertEquals(1, r.link.count("leave"))
        assertEquals(RadioState.Idle, r.controller.state.value)
    }

    @Test
    fun leaveWhileLostStopsRejoining() = runTest {
        val r = rig()
        becomeLost(r)
        r.controller.leave()
        runCurrent()
        val joins = r.link.count("join")
        advanceTimeBy(200_000)
        runCurrent()
        assertEquals(joins, r.link.count("join"))
        assertEquals(RadioState.Idle, r.controller.state.value)
    }

    @Test
    fun setModeStoresAndForwards() = runTest {
        val r = rig()
        assertEquals(RadioMode.BALANCED, r.controller.mode.value)
        val seen = mutableListOf<RadioMode>()
        r.controller.onModeChanged = { seen += it }
        r.controller.setMode(RadioMode.RELIABLE)
        assertEquals(RadioMode.RELIABLE, r.controller.mode.value)
        assertEquals(listOf(RadioMode.RELIABLE), seen)
    }

    @Test
    fun leaveDuringInFlightJoinLeavesIdleAndNoStaleWrite() = runTest {
        val r = rig()
        r.link.joinResult = RadioLinkResult.Joined(hub)
        val gate = CompletableDeferred<Unit>()
        r.link.gate = gate
        r.controller.join("a", "c")
        runCurrent()
        r.controller.leave()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(RadioState.Idle, r.controller.state.value)
    }

    @Test
    fun linkCallsNeverOverlap() = runTest {
        val r = rig()
        val gate = CompletableDeferred<Unit>()
        r.link.gate = gate
        r.controller.join("a", "c")
        runCurrent()
        r.controller.leave()
        r.controller.raise("a", "c")
        runCurrent()
        gate.complete(Unit)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, r.link.maxInFlight)
        assertEquals(true, r.link.calls.contains("leave"))
    }

    @Test
    fun doubleReRaiseHostsOnce() = runTest {
        val r = rig()
        becomeLost(r)
        val gate = CompletableDeferred<Unit>()
        r.link.gate = gate
        r.controller.reRaise()
        runCurrent()
        r.controller.reRaise()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, r.link.count("host"))
        assertEquals(RadioState.Hub("c"), r.controller.state.value)
    }

    @Test
    fun raiseWhileHubIsIgnored() = runTest {
        val r = rig()
        r.controller.raise("a", "c")
        runCurrent()
        val before = r.link.calls.toList()
        r.controller.raise("a", "c")
        r.controller.join("a", "c")
        runCurrent()
        assertEquals(before, r.link.calls)
        assertEquals(RadioState.Hub("c"), r.controller.state.value)
    }

    @Test
    fun failedDuringRejoinKeepsRetrying() = runTest {
        val r = rig()
        becomeLost(r)
        r.link.joinResult = RadioLinkResult.Failed("x")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(RadioState.Lost("c", 120_000), r.controller.state.value)
        assertEquals(true, r.link.count("join") > 10)
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(RadioState.Error("c", RadioError.NOT_FOUND), r.controller.state.value)
    }

    @Test
    fun lastRejoinAttemptCappedToWindow() = runTest {
        val r = rig()
        becomeLost(r)
        advanceTimeBy(130_000)
        runCurrent()
        assertEquals(true, r.link.joinAttempts.all { (at, timeout) -> at + timeout <= 120_000L })
        assertEquals(true, r.link.joinAttempts.isNotEmpty())
    }

    @Test
    fun fullPhaseLeavesAndReportsFull() = runTest {
        val r = rig()
        r.link.joinResult = RadioLinkResult.Joined(hub)
        r.controller.join("a", "c")
        runCurrent()
        r.controller.onClientPhase(RadioClientPhase.FULL)
        runCurrent()
        assertEquals(1, r.link.count("leave"))
        assertEquals(RadioState.Error("c", RadioError.FULL), r.controller.state.value)
    }

    @Test
    fun duplicateHubLeavesAndJoinsExisting() = runTest {
        val r = rig()
        r.controller.raise("a", "c")
        runCurrent()
        assertEquals(RadioState.Hub("c"), r.controller.state.value)
        r.link.joinResult = RadioLinkResult.Joined(hub)
        r.controller.onDuplicateHub()
        runCurrent()
        assertEquals(listOf("join", "host", "leave", "join"), r.link.calls)
        assertEquals(RadioState.Client("c", RadioClientPhase.JOINING), r.controller.state.value)
    }

    @Test
    fun duplicateHubIgnoredWhenNotHub() = runTest {
        val r = rig()
        r.link.joinResult = RadioLinkResult.Joined(hub)
        r.controller.onDuplicateHub()
        runCurrent()
        assertEquals(emptyList(), r.link.calls)
        assertEquals(RadioState.Idle, r.controller.state.value)
        r.controller.join("a", "c")
        runCurrent()
        val before = r.link.calls.toList()
        r.controller.onDuplicateHub()
        runCurrent()
        assertEquals(before, r.link.calls)
        assertEquals(RadioState.Client("c", RadioClientPhase.JOINING), r.controller.state.value)
    }

    @Test
    fun runtimeFailureLeavesLinkAndReportsError() = runTest {
        val r = rig()
        r.controller.raise("a", "c")
        runCurrent()
        r.controller.onRuntimeFailure(RadioError.AUDIO_UNAVAILABLE)
        runCurrent()
        assertEquals(RadioState.Error("c", RadioError.AUDIO_UNAVAILABLE), r.controller.state.value)
        assertEquals(1, r.link.count("leave"))
        r.controller.onRuntimeFailure(RadioError.WIFI_FAILED)
        runCurrent()
        assertEquals(RadioState.Error("c", RadioError.AUDIO_UNAVAILABLE), r.controller.state.value)
        assertEquals(1, r.link.count("leave"))
    }
}
