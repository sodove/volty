package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyVisibleLaunchBoundaryTest {
    private val id="10000000-0000-0000-0000-000000000001"
    @Test fun parentCancellationFencesCallbacksBeforeHeldUiChildCompletesAndPreservesBorrowedForeground()=runTest {
        val ui=SupervisorJob(coroutineContext[Job]);val release=CompletableDeferred<Unit>()
        val scope=CoroutineScope(coroutineContext+ui)
        scope.launch(start=CoroutineStart.UNDISPATCHED) {withContext(NonCancellable) {release.await()}}
        val gate=VoiceForegroundGate({true},{id});val ticket=gate.request(Any()) {};assertTrue(gate.acknowledge(ticket,Any()))
        var calls=0
        val boundary=NearbyVisibleLaunchBoundary(scope,{true},{calls++;true},{calls++;ChannelSharingForegroundLaunch.STARTED})
        try {
            ui.cancel();assertFalse(ui.isCompleted)
            assertFalse(boundary.available);assertFalse(boundary.launchVoice(ticket))
            assertEquals(ChannelSharingForegroundLaunch.REJECTED,boundary.launchLocation(ticket));assertEquals(0,calls)
            runCurrent();assertSame(ticket,gate.current.value);assertSame(ticket,gate.ready.value)
        } finally {release.complete(Unit);boundary.close();ui.cancel();runCurrent()}
    }
    @Test fun explicitCloseWithdrawsBothCallbackKindsWithoutCancellingParentOrBorrowedTicket()=runTest {
        val ui=SupervisorJob(coroutineContext[Job]);val scope=CoroutineScope(coroutineContext+ui)
        val gate=VoiceForegroundGate({true},{id});val ticket=gate.request(Any()) {};assertTrue(gate.acknowledge(ticket,Any()))
        var voices=0;var locations=0
        val boundary=NearbyVisibleLaunchBoundary(scope,{true},{voices++;true},{locations++;ChannelSharingForegroundLaunch.STARTED})
        try {
            assertTrue(boundary.launchVoice(ticket));assertEquals(ChannelSharingForegroundLaunch.STARTED,boundary.launchLocation(ticket))
            boundary.close();runCurrent()
            assertFalse(boundary.launchVoice(ticket));assertEquals(ChannelSharingForegroundLaunch.REJECTED,boundary.launchLocation(ticket))
            assertEquals(1,voices);assertEquals(1,locations);assertTrue(ui.isActive)
            assertSame(ticket,gate.current.value);assertSame(ticket,gate.ready.value)
        } finally {boundary.close();ui.cancel();runCurrent()}
    }
    @Test fun visibilityLossAndCallbackExceptionsNeverEscapeOrAdmitFurtherLaunches()=runTest {
        val ui=SupervisorJob(coroutineContext[Job]);var visible=false;var calls=0
        val ticket=VoiceForegroundGate({true},{id}).request(Any()) {}
        val boundary=NearbyVisibleLaunchBoundary(CoroutineScope(coroutineContext+ui),{visible},
            {calls++;error("Private platform details")},{calls++;error("Private platform details")})
        try {
            assertFalse(boundary.launchVoice(ticket));assertEquals(0,calls)
            visible=true;assertFalse(boundary.launchVoice(ticket))
            assertEquals(ChannelSharingForegroundLaunch.REJECTED,boundary.launchLocation(ticket));assertEquals(2,calls)
            assertTrue(ui.isActive)
        } finally {boundary.close();ui.cancel();runCurrent()}
    }
    @Test fun rejectedBoundaryDoesNotAllocateChildrenForMissingOrCancelledParent()=runTest {
        val cancelled=Job().apply {cancel()};val before=cancelled.children.toList()
        val context=StandardTestDispatcher(testScheduler)
        assertFailsWith<IllegalArgumentException> {
            NearbyVisibleLaunchBoundary(CoroutineScope(context+cancelled),{error("No visibility")},{error("No launch")},{error("No launch")})
        }
        assertEquals(before,cancelled.children.toList())
        assertFailsWith<IllegalArgumentException> {
            // CoroutineScope(context) adds a Job, so it cannot exercise the missing-owner contract.
            val ownerless = object : CoroutineScope { override val coroutineContext = context }
            NearbyVisibleLaunchBoundary(ownerless,{error("No visibility")},{error("No launch")},{error("No launch")})
        }
    }
}
