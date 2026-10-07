package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import ru.sodovaya.volty.data.nearby.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.domain.social.*
import ru.sodovaya.volty.domain.voice.*
import ru.sodovaya.volty.domain.nearby.*
import ru.sodovaya.volty.domain.location.*
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import kotlin.test.*
import ru.sodovaya.volty.presentation.root.nearby.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceConversationStartupTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val channel = "20000000-0000-0000-0000-000000000002"
    private val first = VoiceOperationId("30000000-0000-0000-0000-000000000003")
    private val second = VoiceOperationId("30000000-0000-0000-0000-000000000005")
    private val phone = VoiceAudioPreferences(VoiceInputPreference.PHONE, VoiceOutputPreference.HEADSET)

    private fun sharingController(f: Fixture,sharing: SharingFixture,guard: NearbySharingSwitchGuard):NearbySharingController {
        var nonce=0
        return NearbySharingController(f.repository,f.owner,sharing.startup,guard,f.dispatcher,
            {"a0000000-0000-0000-0000-${(++nonce).toString().padStart(12,'0')}"})
    }
    private fun sharingBoundary(f: Fixture,sharing: SharingFixture,ui: Job)=NearbyVisibleLaunchBoundary(
        CoroutineScope(f.test.backgroundScope.coroutineContext+ui),{true},f::launch,{ticket ->
            sharing.launches++;sharing.foreground.authority.acknowledge(ticket,Any());ChannelSharingForegroundLaunch.STARTED
        })

    @Test fun processSharingNeedsSeparateLocationPermissionAndHidePreservesVoiceNavigationAndAccount()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f)
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val root=sharingController(f,sharing,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=sharingBoundary(f,sharing,ui)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            assertTrue(sharing.source.issued.isEmpty());assertEquals(0,sharing.launches)
            val denied=assertNotNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR));runCurrent()
            assertEquals(denied,assertNotNull(root.state.value.pending).id)
            assertTrue(sharing.source.issued.isEmpty());assertTrue(f.sharingAuthorizations.isEmpty())
            root.begin(denied,false,boundary);runCurrent()
            assertEquals(NearbySharingError.LOCATION_DENIED,root.state.value.error);assertTrue(assertNotNull(f.runtime).state.value.wanted)
            val request=assertNotNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR));runCurrent()
            root.begin(request,true,boundary);runCurrent()
            assertEquals(1,sharing.launches);assertEquals(listOf(true),sharing.source.issued)
            assertTrue(root.state.value.active);assertTrue(root.state.value.wanted);assertEquals(request,root.state.value.generation)
            assertEquals(ChannelSharingMode.LOCATION,root.state.value.mode);assertEquals(ChannelSharingTtl.HALF_HOUR,root.state.value.ttl)
            ui.cancel();boundary.close();runCurrent();assertTrue(root.state.value.active)
            f.time=101;sharing.source.emitFix(1001,101);runCurrent();assertEquals(listOf(0L),f.packetSequences)
            root.hide(first,request);runCurrent()
            assertFalse(root.state.value.wanted);assertFalse(root.state.value.active);assertFalse(root.state.value.serverConfirmationPending)
            assertEquals(setOf(LocationConsumer.NAVIGATION),sharing.source.state.value.demands)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertNotNull(f.gate.current.value)
            assertEquals(0,f.closes);assertTrue(account.job.isActive)
        } finally {boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun processSharingOldPermissionAndOldHideCannotAffectReplacementVoiceTrip()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f)
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val root=sharingController(f,sharing,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=sharingBoundary(f,sharing,ui)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val old=assertNotNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR));runCurrent()
            f.owner.start(account,channel,second,replace=true,readPreferences={phone},launch=f::launch);runCurrent()
            root.begin(old,true,boundary);runCurrent();assertEquals(0,sharing.launches);assertTrue(sharing.source.issued.isEmpty())
            val fresh=assertNotNull(root.request(second,ChannelSharingMode.LOCATION,ChannelSharingTtl.TWO_HOURS));runCurrent()
            root.begin(fresh,true,boundary);runCurrent();assertTrue(root.state.value.active);val ticket=sharing.foreground.current.value
            assertEquals(ChannelSharingTtl.TWO_HOURS,root.state.value.ttl)
            root.hide(first,old);root.retryCleanup(first,old);runCurrent()
            assertTrue(root.state.value.active);assertEquals(fresh,root.state.value.generation);assertSame(ticket,sharing.foreground.current.value)
            assertEquals(listOf(true),sharing.source.issued);assertEquals(second,assertNotNull(f.runtime).state.value.operationId)
        } finally {boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun processSharingCancelledHeldAcquisitionCannotLaunchLateOrStopBorrowedVoice()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f)
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val root=sharingController(f,sharing,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=sharingBoundary(f,sharing,ui)
        val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();var returned=false
        sharing.beforeAcquire={withContext(NonCancellable) {entered.complete(Unit);release.await();returned=true}}
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val request=assertNotNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR));runCurrent()
            root.begin(request,true,boundary);runCurrent();assertTrue(entered.isCompleted);assertTrue(root.state.value.preparing)
            root.cancelRequest(request);runCurrent();assertFalse(root.state.value.wanted);assertTrue(root.state.value.localCleanupPending)
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(0,sharing.launches);assertTrue(sharing.source.issued.isEmpty());assertTrue(f.sharingAuthorizations.isEmpty())
            assertFalse(root.state.value.localCleanupPending);assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertTrue(account.job.isActive)
        } finally {release.complete(Unit);runCurrent();boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun processSharingFailedRemoteCleanupIsVisibleAndExactRetryCannotHideLaterTripInSameVoice()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f)
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val root=sharingController(f,sharing,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=sharingBoundary(f,sharing,ui)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val old=assertNotNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR));runCurrent()
            root.begin(old,true,boundary);runCurrent();assertTrue(root.state.value.active)
            f.sharingRevokeStatus=HttpStatusCode.ServiceUnavailable;root.hide(first,old);runCurrent()
            assertFalse(root.state.value.wanted);assertFalse(root.state.value.localCleanupPending)
            assertTrue(root.state.value.serverConfirmationPending);assertEquals(NearbySharingError.CLEANUP_REQUIRED,root.state.value.error)
            assertEquals(1,sharing.journal.rows.size);assertTrue(assertNotNull(f.runtime).state.value.wanted)
            f.sharingRevokeStatus=HttpStatusCode.OK;root.retryCleanup(first,old);runCurrent()
            assertFalse(root.state.value.serverConfirmationPending);assertNull(root.state.value.error);assertTrue(sharing.journal.rows.isEmpty())
            val fresh=assertNotNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.TWO_HOURS));runCurrent()
            root.begin(fresh,true,boundary);runCurrent();assertTrue(root.state.value.active);val ticket=sharing.foreground.current.value
            root.hide(first,old);root.retryCleanup(first,old);runCurrent()
            assertTrue(root.state.value.active);assertEquals(fresh,root.state.value.generation);assertSame(ticket,sharing.foreground.current.value)
            assertEquals(listOf(true,false,true),sharing.source.issued);assertEquals(1,f.published);assertTrue(account.job.isActive)
        } finally {boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun processSharingLostVisibleActivityDuringHeldAcquisitionCannotLaunchGpsAfterFactoryReturns()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f)
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val root=sharingController(f,sharing,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=sharingBoundary(f,sharing,ui)
        val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();var returned=false
        sharing.beforeAcquire={withContext(NonCancellable) {entered.complete(Unit);release.await();returned=true}}
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val request=assertNotNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR));runCurrent()
            root.begin(request,true,boundary);runCurrent();assertTrue(entered.isCompleted)
            ui.cancel();boundary.close();runCurrent();release.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(0,sharing.launches);assertTrue(sharing.source.issued.isEmpty());assertTrue(f.sharingAuthorizations.isEmpty())
            assertFalse(root.state.value.wanted);assertFalse(root.state.value.active);assertEquals(NearbySharingError.NOT_VISIBLE,root.state.value.error)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
        } finally {release.complete(Unit);runCurrent();boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun processSharingRetiredAccountClearsPresentationBeforeUnrelatedAccountChildCompletes()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f)
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val root=sharingController(f,sharing,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=sharingBoundary(f,sharing,ui)
        val release=CompletableDeferred<Unit>()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val request=assertNotNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR));runCurrent()
            root.begin(request,true,boundary);runCurrent();assertTrue(root.state.value.active)
            account.scope().launch(start=CoroutineStart.UNDISPATCHED) {withContext(NonCancellable) {release.await()}}
            account.job.cancel();runCurrent();assertFalse(account.job.isCompleted)
            assertEquals(NearbySharingView(),root.state.value);assertEquals(setOf(LocationConsumer.NAVIGATION),sharing.source.state.value.demands)
            root.begin(request,true,boundary);runCurrent();assertEquals(1,sharing.launches)
            assertNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR))
        } finally {release.complete(Unit);runCurrent();boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun processSharingRejectsActualDirectCallBeforeEntropyAndClosingItsOwnerPreservesCall()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f);var entropy=0
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        val root=NearbySharingController(f.repository,f.owner,sharing.startup,guard,f.dispatcher,{entropy++;f.key})
        try {
            val account=f.login();f.callMode=true
            f.owner.start(account,VoiceConversationTarget.OutgoingCall(channel,f.peer,f.key),first,readPreferences={phone},launch=f::launch);runCurrent()
            assertTrue(assertNotNull(f.runtime).state.value.wanted);val ticket=f.gate.current.value
            assertNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR));runCurrent()
            assertEquals(0,entropy);assertEquals(0,sharing.launches);assertTrue(sharing.source.issued.isEmpty())
            root.close();runCurrent();assertTrue(account.job.isActive);assertTrue(f.repository.applicationScope().coroutineContext[Job]!!.isActive)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertSame(ticket,f.gate.current.value)
        } finally {root.close();f.close()}
    }

    @Test fun processSharingNativeCleanupFailureDoesNotPretendServerAcknowledgementIsStillPending()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f)
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val root=sharingController(f,sharing,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=sharingBoundary(f,sharing,ui)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val request=assertNotNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR));runCurrent()
            root.begin(request,true,boundary);runCurrent();assertTrue(root.state.value.active)
            sharing.source.failStop=true;root.hide(first,request);runCurrent()
            assertTrue(sharing.journal.rows.isEmpty());assertTrue(f.paths.any {it.endsWith("/revoke")})
            assertTrue(root.state.value.localCleanupPending);assertFalse(root.state.value.serverConfirmationPending)
            assertEquals(NearbySharingError.CLEANUP_REQUIRED,root.state.value.error)
            sharing.source.failStop=false;root.retryCleanup(first,request);runCurrent()
            assertFalse(root.state.value.localCleanupPending);assertNull(root.state.value.error)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
        } finally {sharing.source.failStop=false;boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun processSharingFailedDurableAckRemainsServerPendingAfterActualLocalGpsRelease()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f)
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val root=sharingController(f,sharing,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=sharingBoundary(f,sharing,ui)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val request=assertNotNull(root.request(first,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR));runCurrent()
            root.begin(request,true,boundary);runCurrent();sharing.journal.failAcknowledge=true
            root.hide(first,request);runCurrent()
            assertFalse(root.state.value.localCleanupPending);assertTrue(root.state.value.serverConfirmationPending)
            assertEquals(1,sharing.journal.rows.size);assertEquals(NearbySharingError.CLEANUP_REQUIRED,root.state.value.error)
            assertEquals(setOf(LocationConsumer.NAVIGATION),sharing.source.state.value.demands)
            sharing.journal.failAcknowledge=false;root.retryCleanup(first,request);runCurrent()
            assertFalse(root.state.value.serverConfirmationPending);assertTrue(sharing.journal.rows.isEmpty());assertNull(root.state.value.error)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertEquals(0,f.closes);assertTrue(account.job.isActive)
        } finally {sharing.journal.failAcknowledge=false;boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun rootAcceptedFriendCallNeedsExplicitPermissionAndUsesTypedActualCallStartup()=runTest {
        val f=Fixture(this);val root=voiceController(f,Switches());val ui=SupervisorJob(backgroundScope.coroutineContext[Job])
        val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();f.callMode=true
            val friend=FriendSummary(FriendshipId("friendship"),SocialUserId(f.peer),"Rider",FriendshipState.ACCEPTED)
            val request=assertNotNull(root.requestFriendCall(account,friend));runCurrent()
            assertEquals("Rider",assertNotNull(root.state.value.pending).label)
            assertEquals(NearbyConversationKind.CALL,assertNotNull(root.state.value.pending).kind)
            assertTrue(f.paths.isEmpty());assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty())
            // The generated call identity is distinct from the permission/conversation operation.
            f.callDestination="80000000-0000-0000-0000-000000000002"
            root.begin(request,true,boundary);runCurrent()
            val target=assertIs<VoiceConversationTarget.OutgoingCall>(f.owner.state.value.target)
            assertEquals(f.peer,target.calleeId);assertEquals(f.callDestination,target.destinationId)
            assertEquals(request,target.idempotencyKey);assertNotEquals(request,target.destinationId)
            assertEquals(1,f.launches);assertEquals(1,f.published);assertEquals(listOf(phone),f.allocated)
            assertTrue(f.paths.contains("/v2/nearby/calls/${f.callDestination}/create"))
            assertTrue(f.paths.contains("/v2/nearby/calls/${f.callDestination}/resume"))
            assertEquals(1,f.rosters.size);assertTrue(f.sharingAuthorizations.isEmpty())
            assertEquals(NearbyConversationKind.CALL,root.state.value.kind)
            assertEquals(1,assertNotNull(root.state.value.callRoster).connectedCount)
            root.disconnect(VoiceOperationId(request));runCurrent()
            assertFalse(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
        } finally {boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun rootFriendCallRejectsUnacceptedSelfMalformedAndRetiredAuthorityBeforeEntropyOrHttp()=runTest {
        val f=Fixture(this);var entropy=0
        val root=NearbyVoiceController(f.repository,f.owner,f.dispatcher,{entropy++;f.key},{phone},Switches())
        try {
            val account=f.login();val friend=FriendSummary(FriendshipId("friendship"),SocialUserId(f.peer),"Rider",FriendshipState.ACCEPTED)
            for(state in listOf(FriendshipState.REQUEST_SENT,FriendshipState.REQUEST_RECEIVED,FriendshipState.BLOCKED)) {
                assertNull(root.requestFriendCall(account,friend.copy(state=state)))
            }
            assertNull(root.requestFriendCall(account,friend.copy(userId=account.userId)))
            assertNull(root.requestFriendCall(account,friend.copy(userId=SocialUserId("invalid"))))
            account.job.cancel();assertNull(root.requestFriendCall(account,friend));runCurrent()
            assertEquals(0,entropy);assertTrue(f.paths.isEmpty());assertEquals(0,f.launches);assertNull(f.runtime)
        } finally {root.close();f.close()}
    }

    @Test fun rootIncomingCallCancellationKeepsCurrentConversationAndOnlyFreshAcceptanceIssuesHttp()=runTest {
        val f=Fixture(this);val guard=Switches();val root=voiceController(f,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();val original=assertNotNull(root.requestChannel(account,channel,"Channel"));runCurrent()
            root.begin(original,true,boundary);runCurrent();val ticket=f.gate.current.value;val withdrawn=guard.withdrawn.size
            f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
            val ring=DirectCallSnapshot(channel,f.peer,actor,DirectCallPhase.RINGING,2,45100,null)
            val cancelled=assertNotNull(root.requestIncomingCall(account,ring,"Incoming"));runCurrent()
            root.cancelRequest(cancelled);runCurrent();root.begin(cancelled,true,boundary);runCurrent()
            assertSame(ticket,f.gate.current.value);assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertEquals(withdrawn,guard.withdrawn.size);assertFalse(f.paths.any {"/calls/" in it})
            val accepted=assertNotNull(root.requestIncomingCall(account,ring,"Incoming"));runCurrent()
            root.begin(accepted,true,boundary);runCurrent()
            assertIs<VoiceConversationTarget.IncomingCall>(f.owner.state.value.target)
            assertTrue(f.paths.contains("/v2/nearby/calls/$channel/accept"),
                "Issued=${f.paths}; startup=${f.owner.state.value.phase}; runtime=${f.runtime?.state?.value?.phase}; failure=${root.state.value.error}")
            assertEquals(2,f.published)
            assertEquals("Incoming",root.state.value.label);assertTrue(f.sharingAuthorizations.isEmpty())
            assertEquals(NearbyConversationKind.CALL,root.state.value.kind)
            root.disconnect(VoiceOperationId(original));runCurrent()
            assertEquals(VoiceOperationId(accepted),f.owner.state.value.operationId);assertTrue(assertNotNull(f.runtime).state.value.wanted)
        } finally {boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun rootIncomingCallRejectsForeignOutgoingTerminalAndIncoherentSnapshotsBeforeEntropy()=runTest {
        val f=Fixture(this);var entropy=0
        val root=NearbyVoiceController(f.repository,f.owner,f.dispatcher,{entropy++;f.key},{phone},Switches())
        try {
            val account=f.login();val ring=DirectCallSnapshot(channel,f.peer,actor,DirectCallPhase.RINGING,2,45100,null)
            for(invalid in listOf(ring.copy(calleeId=f.peer),ring.copy(callerId=actor),ring.copy(callId="invalid"),
                ring.copy(phase=DirectCallPhase.ACCEPTED),ring.copy(phase=DirectCallPhase.CANCELLED),
                ring.copy(revision=-1),ring.copy(outcome=DirectCallOutcome.CALLEE_DECLINED))) {
                assertNull(root.requestIncomingCall(account,invalid,"Incoming"))
            }
            runCurrent();assertEquals(0,entropy);assertTrue(f.paths.isEmpty());assertEquals(0,f.launches);assertNull(root.state.value.pending)
        } finally {root.close();f.close()}
    }

    @Test fun rootExpiredIncomingCallCannotStopCurrentConversationAfterPermissionReturns()=runTest {
        val f=Fixture(this);val guard=Switches();val root=voiceController(f,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();val original=assertNotNull(root.requestChannel(account,channel,"Channel"));runCurrent()
            root.begin(original,true,boundary);runCurrent();val ticket=f.gate.current.value;val withdrawn=guard.withdrawn.size
            f.callMode=true;f.incomingMode=true;f.statusPhase="ENDED"
            val ring=DirectCallSnapshot(channel,f.peer,actor,DirectCallPhase.RINGING,2,45100,null)
            val request=assertNotNull(root.requestIncomingCall(account,ring,"Expired"));runCurrent()
            root.begin(request,true,boundary);runCurrent()
            assertSame(ticket,f.gate.current.value);assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertEquals(withdrawn,guard.withdrawn.size);assertEquals(1,f.published)
            assertEquals(listOf("/v2/nearby/calls/$channel"),f.paths.filter {"/calls/" in it})
            assertEquals(NearbyVoiceError.UNAVAILABLE,root.state.value.error)
        } finally {boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun rootIncomingCallMissingOrExpiredRelativeCreditPreservesCurrentVoice()=runTest {
        val f=Fixture(this);val guard=Switches();val root=voiceController(f,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();val original=assertNotNull(root.requestChannel(account,channel,"Channel"));runCurrent()
            root.begin(original,true,boundary);runCurrent();val ticket=f.gate.current.value;val withdrawn=guard.withdrawn.size
            f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
            val ring=DirectCallSnapshot(channel,f.peer,actor,DirectCallPhase.RINGING,2,45100,null)
            for(missing in listOf(true,false)) {
                f.statusRingCredit=if(missing) null else 1000;f.statusElapsed=if(missing) 0 else 1000
                val request=assertNotNull(root.requestIncomingCall(account,ring,"Incoming"));runCurrent()
                root.begin(request,true,boundary);runCurrent()
                assertSame(ticket,f.gate.current.value);assertTrue(assertNotNull(f.runtime).state.value.wanted)
                assertEquals(withdrawn,guard.withdrawn.size);assertEquals(1,f.published)
                assertEquals(NearbyVoiceError.UNAVAILABLE,root.state.value.error)
            }
            assertEquals(listOf("/v2/nearby/calls/$channel","/v2/nearby/calls/$channel"),f.paths.filter {"/calls/" in it})
        } finally {boundary.close();ui.cancel();root.close();f.close()}
    }

    @Test fun rootIncomingCallLateStatusAfterReplacementCannotWithdrawOriginalOrAcceptObsoleteRequest()=runTest {
        val f=Fixture(this);val guard=Switches();val root=voiceController(f,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();val original=assertNotNull(root.requestChannel(account,channel,"Channel"));runCurrent()
            root.begin(original,true,boundary);runCurrent();val ticket=f.gate.current.value;val withdrawn=guard.withdrawn.size
            f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING";f.statusRelease=CompletableDeferred()
            val ring=DirectCallSnapshot(channel,f.peer,actor,DirectCallPhase.RINGING,2,45100,null)
            val incoming=assertNotNull(root.requestIncomingCall(account,ring,"Incoming"));runCurrent()
            root.begin(incoming,true,boundary);runCurrent();assertTrue(f.statusEntered.isCompleted)
            val replacement=assertNotNull(root.requestChannel(account,channel,"Latest"));runCurrent()
            f.statusRelease!!.complete(Unit);runCurrent();assertTrue(f.statusHandlerFinished)
            assertSame(ticket,f.gate.current.value);assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertEquals(withdrawn,guard.withdrawn.size);assertEquals(replacement,assertNotNull(root.state.value.pending).id)
            assertEquals(listOf("/v2/nearby/calls/$channel"),f.paths.filter {"/calls/" in it})
            root.begin(replacement,true,boundary);runCurrent()
            assertEquals(VoiceOperationId(replacement),f.owner.state.value.operationId);assertEquals(2,f.published)
            assertEquals("Latest",root.state.value.label);assertNull(root.state.value.callRoster)
        } finally {f.statusRelease?.complete(Unit);runCurrent();boundary.close();ui.cancel();root.close();f.close()}
    }

    private inner class SharingFixture(val f: Fixture) {
        val source=CaptureLocationRepository()
        val foreground=CaptureForegroundPort()
        val journal=RecordingRevokeJournal()
        var beforeAcquire: suspend ()->Unit={}
        var launches=0
        val startup=ChannelSharingStartup(f.client,"https://example.test/v2/nearby",f.dispatcher,source,
            NearbyLocationDemandCoordinator(source),foreground,{f.time},{f.time+900},{EarnedTelemetry()},journal,{f.peer}) {account ->
            beforeAcquire();f.acquire(account);assertNotNull(f.boundRuntime)
        }
        suspend fun start(account: SocialAuthenticatedAccount,generation: String=f.key):ChannelSharingStartHandle {
            val state=assertNotNull(f.runtime).state.value
            return startup.start(account,VoiceSessionTag(assertNotNull(state.sessionId),state.generation),generation,
                ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR) {ticket ->
                launches++;foreground.authority.acknowledge(ticket,Any());ChannelSharingForegroundLaunch.STARTED
            }
        }
    }

    @Test fun actualSharingSwitchGuardWithdrawsPositionSynchronouslyAndWaitsForOriginalRemoteRevoke()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f);var handle: ChannelSharingStartHandle?=null
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val started=sharing.start(account);handle=started;assertTrue(started.result.await());runCurrent()
            assertFalse(guard.remember(account,second,started));assertTrue(guard.remember(account,first,started))
            assertTrue(guard.requiresConfirmation(first));assertFalse(guard.requiresConfirmation(second))
            f.time=101;sharing.source.emitFix(1001,101);runCurrent();assertEquals(listOf(0L),f.packetSequences)
            f.sharingRevokeRelease=CompletableDeferred()
            guard.withdrawSharing(first)
            assertFalse(started.state.value.wanted);assertNull(assertNotNull(started.capture.value).packet.value)
            assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertFalse(guard.awaitSharingCleanup(first));runCurrent();assertTrue(f.sharingRevokeEntered.isCompleted)
            f.sharingRevokeRelease!!.complete(Unit);runCurrent()
            assertTrue(guard.awaitSharingCleanup(first));assertFalse(guard.requiresConfirmation(first))
            assertEquals(setOf(LocationConsumer.NAVIGATION),sharing.source.state.value.demands)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
        } finally {f.sharingRevokeRelease?.complete(Unit);runCurrent();handle?.close();f.close()}
    }

    @Test fun actualSharingSwitchGuardCannotCallHeldPreparationCleanOrLaunchItAfterWithdrawal()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f);var handle: ChannelSharingStartHandle?=null
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        sharing.beforeAcquire={withContext(NonCancellable) {entered.complete(Unit);release.await()}}
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val started=sharing.start(account);handle=started;runCurrent();assertTrue(entered.isCompleted)
            assertTrue(guard.remember(account,first,started));assertTrue(guard.requiresConfirmation(first))
            guard.withdrawSharing(first);assertFalse(guard.awaitSharingCleanup(first));assertTrue(started.state.value.preparing)
            release.complete(Unit);assertFalse(started.result.await());runCurrent()
            assertTrue(guard.awaitSharingCleanup(first));assertEquals(0,sharing.launches)
            assertTrue(sharing.source.issued.isEmpty());assertTrue(f.sharingAuthorizations.isEmpty())
            assertTrue(assertNotNull(f.runtime).state.value.wanted)
        } finally {release.complete(Unit);runCurrent();handle?.close();f.close()}
    }

    @Test fun actualSharingSwitchGuardDoesNotTreatRemoteRevokeAsNativeCleanupSuccess()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f);var handle: ChannelSharingStartHandle?=null
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val started=sharing.start(account);handle=started;assertTrue(started.result.await());runCurrent()
            assertTrue(guard.remember(account,first,started));sharing.source.failStop=true
            guard.withdrawSharing(first);runCurrent();assertFalse(guard.awaitSharingCleanup(first));runCurrent()
            assertIs<ChannelSharingReply.Negative>(assertNotNull(started.originalRevoke.value).await())
            assertTrue(started.state.value.nativeCleanupPending)
            assertFalse(guard.waitSharingCleanup(first))
            sharing.source.failStop=false;assertTrue(guard.awaitSharingCleanup(first));assertTrue(account.job.isActive)
            assertTrue(assertNotNull(f.runtime).state.value.wanted)
        } finally {sharing.source.failStop=false;runCurrent();handle?.close();f.close()}
    }

    @Test fun actualSharingSwitchGuardOldWithdrawalCannotHideNewConversationSharing()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f);val handles=mutableListOf<ChannelSharingStartHandle>()
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val old=sharing.start(account);handles+=old;assertTrue(old.result.await());runCurrent();assertTrue(guard.remember(account,first,old))
            guard.withdrawSharing(first);runCurrent();assertTrue(guard.awaitSharingCleanup(first))
            f.owner.start(account,channel,second,replace=true,readPreferences={phone},launch=f::launch);runCurrent()
            val fresh=sharing.start(account,f.peer);handles+=fresh;assertTrue(fresh.result.await());runCurrent()
            assertTrue(guard.remember(account,second,fresh));val ticket=sharing.foreground.current.value
            guard.withdrawSharing(first);assertTrue(guard.awaitSharingCleanup(first));runCurrent()
            assertTrue(fresh.state.value.wanted);assertTrue(fresh.state.value.active)
            assertSame(ticket,sharing.foreground.current.value);assertTrue(guard.requiresConfirmation(second))
            assertEquals(second,assertNotNull(f.runtime).state.value.operationId)
        } finally {handles.forEach {it.close()};f.close()}
    }

    @Test fun actualSharingSwitchGuardFailedRemoteRevokeRemainsBlockedUntilDurableRecoverySucceeds()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f);var handle: ChannelSharingStartHandle?=null
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val started=sharing.start(account);handle=started;assertTrue(started.result.await());runCurrent()
            assertTrue(guard.remember(account,first,started));f.sharingRevokeStatus=HttpStatusCode.ServiceUnavailable
            guard.withdrawSharing(first);runCurrent()
            assertTrue(started.close());assertIs<ChannelSharingReply.Failed>(assertNotNull(started.originalRevoke.value).await())
            assertFalse(guard.awaitSharingCleanup(first));assertEquals(1,sharing.journal.rows.size)
            val before=f.paths.count {it.endsWith("/revoke")};f.sharingRevokeStatus=HttpStatusCode.OK
            assertTrue(guard.awaitSharingCleanup(first));runCurrent()
            assertEquals(before+1,f.paths.count {it.endsWith("/revoke")});assertTrue(sharing.journal.rows.isEmpty())
            assertFalse(started.state.value.journalCleanupPending)
            assertTrue(started.close());assertFalse(started.state.value.journalCleanupPending)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
        } finally {handle?.close();f.close()}
    }

    @Test fun actualSharingSwitchGuardRemoteNegativeCannotHideFailedDurableAcknowledgement()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f);var handle: ChannelSharingStartHandle?=null
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val started=sharing.start(account);handle=started;assertTrue(started.result.await());runCurrent()
            assertTrue(guard.remember(account,first,started));sharing.journal.failAcknowledge=true
            guard.withdrawSharing(first);runCurrent();assertTrue(started.close())
            assertIs<ChannelSharingReply.Negative>(assertNotNull(started.originalRevoke.value).await())
            assertEquals(1,sharing.journal.rows.size);assertFalse(guard.awaitSharingCleanup(first))
            sharing.journal.failAcknowledge=false;assertTrue(guard.awaitSharingCleanup(first));assertTrue(sharing.journal.rows.isEmpty())
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
        } finally {sharing.journal.failAcknowledge=false;handle?.close();f.close()}
    }

    private class Switches: NearbyVoiceSwitchGuard {
        var confirm=false
        val withdrawn=mutableListOf<VoiceOperationId?>()
        val awaited=mutableListOf<VoiceOperationId?>()
        var reply: suspend ()->Boolean={true}
        override fun requiresConfirmation(operation: VoiceOperationId?)=confirm
        override fun withdrawSharing(operation: VoiceOperationId?) {withdrawn+=operation}
        override suspend fun awaitSharingCleanup(operation: VoiceOperationId?):Boolean {awaited+=operation;return reply()}
        override suspend fun waitSharingCleanup(operation: VoiceOperationId?)=awaitSharingCleanup(operation)
    }
    private fun voiceController(f: Fixture,switches: NearbyVoiceSwitchGuard):NearbyVoiceController {
        var nonce=0
        return NearbyVoiceController(f.repository,f.owner,f.dispatcher,
            {"80000000-0000-0000-0000-${(++nonce).toString().padStart(12,'0')}"},{phone},switches)
    }
    private fun launchBoundary(f: Fixture,ui: Job)=NearbyVisibleLaunchBoundary(
        CoroutineScope(f.test.backgroundScope.coroutineContext+ui),{true},f::launch,{ChannelSharingForegroundLaunch.REJECTED})

    @Test fun rootTripIncomingCallWaitsForActualHeldRevokeAndThenAcceptsWithoutSecondUserAttempt()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f);var handle: ChannelSharingStartHandle?=null
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val root=voiceController(f,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();val original=assertNotNull(root.requestChannel(account,channel,"Channel"));runCurrent()
            root.begin(original,true,boundary);runCurrent()
            val started=sharing.start(account);handle=started;assertTrue(started.result.await());runCurrent()
            assertTrue(guard.remember(account,VoiceOperationId(original),started))
            f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING";f.sharingRevokeRelease=CompletableDeferred()
            val ring=DirectCallSnapshot(channel,f.peer,actor,DirectCallPhase.RINGING,2,45100,null)
            val incoming=assertNotNull(root.requestIncomingCall(account,ring,"Incoming"));runCurrent()
            assertTrue(assertNotNull(root.state.value.pending).confirmationRequired)
            root.confirmSwitch(incoming);runCurrent();root.begin(incoming,true,boundary);runCurrent()
            assertTrue(f.sharingRevokeEntered.isCompleted);assertFalse(started.state.value.wanted)
            assertEquals(incoming,assertNotNull(root.state.value.starting).id);assertNull(root.state.value.error)
            assertEquals(1,f.published);assertFalse(f.paths.any {it.endsWith("/accept")})
            f.sharingRevokeRelease!!.complete(Unit);runCurrent()
            assertTrue(f.paths.contains("/v2/nearby/calls/$channel/accept"));assertEquals(2,f.published)
            assertNull(root.state.value.starting);assertEquals("Incoming",root.state.value.label)
            assertEquals(setOf(LocationConsumer.NAVIGATION),sharing.source.state.value.demands);assertTrue(account.job.isActive)
        } finally {f.sharingRevokeRelease?.complete(Unit);runCurrent();boundary.close();ui.cancel();root.close();handle?.close();f.close()}
    }

    @Test fun rootTripSwitchCancellationWithdrawsWaiterWhileOriginalRemoteCleanupStillFinishes()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f);var handle: ChannelSharingStartHandle?=null
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val root=voiceController(f,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();val original=assertNotNull(root.requestChannel(account,channel,"Channel"));runCurrent()
            root.begin(original,true,boundary);runCurrent()
            val started=sharing.start(account);handle=started;assertTrue(started.result.await());runCurrent()
            assertTrue(guard.remember(account,VoiceOperationId(original),started))
            f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING";f.sharingRevokeRelease=CompletableDeferred()
            val ring=DirectCallSnapshot(channel,f.peer,actor,DirectCallPhase.RINGING,2,45100,null)
            val incoming=assertNotNull(root.requestIncomingCall(account,ring,"Incoming"));runCurrent()
            root.confirmSwitch(incoming);runCurrent();root.begin(incoming,true,boundary);runCurrent()
            assertTrue(f.sharingRevokeEntered.isCompleted);assertNotNull(root.state.value.starting)
            val negative=assertNotNull(started.originalRevoke.value);assertFalse(negative.isCompleted)
            root.cancelRequest(incoming);runCurrent();assertNull(root.state.value.starting)
            assertFalse(negative.isCancelled);assertEquals(1,sharing.journal.rows.size)
            f.sharingRevokeRelease!!.complete(Unit);runCurrent()
            assertIs<ChannelSharingReply.Negative>(negative.await());assertTrue(sharing.journal.rows.isEmpty())
            assertTrue(started.close());assertFalse(f.paths.any {it.endsWith("/accept")});assertEquals(1,f.published)
            assertTrue(account.job.isActive);assertEquals(setOf(LocationConsumer.NAVIGATION),sharing.source.state.value.demands)
        } finally {f.sharingRevokeRelease?.complete(Unit);runCurrent();boundary.close();ui.cancel();root.close();handle?.close();f.close()}
    }

    @Test fun actualSharingCleanupWaiterCancellationCannotCancelHeldPreparationOrAdmitLateGps()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f);var handle: ChannelSharingStartHandle?=null
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        sharing.beforeAcquire={withContext(NonCancellable) {entered.complete(Unit);release.await()}}
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val started=sharing.start(account);handle=started;runCurrent();assertTrue(entered.isCompleted)
            assertTrue(guard.remember(account,first,started))
            val waiter=async {guard.waitSharingCleanup(first)};runCurrent()
            assertFalse(waiter.isCompleted);assertFalse(started.state.value.wanted);assertFalse(started.result.isCompleted)
            waiter.cancel();runCurrent();assertFalse(started.result.isCancelled)
            release.complete(Unit);assertFalse(started.result.await());runCurrent()
            assertTrue(guard.waitSharingCleanup(first));assertEquals(0,sharing.launches);assertTrue(sharing.source.issued.isEmpty())
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
        } finally {release.complete(Unit);runCurrent();handle?.close();f.close()}
    }

    @Test fun actualSharingCleanupWaiterKeepsNativeProducerOwnedUntilItsIssuedGpsStartReturns()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val sharing=SharingFixture(f);var handle: ChannelSharingStartHandle?=null
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        val release=CompletableDeferred<Unit>();sharing.source.startRelease=release
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val started=sharing.start(account);handle=started;runCurrent()
            assertEquals(listOf(true),sharing.source.issued);assertFalse(started.result.isCompleted)
            assertTrue(guard.remember(account,first,started))
            val waiter=async {guard.waitSharingCleanup(first)};runCurrent()
            assertFalse(waiter.isCompleted);assertFalse(started.state.value.wanted)
            release.complete(Unit);assertTrue(waiter.await());runCurrent()
            assertEquals(listOf(true,false),sharing.source.issued);assertEquals(1,sharing.launches)
            assertEquals(setOf(LocationConsumer.NAVIGATION),sharing.source.state.value.demands)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
        } finally {release.complete(Unit);runCurrent();handle?.close();f.close()}
    }

    @Test fun processVoiceRequestWaitsForExplicitPermissionAndSurvivesUiBoundaryDisposalAfterJoin()=runTest {
        val f=Fixture(this);val guard=Switches();val root=voiceController(f,guard);val ui=SupervisorJob(backgroundScope.coroutineContext[Job])
        val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();runCurrent()
            val request=assertNotNull(root.requestChannel(account,channel,"General"));runCurrent()
            assertEquals(request,root.state.value.pending?.id);assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty())
            root.begin(request,true,boundary);runCurrent()
            assertEquals(1,f.launches);assertEquals(1,f.published)
            assertEquals("General",root.state.value.label);assertNotNull(root.state.value.voice)
            assertFalse(assertNotNull(root.state.value.voice).transportConnected,"SDK calls alone are not connection evidence")
            ui.cancel();runCurrent();assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertNotNull(f.gate.current.value);assertEquals("General",root.state.value.label)
            assertTrue(account.job.isActive)
        } finally {boundary.close();root.close();ui.cancel();f.close()}
    }

    @Test fun latePermissionForReplacedRequestCannotStartOrRejectCurrentRequest()=runTest {
        val f=Fixture(this);val root=voiceController(f,Switches());val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();runCurrent()
            val old=assertNotNull(root.requestChannel(account,channel,"Old"));runCurrent()
            val current=assertNotNull(root.requestChannel(account,channel,"New"));runCurrent()
            root.begin(old,false,boundary);runCurrent()
            assertEquals(current,root.state.value.pending?.id);assertNull(root.state.value.error);assertEquals(0,f.launches)
            root.begin(current,true,boundary);runCurrent();root.begin(old,true,boundary);runCurrent()
            assertEquals(1,f.published);assertEquals("New",root.state.value.label)
            assertEquals(VoiceOperationId(current),assertNotNull(f.runtime).state.value.operationId)
        } finally {boundary.close();root.close();ui.cancel();f.close()}
    }

    @Test fun microphoneRefusalAfterSwitchConfirmationPreservesEarnedVoiceAndDoesNotWithdrawSharing()=runTest {
        val f=Fixture(this);val guard=Switches();val root=voiceController(f,guard);val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();runCurrent();val firstRequest=assertNotNull(root.requestChannel(account,channel,"First"));runCurrent()
            root.begin(firstRequest,true,boundary);runCurrent();val originalTicket=f.gate.current.value
            val withdrawn=guard.withdrawn.toList();guard.confirm=true
            val request=assertNotNull(root.requestChannel(account,channel,"Other"));runCurrent()
            root.begin(request,true,boundary);runCurrent();assertEquals(request,root.state.value.pending?.id)
            assertEquals(1,f.published)
            root.confirmSwitch(request);root.begin(request,false,boundary);runCurrent()
            assertEquals(withdrawn,guard.withdrawn);assertSame(originalTicket,f.gate.current.value)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertEquals(1,f.published)
            assertEquals(NearbyVoiceError.MICROPHONE_DENIED,root.state.value.error)
        } finally {boundary.close();root.close();ui.cancel();f.close()}
    }

    @Test fun cancellationIgnoringOldSwitchWaitCannotStartAfterNewerRequestHasJoined()=runTest {
        val f=Fixture(this);val guard=Switches();val root=voiceController(f,guard);val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();var returned=false;var waits=0
        guard.reply={if(++waits==1) withContext(NonCancellable) {entered.complete(Unit);release.await();returned=true};true}
        try {
            val account=f.login();runCurrent()
            val old=assertNotNull(root.requestChannel(account,channel,"Old"));runCurrent();root.begin(old,true,boundary);runCurrent()
            assertTrue(entered.isCompleted);assertEquals(0,f.launches)
            val fresh=assertNotNull(root.requestChannel(account,channel,"Fresh"));runCurrent();root.begin(fresh,true,boundary);runCurrent()
            assertEquals(1,f.published);val ticket=f.gate.current.value
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(1,f.published);assertSame(ticket,f.gate.current.value);assertEquals("Fresh",root.state.value.label)
            assertEquals(VoiceOperationId(fresh),assertNotNull(f.runtime).state.value.operationId)
        } finally {release.complete(Unit);boundary.close();root.close();ui.cancel();f.close()}
    }

    @Test fun retiredOriginalAccountCannotUsePermissionReplyAfterSameUserRelogin()=runTest {
        val f=Fixture(this);val root=voiceController(f,Switches());val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();runCurrent();val old=assertNotNull(root.requestChannel(account,channel,"Private"));runCurrent()
            f.repository.logout();val fresh=f.login();runCurrent();root.begin(old,true,boundary);runCurrent()
            assertEquals(0,f.launches);assertNull(root.state.value.pending);assertEquals("",root.state.value.label)
            assertNull(root.requestChannel(account,channel,"Retired"))
            val current=assertNotNull(root.requestChannel(fresh,channel,"Current"));runCurrent();root.begin(current,true,boundary);runCurrent()
            assertEquals(1,f.published);assertEquals("Current",root.state.value.label)
        } finally {boundary.close();root.close();ui.cancel();f.close()}
    }

    @Test fun unavailableVisibleBoundaryCannotWithdrawCurrentVoiceBeforeReplacementAdmission()=runTest {
        val f=Fixture(this);val guard=Switches();val root=voiceController(f,guard);val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();runCurrent();val firstRequest=assertNotNull(root.requestChannel(account,channel,"First"));runCurrent()
            root.begin(firstRequest,true,boundary);runCurrent();val original=f.gate.current.value;val withdrawn=guard.withdrawn.toList()
            val secondRequest=assertNotNull(root.requestChannel(account,channel,"Second"));runCurrent();boundary.close()
            root.begin(secondRequest,true,boundary);runCurrent()
            assertSame(original,f.gate.current.value);assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertEquals(withdrawn,guard.withdrawn);assertEquals(1,f.published)
            assertEquals(NearbyVoiceError.NOT_VISIBLE,root.state.value.error)
        } finally {boundary.close();root.close();ui.cancel();f.close()}
    }

    @Test fun cancelledRootStartCannotIssueForegroundAfterHeldSwitchProducerActuallyReturns()=runTest {
        val f=Fixture(this);val guard=Switches();val root=voiceController(f,guard);val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();var returned=false
        guard.reply={withContext(NonCancellable) {entered.complete(Unit);release.await();returned=true;true}}
        try {
            val account=f.login();runCurrent();val request=assertNotNull(root.requestChannel(account,channel,"Cancelled"));runCurrent()
            root.begin(request,true,boundary);runCurrent();assertTrue(entered.isCompleted)
            assertEquals(request,root.state.value.starting?.id)
            root.cancelRequest(request);runCurrent();assertNull(root.state.value.starting);assertFalse(returned)
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty());assertEquals(0,f.published);assertTrue(account.job.isActive)
        } finally {release.complete(Unit);boundary.close();root.close();ui.cancel();f.close()}
    }

    @Test fun failedOriginalSharingCleanupDoesNotAdmitReplacementVoice()=runTest {
        val f=Fixture(this);val guard=Switches();val root=voiceController(f,guard);val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,ui)
        try {
            val account=f.login();runCurrent();val request=assertNotNull(root.requestChannel(account,channel,"Blocked"));runCurrent()
            guard.reply={false};root.begin(request,true,boundary);runCurrent()
            assertEquals(NearbyVoiceError.CLEANUP_REQUIRED,root.state.value.error)
            assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty());assertEquals(0,f.published)
            assertEquals(1,guard.withdrawn.size);assertEquals(1,guard.awaited.size);assertTrue(account.job.isActive)
        } finally {boundary.close();root.close();ui.cancel();f.close()}
    }

    @Test fun taggedDisconnectWithdrawsOriginalIntentButCannotStopReplacementConversation()=runTest {
        val f=Fixture(this)
        try {
            val account=f.login()
            f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            assertEquals(1,f.published);assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertTrue(f.owner.stop(first));runCurrent()
            assertEquals(VoiceConversationPhase.STOPPED,f.owner.state.value.phase)
            assertFalse(assertNotNull(f.runtime).state.value.wanted)
            f.owner.start(account,channel,second,readPreferences={phone},launch=f::launch);runCurrent()
            assertEquals(2,f.published)
            val ticket=assertNotNull(f.gate.current.value);val issued=f.paths.toList();val closes=f.closes
            assertFalse(f.owner.stop(first));runCurrent()
            assertSame(ticket,f.gate.current.value);assertEquals(issued,f.paths);assertEquals(closes,f.closes)
            assertEquals(second,f.owner.state.value.operationId);assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertTrue(f.owner.stop(second));runCurrent();assertFalse(f.owner.stop(second))
            assertFalse(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
        } finally {f.close()}
    }

    @Test fun taggedDisconnectDuringHeldPreferencesClosesOriginalForegroundAndPreventsLateMedia()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();var returned=false
        try {
            val account=f.login()
            f.owner.start(account,channel,first,readPreferences={withContext(NonCancellable) {
                entered.complete(Unit);release.await();returned=true;phone
            }},launch=f::launch);runCurrent()
            assertTrue(entered.isCompleted);assertEquals(VoiceConversationPhase.PREPARING,f.owner.state.value.phase)
            // Earlier test expected zero launches; preferences deliberately follow visible foreground ACK.
            assertEquals(1,f.launches);assertNotNull(f.gate.current.value)
            assertTrue(f.owner.stop(first));runCurrent();assertFalse(returned)
            assertNull(f.gate.current.value)
            assertEquals(VoiceConversationPhase.STOPPED,f.owner.state.value.phase)
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(1,f.launches);assertTrue(f.allocated.isEmpty());assertEquals(0,f.published)
            assertNull(f.gate.current.value);assertTrue(account.job.isActive)
        } finally {release.complete(Unit);f.close()}
    }

    @Test fun taggedDisconnectBeforeHeldAcquisitionReturnsNeverLaunchesForegroundOrAudio()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();var returned=false
        f.afterAcquire={withContext(NonCancellable) {entered.complete(Unit);release.await();returned=true}}
        try {
            val account=f.login()
            f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            assertTrue(entered.isCompleted);assertEquals(0,f.launches)
            assertTrue(f.owner.stop(first));runCurrent();assertFalse(returned)
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty());assertEquals(0,f.published)
            assertNull(f.gate.current.value);assertTrue(account.job.isActive)
            assertEquals(VoiceConversationPhase.STOPPED,f.owner.state.value.phase)
        } finally {release.complete(Unit);f.close()}
    }

    @Test fun taggedNegativeDisconnectStillExecutesWhenUiCallerIsAlreadyCancelled()=runTest {
        val f=Fixture(this);val caller=Job().apply {cancel()}
        try {
            val account=f.login()
            f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            assertTrue(assertNotNull(f.runtime).state.value.wanted)
            CoroutineScope(coroutineContext+caller).launch(start=CoroutineStart.UNDISPATCHED) {f.owner.stop(first)}
            runCurrent()
            assertFalse(assertNotNull(f.runtime).state.value.wanted)
            assertEquals(VoiceConversationPhase.STOPPED,f.owner.state.value.phase);assertNull(f.gate.current.value)
            assertEquals(1,f.closes);assertTrue(account.job.isActive)
        } finally {caller.cancel();f.close()}
    }

    @Test fun durableConsentMustFinishJournalBeforeHttpAndOnlyConfirmedRevokeRemovesIntent() = runTest {
        val f=Fixture(this);var control: ChannelSharingAccountControl?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val journal=RecordingRevokeJournal();journal.gate=CompletableDeferred()
            val owner=ChannelSharingAccountControl.createDurable(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time},journal,{f.peer});control=owner
            val starting=async {owner.start(f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR)};runCurrent()
            assertTrue(journal.entered.isCompleted);assertTrue(f.sharingAuthorizations.isEmpty());assertFalse(starting.isCompleted)
            journal.gate!!.complete(Unit);assertIs<ChannelSharingReply.Active>(starting.await())
            assertEquals(1,journal.rows.size);assertEquals(ChannelSharingTag(original.sessionId.value,f.key),journal.rows.values.single().tag)
            f.sharingRevokeStatus=HttpStatusCode.ServiceUnavailable
            assertIs<ChannelSharingReply.Failed>(owner.requestRevoke(f.key).await());runCurrent();assertEquals(1,journal.rows.size)
            f.sharingRevokeStatus=HttpStatusCode.OK
            assertIs<ChannelSharingReply.Negative>(owner.requestRevoke(f.key).await());runCurrent();assertTrue(journal.rows.isEmpty())
            assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive)
        } finally {control?.close();f.close()}
    }

    @Test fun durableConsentStorageFailureNeverIssuesPositiveHttp() = runTest {
        val f=Fixture(this);var control: ChannelSharingAccountControl?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val journal=RecordingRevokeJournal();journal.failRecord=true
            val owner=ChannelSharingAccountControl.createDurable(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time},journal,{f.peer});control=owner
            assertFailsWith<IllegalStateException> {owner.start(f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR)}
            assertTrue(journal.entered.isCompleted);assertTrue(f.sharingAuthorizations.isEmpty());assertTrue(journal.rows.isEmpty())
            assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive)
        } finally {control?.close();f.close()}
    }

    @Test fun durableConsentWithdrawnWhileWriteHeldCannotStartAfterOriginalRevoke() = runTest {
        val f=Fixture(this);var control: ChannelSharingAccountControl?=null
        val journal=RecordingRevokeJournal();journal.gate=CompletableDeferred()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val owner=ChannelSharingAccountControl.createDurable(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time},journal,{f.peer});control=owner
            val starting=async {owner.start(f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR)};runCurrent()
            assertTrue(journal.entered.isCompleted);assertTrue(f.sharingAuthorizations.isEmpty())
            assertIs<ChannelSharingReply.Negative>(owner.requestRevoke(f.key).await());runCurrent()
            assertTrue(owner.journalCleanupPending.value)
            journal.gate!!.complete(Unit)
            assertFailsWith<CancellationException> {starting.await()};runCurrent()
            assertEquals(1,f.sharingAuthorizations.size) // Only the original revoke; no late positive HTTP.
            assertTrue(journal.rows.isEmpty());assertFalse(owner.journalCleanupPending.value)
            assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive)
        } finally {journal.gate!!.complete(Unit);runCurrent();control?.close();f.close()}
    }

    @Test fun durableRevokeAckDiskFailureKeepsRemoteNegativeAndPendingIntent() = runTest {
        val f=Fixture(this);var control: ChannelSharingAccountControl?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val journal=RecordingRevokeJournal();journal.failAcknowledge=true
            val owner=ChannelSharingAccountControl.createDurable(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time},journal,{f.peer});control=owner
            assertIs<ChannelSharingReply.Active>(owner.start(f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR))
            assertIs<ChannelSharingReply.Negative>(owner.requestRevoke(f.key).await());runCurrent()
            assertEquals(1,journal.rows.size);assertTrue(owner.journalCleanupPending.value);assertFalse(owner.cleanupFailed.value)
            journal.failAcknowledge=false
            assertIs<ChannelSharingReply.Negative>(owner.requestRevoke(f.key).await());runCurrent()
            assertTrue(journal.rows.isEmpty());assertFalse(owner.journalCleanupPending.value)
            assertTrue(runtime.state.value.wanted)
        } finally {control?.close();f.close()}
    }

    @Test fun originalRevokeRecoveryRemovesOnlyOwnUnprotectedTagWithoutVoiceOrPositiveHttp() = runTest {
        val f=Fixture(this)
        try {
            val account=f.login();val journal=RecordingRevokeJournal()
            val obsolete=ChannelSharingRevokeIntent(actor,ChannelSharingTag(channel,f.key),first.value)
            val live=ChannelSharingRevokeIntent(actor,ChannelSharingTag(channel,f.peer),second.value)
            val foreign=ChannelSharingRevokeIntent(f.peer,obsolete.tag,f.key)
            journal.record(obsolete);journal.record(live);journal.record(foreign)
            val recovery=ChannelSharingRevokeRecovery(f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time},journal)
            assertTrue(recovery.drain(account,setOf(live.tag)))
            assertEquals(setOf(live,foreign),journal.rows.values.toSet())
            assertEquals(listOf("/v2/nearby/sharing/$channel/${f.key}/revoke"),f.paths)
            assertEquals<List<String?>>(listOf("Bearer token"),f.sharingAuthorizations)
            assertNull(f.runtime);assertTrue(account.job.isActive)
        } finally {f.close()}
    }

    @Test fun originalRevokeRecoveryFailureRetainsIntentAndOnlyExactRetryCompletes() = runTest {
        val f=Fixture(this)
        try {
            val account=f.login();val journal=RecordingRevokeJournal()
            val intent=ChannelSharingRevokeIntent(actor,ChannelSharingTag(channel,f.key),first.value);journal.record(intent)
            val recovery=ChannelSharingRevokeRecovery(f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time},journal)
            f.sharingRevokeStatus=HttpStatusCode.ServiceUnavailable
            assertFalse(recovery.drain(account,emptySet()));assertEquals(listOf(intent),journal.rows.values.toList())
            f.sharingRevokeStatus=HttpStatusCode.OK
            assertTrue(recovery.drain(account,emptySet()));assertTrue(journal.rows.isEmpty())
            assertEquals(2,f.paths.size);assertTrue(f.paths.all {it.endsWith("/revoke")});assertNull(f.runtime)
        } finally {f.close()}
    }

    @Test fun originalRevokeRecoveryRetiredAccountCannotAcknowledgeHeldResponse() = runTest {
        val f=Fixture(this);f.sharingRevokeRelease=CompletableDeferred()
        try {
            val account=f.login();val journal=RecordingRevokeJournal()
            val intent=ChannelSharingRevokeIntent(actor,ChannelSharingTag(channel,f.key),first.value);journal.record(intent)
            val recovery=ChannelSharingRevokeRecovery(f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time},journal)
            val draining=async {recovery.drain(account,emptySet())};runCurrent();assertTrue(f.sharingRevokeEntered.isCompleted)
            account.job.cancel();f.sharingRevokeRelease!!.complete(Unit)
            assertFailsWith<CancellationException> {draining.await()};assertEquals(listOf(intent),journal.rows.values.toList())
            assertEquals(1,f.paths.size);assertNull(f.runtime)
        } finally {f.sharingRevokeRelease!!.complete(Unit);runCurrent();f.close()}
    }

    @Test fun originalRevokeRecoveryBoundedBatchCannotClaimUnseenTailComplete() = runTest {
        val f=Fixture(this)
        try {
            val account=f.login();val journal=RecordingRevokeJournal()
            repeat(65) {index ->
                val generation="90000000-0000-0000-0000-${index.toString().padStart(12,'0')}"
                journal.record(ChannelSharingRevokeIntent(actor,ChannelSharingTag(channel,generation),first.value))
            }
            val recovery=ChannelSharingRevokeRecovery(f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time},journal)
            assertFalse(recovery.drain(account,emptySet()));assertEquals(64,f.paths.size);assertEquals(1,journal.rows.size)
            assertTrue(recovery.drain(account,emptySet()));assertEquals(65,f.paths.size);assertTrue(journal.rows.isEmpty())
        } finally {f.close()}
    }

    @Test fun originalRevokeRecoveryRejectsForeignJournalBatchBeforeAnyHttp() = runTest {
        val f=Fixture(this)
        try {
            val account=f.login();val journal=RecordingRevokeJournal();journal.returnForeign=true
            journal.record(ChannelSharingRevokeIntent(f.peer,ChannelSharingTag(channel,f.key),first.value))
            val recovery=ChannelSharingRevokeRecovery(f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time},journal)
            assertFailsWith<IllegalArgumentException> {recovery.drain(account,emptySet())}
            assertTrue(f.paths.isEmpty());assertEquals(1,journal.rows.size);assertTrue(account.job.isActive)
        } finally {f.close()}
    }

    @Test fun sharingStartupRecoversOldIntentBeforeForegroundAndNewConsent() = runTest {
        val f=Fixture(this);f.useBoundRuntime=true;f.sharingRevokeRelease=CompletableDeferred();var handle: ChannelSharingStartHandle?=null
        val source=CaptureLocationRepository();val foreground=CaptureForegroundPort();val journal=RecordingRevokeJournal();var launches=0
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val old=ChannelSharingRevokeIntent(actor,ChannelSharingTag(channel,f.peer),first.value);journal.record(old)
            val startup=ChannelSharingStartup(f.client,"https://example.test/v2/nearby",f.dispatcher,source,NearbyLocationDemandCoordinator(source),foreground,
                {f.time},{f.time+900},{EarnedTelemetry()},journal,{f.peer}) {captured -> f.acquire(captured);assertNotNull(f.boundRuntime)}
            val started=startup.start(account,original,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR) {ticket ->
                launches++;foreground.authority.acknowledge(ticket,Any());ChannelSharingForegroundLaunch.STARTED
            };handle=started;runCurrent()
            assertTrue(f.sharingRevokeEntered.isCompleted);assertEquals(0,launches);assertTrue(source.issued.isEmpty());assertFalse(started.result.isCompleted)
            assertEquals(listOf(old),journal.rows.values.toList());assertEquals(1,f.sharingAuthorizations.size)
            f.sharingRevokeRelease!!.complete(Unit);assertTrue(started.result.await());runCurrent()
            assertEquals(1,launches);assertEquals(ChannelSharingTag(original.sessionId.value,f.key),journal.rows.values.single().tag)
            assertEquals(listOf("/v2/nearby/sharing/$channel/${f.peer}/revoke","/v2/nearby/sharing/${original.sessionId.value}/${f.key}/start"),f.paths.filter {"/sharing/" in it})
            assertTrue(runtime.state.value.wanted)
        } finally {f.sharingRevokeRelease!!.complete(Unit);runCurrent();handle?.close();f.close()}
    }

    @Test fun sharingStartupFailedOldRecoveryPreventsForegroundConsentAndGps() = runTest {
        val f=Fixture(this);f.useBoundRuntime=true;f.sharingRevokeStatus=HttpStatusCode.ServiceUnavailable;var handle: ChannelSharingStartHandle?=null
        val source=CaptureLocationRepository();val foreground=CaptureForegroundPort();val journal=RecordingRevokeJournal();var launches=0
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val old=ChannelSharingRevokeIntent(actor,ChannelSharingTag(channel,f.peer),first.value);journal.record(old)
            val startup=ChannelSharingStartup(f.client,"https://example.test/v2/nearby",f.dispatcher,source,NearbyLocationDemandCoordinator(source),foreground,
                {f.time},{f.time+900},{EarnedTelemetry()},journal,{f.peer}) {captured -> f.acquire(captured);assertNotNull(f.boundRuntime)}
            val started=startup.start(account,original,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR) {launches++;ChannelSharingForegroundLaunch.STARTED};handle=started
            assertFalse(started.result.await());assertTrue(started.state.value.originalCleanupPending);assertEquals(0,launches);assertTrue(source.issued.isEmpty())
            assertEquals(listOf(old),journal.rows.values.toList());assertEquals(1,f.sharingAuthorizations.size)
            assertTrue(runtime.state.value.wanted);assertFalse(runtime.state.value.sharingEnabled);assertTrue(account.job.isActive)
        } finally {handle?.close();f.close()}
    }

    @Test fun concurrentSharingPreparationsCannotRevokeOrReplaceFirstAdmittedSharing() = runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val handles=mutableListOf<ChannelSharingStartHandle>()
        val source=CaptureLocationRepository();val foreground=CaptureForegroundPort();val journal=RecordingRevokeJournal();var launches=0
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val startup=ChannelSharingStartup(f.client,"https://example.test/v2/nearby",f.dispatcher,source,NearbyLocationDemandCoordinator(source),foreground,
                {f.time},{f.time+900},{EarnedTelemetry()},journal,{f.peer}) {captured -> f.acquire(captured);assertNotNull(f.boundRuntime)}
            val launch: (ChannelLocationForegroundTicket)->ChannelSharingForegroundLaunch={ticket -> launches++;foreground.authority.acknowledge(ticket,Any());ChannelSharingForegroundLaunch.STARTED}
            val a=startup.start(account,original,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,launch);handles+=a
            val b=startup.start(account,original,f.peer,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,launch);handles+=b
            assertTrue(a.result.await());assertFalse(b.result.await());runCurrent()
            assertEquals(1,launches);assertEquals(ChannelSharingTag(original.sessionId.value,f.key),journal.rows.values.single().tag)
            assertEquals(1,f.sharingAuthorizations.size);assertTrue(runtime.state.value.sharingEnabled)
        } finally {handles.forEach {it.close()};f.close()}
    }

    @Test fun sharingStartupHiddenDuringRecoveryCannotLaunchAfterLateNegative() = runTest {
        val f=Fixture(this);f.useBoundRuntime=true;f.sharingRevokeRelease=CompletableDeferred();var handle: ChannelSharingStartHandle?=null
        val source=CaptureLocationRepository();val foreground=CaptureForegroundPort();val journal=RecordingRevokeJournal();var launches=0
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            journal.record(ChannelSharingRevokeIntent(actor,ChannelSharingTag(channel,f.peer),first.value))
            val startup=ChannelSharingStartup(f.client,"https://example.test/v2/nearby",f.dispatcher,source,NearbyLocationDemandCoordinator(source),foreground,
                {f.time},{f.time+900},{EarnedTelemetry()},journal,{f.peer}) {captured -> f.acquire(captured);assertNotNull(f.boundRuntime)}
            val started=startup.start(account,original,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR) {launches++;ChannelSharingForegroundLaunch.STARTED};handle=started
            runCurrent();assertTrue(f.sharingRevokeEntered.isCompleted)
            started.requestHide();assertFalse(started.close());assertTrue(started.state.value.preparing)
            f.sharingRevokeRelease!!.complete(Unit);assertFalse(started.result.await());runCurrent()
            assertEquals(0,launches);assertTrue(source.issued.isEmpty());assertTrue(journal.rows.isEmpty())
            assertEquals(1,f.sharingAuthorizations.size);assertTrue(started.close());assertTrue(runtime.state.value.wanted)
        } finally {f.sharingRevokeRelease!!.complete(Unit);runCurrent();handle?.close();f.close()}
    }

    private class RecordingRevokeJournal : ChannelSharingRevokeJournal {
        val rows=linkedMapOf<Pair<String,ChannelSharingTag>,ChannelSharingRevokeIntent>()
        val entered=CompletableDeferred<Unit>();var gate: CompletableDeferred<Unit>?=null;var failRecord=false;var failAcknowledge=false;var returnForeign=false
        override suspend fun record(intent: ChannelSharingRevokeIntent): ChannelSharingRevokeIntent {
            withContext(NonCancellable) {entered.complete(Unit);gate?.await()}
            check(!failRecord) {"Disk write failed"}
            return rows.getOrPut(intent.actorId to intent.tag) {intent}
        }
        override suspend fun pending(actorId: String,limit: Int)=rows.values.filter {returnForeign || it.actorId==actorId}.take(limit)
        override suspend fun acknowledge(intent: ChannelSharingRevokeIntent,authenticatedActorId: String,reply: ChannelSharingReply): Boolean {
            check(!failAcknowledge) {"Disk ACK failed"}
            if(authenticatedActorId!=intent.actorId || reply !is ChannelSharingReply.Negative || reply.tag!=intent.tag ||
                rows[intent.actorId to intent.tag]!=intent) return false
            rows.remove(intent.actorId to intent.tag);return true
        }
    }

    @Test fun sharingStartupOneShotVisibleLauncherThenActualForegroundPublishesDespiteCancelledUiWaiter() = runTest {
        val f=Fixture(this);f.useBoundRuntime=true;var handle: ChannelSharingStartHandle?=null
        val source=CaptureLocationRepository();val foreground=CaptureForegroundPort();var launches=0;var ticket: ChannelLocationForegroundTicket?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val startup=ChannelSharingStartup(f.client,"https://example.test/v2/nearby",f.dispatcher,source,NearbyLocationDemandCoordinator(source),foreground,
                {f.time},{f.time+900},{EarnedTelemetry()},RecordingRevokeJournal(),{f.peer}) {captured -> f.acquire(captured);assertNotNull(f.boundRuntime)}
            val started=startup.start(account,original,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR) {issued ->
                launches++;ticket=issued;ChannelSharingForegroundLaunch.STARTED
            };handle=started
            val waiter=async {started.result.await()};runCurrent();waiter.cancel()
            assertEquals(1,launches);assertFalse(started.result.isCompleted);assertTrue(f.sharingAuthorizations.isEmpty());assertTrue(source.issued.isEmpty())
            foreground.authority.acknowledge(assertNotNull(ticket),Any());assertTrue(started.result.await());runCurrent()
            f.time=101;source.emitFix(1001,101);runCurrent();assertEquals(listOf(0L),f.packetSequences)
            f.sharingRevokeRelease=CompletableDeferred()
            started.requestHide();runCurrent();assertFalse(runtime.state.value.sharingEnabled);assertTrue(runtime.state.value.wanted)
            assertTrue(f.sharingRevokeEntered.isCompleted);assertTrue(started.state.value.childCleanupPending)
            assertFalse(started.close());assertFalse(started.state.value.nativeCleanupPending)
            f.sharingRevokeRelease!!.complete(Unit);runCurrent();assertFalse(started.state.value.childCleanupPending)
            assertTrue(started.close());assertEquals(1,launches);assertEquals(setOf(LocationConsumer.NAVIGATION),source.state.value.demands)
            assertTrue(account.job.isActive)
        } finally {f.sharingRevokeRelease?.complete(Unit);runCurrent();handle?.close();f.close()}
    }

    @Test fun sharingStartupPermissionRejectionNeverAdmitsConsentOrNativeAndGenerationCannotBeReused() = runTest {
        val f=Fixture(this);f.useBoundRuntime=true;var handle: ChannelSharingStartHandle?=null
        val source=CaptureLocationRepository();val foreground=CaptureForegroundPort();var launches=0
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val startup=ChannelSharingStartup(f.client,"https://example.test/v2/nearby",f.dispatcher,source,NearbyLocationDemandCoordinator(source),foreground,
                {f.time},{f.time+900},{EarnedTelemetry()},RecordingRevokeJournal(),{f.peer}) {captured -> f.acquire(captured);assertNotNull(f.boundRuntime)}
            val started=startup.start(account,original,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR) {
                launches++;ChannelSharingForegroundLaunch.PERMISSION_REQUIRED
            };handle=started
            assertFalse(started.result.await());assertEquals(ChannelSharingForegroundLaunch.PERMISSION_REQUIRED,started.state.value.launchResult)
            assertTrue(f.sharingAuthorizations.isEmpty());assertTrue(source.issued.none {it});assertTrue(runtime.state.value.wanted)
            assertFailsWith<IllegalArgumentException> {startup.start(account,original,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR) {
                launches++;ChannelSharingForegroundLaunch.STARTED
            }}
            assertEquals(1,launches);assertTrue(started.close())
        } finally {handle?.close();f.close()}
    }

    @Test fun sharingStartupHideDuringHeldAcquisitionKeepsActualPreparationOwnedAndCannotLaunchLate() = runTest {
        val f=Fixture(this);f.useBoundRuntime=true;var handle: ChannelSharingStartHandle?=null
        val source=CaptureLocationRepository();val foreground=CaptureForegroundPort();val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();var launches=0
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val startup=ChannelSharingStartup(f.client,"https://example.test/v2/nearby",f.dispatcher,source,NearbyLocationDemandCoordinator(source),foreground,
                {f.time},{f.time+900},{EarnedTelemetry()},RecordingRevokeJournal(),{f.peer}) {captured ->
                withContext(NonCancellable) {entered.complete(Unit);gate.await()}
                f.acquire(captured);assertNotNull(f.boundRuntime)
            }
            val started=startup.start(account,original,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR) {
                launches++;ChannelSharingForegroundLaunch.STARTED
            };handle=started;runCurrent();assertTrue(entered.isCompleted)
            started.requestHide();assertFalse(started.close());assertTrue(started.state.value.preparing)
            gate.complete(Unit);assertFalse(started.result.await());runCurrent()
            assertFalse(started.state.value.preparing);assertTrue(started.close());assertEquals(0,launches)
            assertTrue(source.issued.isEmpty());assertTrue(f.sharingAuthorizations.isEmpty());assertTrue(runtime.state.value.wanted)
        } finally {gate.complete(Unit);runCurrent();handle?.close();f.close()}
    }

    @Test fun boundAccountRuntimePublishesSharingThroughOwnedCallbackWithoutUiRelay() = runTest {
        val f=Fixture(this);f.useBoundRuntime=true
        f.sharingDispatch={error("Bound runtime must use its own sharing callback")}
        var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source);val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val bound=assertNotNull(f.boundRuntime);val runtime=bound.voice;assertSame(runtime,f.runtime)
            val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()});capture=owner
            foreground.authority.acknowledge(owner.foregroundTicket,Any());assertTrue(bound.sharing.enable(original,owner).await());runCurrent()
            f.time=101;source.emitFix(1001,101);runCurrent();assertEquals(listOf(0L),f.packetSequences)
            runtime.hidePosition();runCurrent();assertNull(bound.sharing.publisher.value);assertNull(owner.packet.value)
            assertEquals(listOf(true,false),source.issued);assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive)
        } finally {capture?.close();sharing?.close();f.close()}
    }

    @Test fun sharingCoordinatorMissingVoiceCallbackTimesOutAndClearsOnlyOriginalSharingFlag() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null;var coordinator: ChannelSharingCoordinator?=null
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source);val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()});capture=owner
            val root=ChannelSharingCoordinator.create(account,runtime,f.dispatcher);coordinator=root
            val started=root.enable(original,owner);runCurrent();assertTrue(runtime.state.value.sharingEnabled)
            assertFalse(started.isCompleted);assertTrue(source.issued.isEmpty())
            advanceTimeBy(30_001);runCurrent();assertFalse(started.await())
            assertFalse(owner.locationForegroundState.value.wanted);assertFalse(runtime.state.value.sharingEnabled)
            assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive);assertTrue(f.sharingAuthorizations.isEmpty())
        } finally {coordinator?.close();capture?.close();sharing?.close();f.close()}
    }

    @Test fun sharingCoordinatorReplacementDuringPartialNativeStartPreservesNewVoiceAndRetriesOldCleanup() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null;var coordinator: ChannelSharingCoordinator?=null
        f.sharingDispatch={coordinator?.dispatch(it)}
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source);val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()});capture=owner
            val root=ChannelSharingCoordinator.create(account,runtime,f.dispatcher);coordinator=root
            foreground.authority.acknowledge(owner.foregroundTicket,Any());source.startRelease=CompletableDeferred()
            val started=root.enable(original,owner);runCurrent()
            assertEquals(listOf(true),source.issued);assertEquals(1,owner.locationForegroundState.value.nativeInFlight)
            source.failStop=true
            f.owner.start(account,channel,second,readPreferences={phone},launch=f::launch);runCurrent()
            assertNotEquals(original.generation,runtime.state.value.generation);assertTrue(runtime.state.value.wanted)
            assertFalse(owner.locationForegroundState.value.wanted);assertTrue(root.cleanupPending.value)
            source.startRelease!!.complete(Unit);runCurrent();assertFalse(started.await())
            assertTrue(owner.locationForegroundState.value.cleanupFailed);assertTrue(root.cleanupPending.value)
            assertNull(root.publisher.value);assertTrue(f.packetSequences.isEmpty())
            assertFalse(root.close());source.failStop=false;assertTrue(root.close());runCurrent()
            assertFalse(root.cleanupPending.value);assertEquals(setOf(LocationConsumer.NAVIGATION),source.state.value.demands)
            assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive)
        } finally {source.failStop=false;source.startRelease?.complete(Unit);runCurrent();coordinator?.close();capture?.close();sharing?.close();f.close()}
    }

    @Test fun sharingCoordinatorUsesActualVoiceEffectsAndSurvivesCancelledUiWaiter() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null;var coordinator: ChannelSharingCoordinator?=null
        f.sharingDispatch={coordinator?.dispatch(it)}
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source);val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()});capture=owner
            val root=ChannelSharingCoordinator.create(account,runtime,f.dispatcher);coordinator=root
            val started=root.enable(original,owner);val waiter=async {started.await()};runCurrent();waiter.cancel()
            assertTrue(runtime.state.value.sharingEnabled);assertFalse(started.isCompleted)
            assertTrue(source.issued.isEmpty());assertTrue(f.sharingAuthorizations.isEmpty())
            foreground.authority.acknowledge(owner.foregroundTicket,Any());assertTrue(started.await());runCurrent()
            assertNotNull(root.publisher.value);f.time=101;source.emitFix(1001,101);runCurrent()
            assertEquals(listOf(0L),f.packetSequences)
            root.dispatch(VoiceSharingDispatch(VoiceSharingEffect.REMOVE_DEMAND,VoiceSessionTag(original.sessionId,VoiceGeneration(99))))
            runCurrent();assertTrue(owner.locationForegroundState.value.wanted)
            runtime.hidePosition();runCurrent();assertNull(root.publisher.value);assertNull(owner.packet.value)
            assertFalse(owner.locationForegroundState.value.wanted);assertEquals(listOf(true,false),source.issued)
            assertTrue(f.paths.any {it.endsWith("/revoke")});assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive)
            assertTrue(root.close())
        } finally {coordinator?.close();capture?.close();sharing?.close();f.close()}
    }

    @Test fun sharingCoordinatorHideDuringHeldConsentCannotAcquireNativeOrPublishLate() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null;var coordinator: ChannelSharingCoordinator?=null
        f.sharingDispatch={coordinator?.dispatch(it)}
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source);val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()});capture=owner
            val root=ChannelSharingCoordinator.create(account,runtime,f.dispatcher);coordinator=root
            foreground.authority.acknowledge(owner.foregroundTicket,Any());f.sharingPositiveRelease=CompletableDeferred()
            val started=root.enable(original,owner);runCurrent();assertTrue(f.sharingPositiveEntered.isCompleted)
            runtime.hidePosition();runCurrent();assertFalse(owner.locationForegroundState.value.wanted)
            f.sharingPositiveRelease!!.complete(Unit);runCurrent()
            assertFalse(started.await());assertNull(root.publisher.value);assertTrue(source.issued.none {it})
            assertTrue(f.packetSequences.isEmpty());assertTrue(runtime.state.value.wanted);assertFalse(runtime.state.value.sharingEnabled)
        } finally {f.sharingPositiveRelease?.complete(Unit);runCurrent();coordinator?.close();capture?.close();sharing?.close();f.close()}
    }

    @Test fun rootPacketPublisherExpiredSampleDoesNotWithdrawConsentButAccessDenialHidesOriginalCapture() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null;var publisher: ChannelSharingPacketPublisher?=null
        f.sharingCredit=30_000;f.voiceGrantMillis=20_000;f.packetElapsed=1001
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source);val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()});capture=owner
            foreground.authority.acknowledge(owner.foregroundTicket,Any());assertTrue(owner.begin().await())
            val sending=ChannelSharingPacketPublisher.create(account,owner,f.dispatcher);publisher=sending
            f.time=101;source.emitFix(1001,101);runCurrent()
            assertEquals(listOf(0L),f.packetSequences);assertEquals(ChannelSharingFailure.EXPIRED,sending.state.value.failure)
            assertTrue(owner.locationForegroundState.value.wanted);assertFalse(sending.state.value.retired)
            assertFalse(f.paths.any {it.endsWith("/revoke")})
            f.packetElapsed=0;f.time=1103;source.emitFix(2003,1103);runCurrent()
            assertEquals(listOf(0L,1L),f.packetSequences);assertEquals(1L,sending.state.value.publishedSequence);assertNull(sending.state.value.failure)
            f.packetStatus=HttpStatusCode.Forbidden;f.time=1104;source.emitFix(2004,1104);runCurrent()
            assertEquals(listOf(0L,1L,2L),f.packetSequences);assertTrue(sending.state.value.retired)
            assertEquals(ChannelSharingFailure.ACCESS_DENIED,sending.state.value.failure)
            assertFalse(owner.locationForegroundState.value.wanted);assertNull(owner.packet.value)
            assertTrue(f.paths.any {it.endsWith("/revoke")});assertTrue(runtime.state.value.wanted)
        } finally {publisher?.close();capture?.close();sharing?.close();f.close()}
    }

    @Test fun rootPacketPublisherDropsExpiredPendingSampleAndClosePreservesBorrowedCapture() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null
        var publisher: ChannelSharingPacketPublisher?=null;var duplicate: ChannelSharingPacketPublisher?=null
        f.sharingCredit=30_000;f.voiceGrantMillis=20_000
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source);val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()});capture=owner
            foreground.authority.acknowledge(owner.foregroundTicket,Any());assertTrue(owner.begin().await())
            val sending=ChannelSharingPacketPublisher.create(account,owner,f.dispatcher);publisher=sending
            assertFailsWith<IllegalStateException> {duplicate=ChannelSharingPacketPublisher.create(account,owner,f.dispatcher)}
            val gate=CompletableDeferred<Unit>();f.packetRelease=gate
            f.time=101;source.emitFix(1001,101);runCurrent();assertEquals(listOf(0L),f.packetSequences)
            f.time=102;source.emitFix(1002,102);runCurrent();assertEquals(1L,assertNotNull(owner.packet.value).sequence)
            f.time=15_102;owner.tick();assertNull(owner.packet.value)
            f.packetRelease=null;gate.complete(Unit);runCurrent()
            assertEquals(listOf(0L),f.packetSequences);assertNull(sending.state.value.publishedSequence)
            assertTrue(owner.locationForegroundState.value.wanted)
            f.time=15_103;source.emitFix(16_003,15_103);runCurrent()
            assertEquals(listOf(0L,2L),f.packetSequences);assertEquals(2L,sending.state.value.publishedSequence)
            sending.close();runCurrent();assertTrue(sending.state.value.retired)
            f.time=15_104;source.emitFix(16_004,15_104);runCurrent()
            assertEquals(listOf(0L,2L),f.packetSequences);assertEquals(3L,assertNotNull(owner.packet.value).sequence)
            assertTrue(owner.locationForegroundState.value.nativeOwned);assertTrue(runtime.state.value.wanted)
            assertFalse(f.paths.any {it.endsWith("/revoke")})
        } finally {f.packetRelease?.complete(Unit);runCurrent();duplicate?.close();publisher?.close();capture?.close();sharing?.close();f.close()}
    }

    @Test fun rootPacketPublisherSerializesAndConflatesActualSamplesThenFencesHeldHideResponse() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null;var publisher: ChannelSharingPacketPublisher?=null
        f.sharingCredit=30_000;f.voiceGrantMillis=20_000
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source);val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()});capture=owner
            foreground.authority.acknowledge(owner.foregroundTicket,Any());assertTrue(owner.begin().await())
            val sending=ChannelSharingPacketPublisher.create(account,owner,f.dispatcher);publisher=sending
            val gate=CompletableDeferred<Unit>();f.packetRelease=gate
            f.time=101;source.emitFix(1001,101);runCurrent()
            assertEquals(listOf(0L),f.packetSequences);assertEquals(1,sending.state.value.inFlight)
            f.time=102;source.emitFix(1002,102);runCurrent();f.time=103;source.emitFix(1003,103);runCurrent()
            assertEquals(listOf(0L),f.packetSequences)
            f.packetRelease=null;gate.complete(Unit);runCurrent()
            assertEquals(listOf(0L,2L),f.packetSequences);assertEquals(2L,sending.state.value.publishedSequence);assertEquals(0,sending.state.value.inFlight)
            f.packetRelease=CompletableDeferred();f.time=104;source.emitFix(1004,104);runCurrent()
            assertEquals(listOf(0L,2L,3L),f.packetSequences);assertEquals(1,sending.state.value.inFlight)
            owner.requestHide();runCurrent();assertTrue(sending.state.value.retired);assertNull(sending.state.value.publishedSequence)
            // Ktor cancels our await before the deliberately uncancellable engine callback finishes.
            assertEquals(0,sending.state.value.inFlight);assertEquals(listOf(0L,2L),f.packetCompletions)
            f.packetRelease!!.complete(Unit);runCurrent();assertEquals(listOf(0L,2L,3L),f.packetCompletions)
            assertEquals(0,sending.state.value.inFlight);assertNull(sending.state.value.publishedSequence)
            assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive);assertEquals(setOf(LocationConsumer.NAVIGATION),source.state.value.demands)
        } finally {f.packetRelease?.complete(Unit);runCurrent();publisher?.close();capture?.close();sharing?.close();f.close()}
    }

    @Test fun rootLocationCaptureRideStripsUnearnedValuesAndExpiresPacketWithoutStoppingHealthyVoice() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null
        f.sharingCredit=30_000;f.voiceGrantMillis=20_000;f.sharingMode="RIDE"
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source);val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.RIDE,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},
                {EarnedTelemetry(speedKmh=TelemetryNumber.known(0.0),powerW=TelemetryNumber(true,false,4200.0),packVoltageV=TelemetryNumber.known(99.0))});capture=owner
            foreground.authority.acknowledge(owner.foregroundTicket,Any());assertTrue(owner.begin().await())
            f.time=101;source.emitFix(1001,101);runCurrent()
            val telemetry=assertNotNull(assertNotNull(owner.packet.value).telemetry)
            assertEquals(0.0,telemetry.speedKmh.value);assertFalse(telemetry.powerW.known);assertNull(telemetry.powerW.value)
            assertFalse(telemetry.packVoltageV.supported);assertNull(telemetry.packVoltageV.value)
            f.time=15_101;owner.tick();assertNull(owner.packet.value)
            assertTrue(owner.locationForegroundState.value.wanted);assertTrue(owner.locationForegroundState.value.nativeOwned)
            assertTrue(runtime.canShare(original));assertEquals(listOf(true),source.issued)
        } finally {capture?.close();sharing?.close();f.close()}
    }

    @Test fun rootLocationCaptureRequiresForegroundAndOwnsFreshFixAfterCancelledUiWaiter() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null;var duplicate: ChannelSharingCaptureOwner?=null
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source)
        val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()});capture=owner
            val started=owner.begin();val waiter=async {started.await()};runCurrent();waiter.cancel()
            assertTrue(source.issued.isEmpty());assertTrue(f.sharingAuthorizations.isEmpty());assertFalse(started.isCompleted)
            foreground.authority.acknowledge(owner.foregroundTicket,Any());assertTrue(started.await())
            assertEquals(listOf(true),source.issued);assertTrue(owner.locationForegroundState.value.nativeOwned)
            f.time=101;source.emitFix(1001,101);runCurrent()
            val packet=assertNotNull(owner.packet.value);assertEquals(0,packet.sequence);assertEquals(0.0,packet.location!!.latitude);assertNull(packet.telemetry)
            source.emitFix(1001,null);runCurrent();assertEquals(packet,owner.packet.value)
            f.time=102;source.emitFix(1002,101);runCurrent()
            assertEquals(1002,(source.state.value.status as RideLocationStatus.Available).fix.capturedAtEpochMillis)
            assertEquals(packet,owner.packet.value)
            source.emitFix(1000,99);runCurrent();assertEquals(packet,owner.packet.value)
            source.emitFix(1003,103);runCurrent();assertEquals(packet,owner.packet.value)
            f.time=104;source.emitFix(1004,104);runCurrent()
            assertEquals(1,assertNotNull(owner.packet.value).sequence)
            assertFailsWith<IllegalStateException> {duplicate=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()})}
            assertSame(owner.foregroundTicket,foreground.current.value);assertTrue(foreground.allows(owner))
            assertTrue(owner.close());runCurrent();assertNull(owner.packet.value)
            assertEquals(setOf(LocationConsumer.NAVIGATION),source.state.value.demands)
            assertTrue(f.paths.any {it.endsWith("/revoke")});assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive)
        } finally {duplicate?.close();capture?.close();sharing?.close();f.close()}
    }

    @Test fun rootLocationCaptureHeldPartialActivationRemainsOwnedUntilActualNativeRelease() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source);val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()});capture=owner
            foreground.authority.acknowledge(owner.foregroundTicket,Any());source.startRelease=CompletableDeferred()
            val started=owner.begin();runCurrent();assertEquals(listOf(true),source.issued)
            owner.requestHide();runCurrent();assertFalse(owner.locationForegroundState.value.wanted)
            assertTrue(owner.locationForegroundState.value.nativeOwned);assertEquals(1,owner.locationForegroundState.value.nativeInFlight)
            assertFalse(canReleaseChannelLocationForeground(false,listOf(owner.locationForegroundState.value)))
            source.startRelease!!.complete(Unit);runCurrent();assertTrue(started.isCancelled);assertTrue(owner.close())
            assertEquals(listOf(true,false),source.issued);assertFalse(owner.locationForegroundState.value.nativeOwned)
            assertEquals(0,owner.locationForegroundState.value.nativeInFlight);assertTrue(runtime.state.value.wanted)
        } finally {source.startRelease?.complete(Unit);runCurrent();capture?.close();sharing?.close();f.close()}
    }

    @Test fun rootLocationCaptureFailedNativeStopIsRetryableAndForeignSourceCannotReplaceOwner() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var capture: ChannelSharingCaptureOwner?=null
        val source=CaptureLocationRepository();val demand=NearbyLocationDemandCoordinator(source);val foreground=CaptureForegroundPort()
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingCaptureOwner.create(account,runtime,original,control,f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                source,demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()});capture=owner
            foreground.authority.acknowledge(owner.foregroundTicket,Any());assertTrue(owner.begin().await())
            assertFailsWith<IllegalArgumentException> {ChannelSharingCaptureOwner.create(account,runtime,original,control,f.peer,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR,
                CaptureLocationRepository(),demand,foreground,f.dispatcher,{f.time},{f.time+900},{EarnedTelemetry()})}
            assertSame(owner.foregroundTicket,foreground.current.value)
            source.failStop=true;assertFalse(owner.close());assertTrue(owner.locationForegroundState.value.nativeOwned)
            assertTrue(owner.locationForegroundState.value.cleanupFailed);assertTrue(demand.cleanupPending.value)
            assertFalse(canReleaseChannelLocationForeground(false,listOf(owner.locationForegroundState.value)))
            source.failStop=false;assertTrue(owner.close());assertFalse(owner.locationForegroundState.value.nativeOwned)
            assertFalse(demand.cleanupPending.value);assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive)
        } finally {source.failStop=false;capture?.close();sharing?.close();f.close()}
    }

    private class CaptureLocationRepository : RideLocationRepository {
        override val requiredPermissions=emptyList<String>()
        override val state=MutableStateFlow(RideLocationState(demands=setOf(LocationConsumer.NAVIGATION)))
        val issued=mutableListOf<Boolean>();var startRelease: CompletableDeferred<Unit>?=null;var failStop=false
        override suspend fun refreshPermissionAndProviders()=Unit
        override suspend fun setDemand(consumer: LocationConsumer,enabled: Boolean) {
            assertEquals(LocationConsumer.NEARBY_CHANNEL_SHARING,consumer);issued+=enabled
            if(enabled) withContext(NonCancellable) {startRelease?.await()}
            state.value=state.value.copy(demands=if(enabled) state.value.demands+consumer else state.value.demands-consumer)
            if(!enabled && failStop) error("Native stop failed")
        }
        fun emitFix(epoch: Long,elapsed: Long?) {
            state.value=state.value.copy(status=RideLocationStatus.Available(RideLocationFix(GeoCoordinate(0.0,0.0),0.0,null,null,epoch,elapsed,LocationSource.GPS)))
        }
    }
    private inner class CaptureForegroundPort : ChannelLocationForegroundPort {
        var ids=0;val authority=ChannelLocationForegroundAuthority({true},{"90000000-0000-0000-0000-${(++ids).toString().padStart(12,'0')}"})
        override val current=authority.current
        override fun request(owner: ChannelLocationForegroundOwner)=authority.request(owner)
        override fun allows(owner: ChannelLocationForegroundOwner)=authority.allows(owner)
        override fun stop(ticket: ChannelLocationForegroundTicket)=authority.stop(ticket)
        override suspend fun awaitReady(ticket: ChannelLocationForegroundTicket)=combine(authority.current,authority.ready) {current,ready ->
            when {current!==ticket -> false;ready===ticket -> authority.allows(ticket.owner as ChannelLocationForegroundOwner);else -> null}
        }.filterNotNull().first()
    }

    @Test fun rootPresentationDisposalRejectsOldActionsAndPreservesProcessConversation() = runTest {
        val f=Fixture(this,withAudience=true,withChannelRoster=true);f.useBoundRuntime=true;f.channelRosterName="Саша";val trip=SharingFixture(f)
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        val voice=voiceController(f,guard);val sharing=sharingController(f,trip,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val activity=SupervisorJob(backgroundScope.coroutineContext[Job])
        val boundary=launchBoundary(f,activity)
        val root=NearbyRootPresenter(CoroutineScope(backgroundScope.coroutineContext+ui),voice,sharing)
        var replacement:NearbyRootPresenter?=null
        try {
            val account=f.login();root.connectChannel(account,channel,"Channel");runCurrent()
            val request=assertNotNull(voice.state.value.pending).id
            voice.begin(request,true,boundary);runCurrent()
            val operation=VoiceOperationId(request)
            assertEquals(operation,root.state.value.voice.operation)
            assertTrue(root.state.value.voice.channelMap.observed)
            assertEquals(1,root.state.value.voice.channelRoster?.connectedCount)
            assertTrue(nearbyChannelRosterPanel(root.state.value.voice).known)
            assertEquals(mapOf(f.peer to "Саша"),nearbyChannelRosterPanel(root.state.value.voice).profileNames)
            val mutesBefore=f.mutes.toList();val locationBefore=trip.source.issued.toList()
            val old=root.actions;root.close();runCurrent();assertEquals(NearbyRootView(),root.state.value)
            old.disconnect(operation);old.mute(operation,true);old.startTrip(operation);runCurrent()
            assertEquals(operation,voice.state.value.operation);assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertFalse(f.channelRosters.single().state.value.retired)
            assertEquals(1,voice.state.value.channelRoster?.connectedCount)
            assertEquals(mutesBefore,f.mutes);assertEquals(locationBefore,trip.source.issued);assertTrue(account.job.isActive)
            val fresh=NearbyRootPresenter(CoroutineScope(backgroundScope.coroutineContext+ui),voice,sharing);replacement=fresh;runCurrent()
            assertEquals(operation,fresh.state.value.voice.operation)
            assertSame(f.channelRosters.single().state.value,fresh.state.value.voice.channelRoster)
            assertEquals(mapOf(f.peer to "Саша"),nearbyChannelRosterPanel(fresh.state.value.voice).profileNames)
            fresh.actions.disconnect(operation);runCurrent();assertFalse(assertNotNull(f.runtime).state.value.wanted)
            assertNull(fresh.state.value.voice.operation)
            assertNull(fresh.state.value.voice.channelRoster);assertTrue(f.channelRosters.single().state.value.retired)
            assertEquals(NearbyPanelConnection.HIDDEN,nearbyConversationPanel(fresh.state.value.voice).connection)
            assertTrue(account.job.isActive)
        } finally {replacement?.close();root.close();boundary.close();ui.cancel();activity.cancel();sharing.close();voice.close();f.close()}
    }

    @Test fun rootPresentationClearsNaturallyStoppedConversationWithoutRetiringAuthentication() = runTest {
        val f=Fixture(this);val trip=SharingFixture(f);val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        val voice=voiceController(f,guard);val sharing=sharingController(f,trip,guard)
        val activity=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,activity)
        val root=NearbyRootPresenter(backgroundScope,voice,sharing)
        try {
            val account=f.login();root.connectChannel(account,channel,"Channel");runCurrent()
            val request=assertNotNull(voice.state.value.pending).id;voice.begin(request,true,boundary);runCurrent()
            assertEquals(VoiceOperationId(request),root.state.value.voice.operation)
            assertTrue(f.gate.stop(assertNotNull(f.gate.current.value)));runCurrent()
            assertEquals(VoiceConversationPhase.STOPPED,f.owner.state.value.phase)
            assertFalse(assertNotNull(f.runtime).state.value.wanted)
            assertNull(root.state.value.voice.operation)
            assertEquals(NearbyPanelConnection.HIDDEN,nearbyConversationPanel(root.state.value.voice).connection)
            assertTrue(account.job.isActive)
        } finally {root.close();boundary.close();activity.cancel();sharing.close();voice.close();f.close()}
    }

    @Test fun cancelledRootParentWithdrawsViewBeforeHeldChildCompletesWithoutStoppingVoice() = runTest {
        val f=Fixture(this);val trip=SharingFixture(f);val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher)
        val voice=voiceController(f,guard);val sharing=sharingController(f,trip,guard)
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job]);val activity=SupervisorJob(backgroundScope.coroutineContext[Job])
        val boundary=launchBoundary(f,activity);val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        val parent=CoroutineScope(backgroundScope.coroutineContext+ui)
        val root=NearbyRootPresenter(parent,voice,sharing)
        parent.launch {try {awaitCancellation()} finally {withContext(NonCancellable) {entered.complete(Unit);release.await()}}}
        try {
            val account=f.login();root.connectChannel(account,channel,"Channel");runCurrent()
            val request=assertNotNull(voice.state.value.pending).id;voice.begin(request,true,boundary);runCurrent()
            assertEquals(VoiceOperationId(request),root.state.value.voice.operation)
            ui.cancel();runCurrent();assertTrue(entered.isCompleted);assertFalse(ui.isCompleted)
            assertEquals(NearbyRootView(),root.state.value)
            root.actions.disconnect(VoiceOperationId(request));runCurrent()
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
        } finally {release.complete(Unit);runCurrent();root.close();boundary.close();ui.cancel();activity.cancel();sharing.close();voice.close();f.close()}
    }

    @Test fun conversationPublishesOnlyItsActualChannelAudienceAndWithdrawsOnStop() = runTest {
        val f=Fixture(this,withAudience=true)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            assertTrue(assertNotNull(f.owner.state.value.sharingAudience).observed)
            assertEquals(1,f.paths.count {it.endsWith("/sharing/audience")})
            val runtime=assertNotNull(f.runtime);assertFalse(runtime.state.value.sharingEnabled)
            f.owner.stop();runCurrent();assertNull(f.owner.state.value.sharingAudience)
            val issued=f.paths.count {it.endsWith("/sharing/audience")};advanceTimeBy(2500);runCurrent()
            assertEquals(issued,f.paths.count {it.endsWith("/sharing/audience")})
            assertTrue(account.job.isActive)
            f.callMode=true
            f.owner.start(account,VoiceConversationTarget.OutgoingCall(channel,f.peer,f.key),second,
                readPreferences={phone},launch=f::launch);runCurrent()
            assertIs<VoiceConversationTarget.OutgoingCall>(f.owner.state.value.target)
            assertNull(f.owner.state.value.sharingAudience)
            assertEquals(issued,f.paths.count {it.endsWith("/sharing/audience")})
        } finally {f.close()}
    }

    @Test fun obsoleteHeldAudienceFactoryCannotRepaintOrStopReplacementChannel() = runTest {
        val f=Fixture(this,withAudience=true);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        f.afterAudience={if(f.audiences.size==1) withContext(NonCancellable) {entered.complete(Unit);release.await()}}
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            assertTrue(entered.isCompleted);assertTrue(f.audiences.single().state.value.observed)
            assertNull(f.owner.state.value.sharingAudience)
            f.owner.start(account,channel,second,replace=true,readPreferences={phone},launch=f::launch);runCurrent()
            assertTrue(assertNotNull(f.owner.state.value.sharingAudience).observed)
            val ticket=assertNotNull(f.gate.current.value)
            release.complete(Unit);runCurrent()
            assertFalse(f.audiences.first().state.value.observed)
            assertTrue(f.audiences.last().state.value.observed)
            assertEquals(second,f.owner.state.value.operationId);assertSame(ticket,f.gate.current.value)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
            assertEquals(2,f.paths.count {it.endsWith("/sharing/audience")})
        } finally {release.complete(Unit);runCurrent();f.close()}
    }

    @Test fun channelAudienceConstructionFailureDoesNotDisconnectBorrowedVoice() = runTest {
        val f=Fixture(this,withAudience=true);var issued=false
        f.beforeAudience={issued=true;throw IllegalStateException("reader unavailable")}
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            assertTrue(issued);assertNull(f.owner.state.value.sharingAudience)
            assertEquals(VoiceConversationPhase.JOIN_SUBMITTED,f.owner.state.value.phase)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(account.job.isActive)
            assertNotNull(f.gate.current.value);assertEquals(0,f.closes)
        } finally {f.close()}
    }

    @Test fun audienceSessionAutomaticallyReadsOriginalChannelWithoutStartingLocationOrSharing() = runTest {
        val f=Fixture(this);var audience: ChannelSharingAudienceSession?=null
        f.authToken="original"
        val ui=SupervisorJob(backgroundScope.coroutineContext[Job])
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val source=runtime.state.value
            val original=VoiceSessionTag(assertNotNull(source.sessionId),source.generation)
            val screen=CoroutineScope(backgroundScope.coroutineContext+ui).async {
                ChannelSharingAudienceSession.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time})
            }
            runCurrent();val reader=screen.await();audience=reader;ui.cancel();runCurrent()
            assertTrue(reader.state.value.observed)
            assertEquals(listOf<String?>("Bearer original"),f.sharingAuthorizations)
            assertEquals(listOf("/v2/nearby/channel-sessions/${original.sessionId.value}/sharing/audience"),
                f.paths.filter {it.contains("/sharing/")})
            assertFalse(runtime.state.value.sharingEnabled);assertTrue(runtime.state.value.wanted)
            val issued=f.paths.size;reader.close();runCurrent();advanceTimeBy(2500);runCurrent()
            assertEquals(issued,f.paths.size);assertFalse(reader.state.value.observed)
            assertTrue(account.job.isActive);assertFalse(runtime.retired.value);assertEquals(0,f.closes)
        } finally {ui.cancel();audience?.close();f.close()}
    }

    @Test fun channelRosterUsesActualOriginalAccountAndStopClearsOnlyReadEvidence() = runTest {
        val f=Fixture(this,withChannelRoster=true)
        try {
            val account=f.login();assertTrue(f.channelRosters.isEmpty())
            f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val roster=assertNotNull(f.owner.state.value.channelRoster)
            assertEquals(1,roster.connectedCount);assertEquals(f.peer,assertNotNull(roster.peers).single().peer.actorId)
            assertEquals(listOf<String?>("Bearer token"),f.channelRosterAuthorizations)
            assertEquals(listOf("/v2/nearby/channels/$channel/roster"),f.paths.filter {it.endsWith("/roster")})
            assertEquals(1,f.allocated.size);assertEquals(1,f.launches)
            f.owner.stop(first);runCurrent();assertNull(f.owner.state.value.channelRoster)
            assertTrue(f.channelRosters.single().state.value.retired);assertTrue(account.job.isActive)
            f.callMode=true
            f.owner.start(account,VoiceConversationTarget.OutgoingCall(channel,f.peer,f.key),second,
                readPreferences={phone},launch=f::launch);runCurrent()
            assertEquals(1,f.channelRosters.size);assertNull(f.owner.state.value.channelRoster)
            assertNotNull(f.owner.state.value.callRoster)
        } finally {f.close()}
    }
    @Test fun channelRosterOldHeldReplyCannotOverwriteReplacementChannel() = runTest {
        val f=Fixture(this,withChannelRoster=true);val release=CompletableDeferred<Unit>()
        try {
            val account=f.login();f.channelRosterRelease=release
            f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            assertTrue(f.channelRosterEntered.isCompleted);assertEquals(1,f.channelRosterAuthorizations.size)
            f.channelRosterRelease=null
            f.owner.start(account,second.value,second,readPreferences={phone},launch=f::launch);runCurrent()
            assertEquals(second,f.owner.state.value.operationId);assertEquals(1,f.owner.state.value.channelRoster?.connectedCount)
            release.complete(Unit);runCurrent()
            assertEquals(second,f.owner.state.value.operationId);assertEquals(1,f.owner.state.value.channelRoster?.connectedCount)
            assertTrue(f.channelRosters.first().state.value.retired);assertFalse(f.channelRosters.last().state.value.retired)
            assertEquals(2,f.channelRosterAuthorizations.size);assertTrue(account.job.isActive)
        } finally {release.complete(Unit);f.close()}
    }
    @Test fun channelRosterSameUserNewLoginRejectsHeldOriginalAccountReply() = runTest {
        val f=Fixture(this,withChannelRoster=true);val release=CompletableDeferred<Unit>()
        try {
            val old=f.login();f.channelRosterRelease=release;f.channelRosterName="Old"
            f.owner.start(old,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            assertTrue(f.channelRosterEntered.isCompleted)
            f.repository.logout();runCurrent();assertNull(f.owner.state.value.channelRoster)
            val fresh=f.login();assertEquals(old.userId,fresh.userId);assertNotSame(old,fresh)
            f.channelRosterRelease=null;f.channelRosterName="Fresh"
            f.owner.start(fresh,channel,second,readPreferences={phone},launch=f::launch);runCurrent()
            val reader=f.channelRosters.last();assertSame(reader.state.value,f.owner.state.value.channelRoster)
            assertEquals(1,reader.state.value.connectedCount)
            assertEquals(mapOf(f.peer to "Fresh"),reader.state.value.profileNames)
            release.complete(Unit);runCurrent()
            assertSame(reader.state.value,f.owner.state.value.channelRoster)
            assertEquals(mapOf(f.peer to "Fresh"),f.owner.state.value.channelRoster?.profileNames)
            assertEquals(second,f.owner.state.value.operationId);assertTrue(f.channelRosters.first().state.value.retired)
            assertFalse(reader.state.value.retired);assertTrue(fresh.job.isActive);assertEquals(2,f.channelRosterAuthorizations.size)
        } finally {release.complete(Unit);f.close()}
    }
    @Test fun channelRosterExpiredAndDeniedReadsDoNotFabricatePeersOrDisconnectVoice() = runTest {
        val f=Fixture(this,withChannelRoster=true)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            assertEquals(1,f.owner.state.value.channelRoster?.connectedCount)
            f.time=10_100;f.channelRosterStatus=HttpStatusCode.ServiceUnavailable
            f.channelRosterPulses.emit(Unit);runCurrent()
            assertEquals(VoiceRosterUnknown.UNAVAILABLE,f.owner.state.value.channelRoster?.unknown)
            assertNull(f.owner.state.value.channelRoster?.peers);assertTrue(f.runtime!!.state.value.wanted)
            f.channelRosterStatus=HttpStatusCode.Forbidden;f.time+=5000;f.channelRosterPulses.emit(Unit);runCurrent()
            assertTrue(f.channelRosters.single().state.value.retired);assertTrue(f.runtime!!.state.value.wanted)
            assertEquals(VoiceConversationPhase.JOIN_SUBMITTED,f.owner.state.value.phase);assertTrue(account.job.isActive)
        } finally {f.close()}
    }

    @Test fun audienceSessionPollsActualReadsAndExpiresPointsDuringNetworkFailure() = runTest {
        val f=Fixture(this);var audience: ChannelSharingAudienceSession?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val source=runtime.state.value
            f.sharingAudienceBody="""{"channelId":"$channel","serverTimeEpochMillis":1100,"peers":[{"actorId":"${f.peer}","sessionId":"${f.peer}","generation":"${f.key}","mode":"LOCATION","packet":{"sequence":7,"capturedAtEpochMillis":1000,"location":{"latitude":55.0,"longitude":60.0,"accuracyMeters":4.0,"capturedAtEpochMillis":1000,"staleAfterEpochMillis":2100}},"remainingMillis":1000}]}"""
            val reader=ChannelSharingAudienceSession.create(account,runtime,VoiceSessionTag(assertNotNull(source.sessionId),source.generation),
                f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});audience=reader;runCurrent()
            assertEquals(1,reader.state.value.peers.size)
            assertEquals(55.0,assertNotNull(reader.state.value.peers.single().packet.location).latitude)
            val original=reader.state.value.peers.single()
            f.sharingAudienceBody=assertNotNull(f.sharingAudienceBody).replace("\"mode\":\"LOCATION\"", "\"displayName\":\"Саша\",\"mode\":\"LOCATION\"")
            f.time=500;advanceTimeBy(2001);runCurrent()
            val renamed=reader.state.value.peers.single();assertEquals("Саша",renamed.displayName)
            assertEquals(original.packet,renamed.packet);assertEquals(original.deadlineMillis,renamed.deadlineMillis)
            f.sharingAudienceStatus=HttpStatusCode.ServiceUnavailable;f.time=1100
            advanceTimeBy(2000);runCurrent()
            assertTrue(reader.state.value.peers.isEmpty());assertTrue(reader.state.value.observed)
            assertEquals(3,f.paths.count {it.endsWith("/sharing/audience")})
            assertTrue(account.job.isActive);assertTrue(runtime.state.value.wanted)
        } finally {audience?.close();f.close()}
    }

    @Test fun audienceSessionWithdrawsHeldReadOnReplacementWithoutStoppingNewVoice() = runTest {
        val f=Fixture(this);var audience: ChannelSharingAudienceSession?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val source=runtime.state.value
            f.sharingPositiveRelease=CompletableDeferred()
            val reader=ChannelSharingAudienceSession.create(account,runtime,VoiceSessionTag(assertNotNull(source.sessionId),source.generation),
                f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});audience=reader;runCurrent()
            assertTrue(f.sharingPositiveEntered.isCompleted)
            f.owner.start(account,channel,second,replace=true,readPreferences={phone},launch=f::launch);runCurrent()
            assertFalse(reader.state.value.observed)
            f.sharingPositiveRelease!!.complete(Unit);runCurrent();advanceTimeBy(2500);runCurrent()
            assertFalse(reader.state.value.observed);assertTrue(reader.state.value.peers.isEmpty())
            assertEquals(1,f.paths.count {it.endsWith("/sharing/audience")})
            assertEquals(second,runtime.state.value.operationId);assertTrue(runtime.state.value.wanted)
            assertTrue(account.job.isActive)
        } finally {f.sharingPositiveRelease?.complete(Unit);runCurrent();audience?.close();f.close()}
    }

    @Test fun audienceSessionRejectsWrongGenerationAndFriendCallBeforeReading() = runTest {
        val f=Fixture(this)
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val source=runtime.state.value
            assertFailsWith<IllegalStateException> {
                ChannelSharingAudienceSession.create(account,runtime,VoiceSessionTag(assertNotNull(source.sessionId),VoiceGeneration(source.generation.value+1)),
                    f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time})
            }
            f.callMode=true
            f.owner.stop();runCurrent()
            f.owner.start(account,VoiceConversationTarget.OutgoingCall(f.callDestination,f.peer,f.key),second,
                readPreferences={phone},launch=f::launch);runCurrent()
            val call=runtime.state.value
            assertEquals(second,call.operationId)
            assertIs<VoiceConversationTarget.OutgoingCall>(f.owner.state.value.target)
            assertFailsWith<IllegalStateException> {
                ChannelSharingAudienceSession.create(account,runtime,VoiceSessionTag(assertNotNull(call.sessionId),call.generation),
                    f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time})
            }
            assertTrue(f.sharingAuthorizations.isEmpty());assertTrue(account.job.isActive)
        } finally {f.close()}
    }

    @Test fun audienceSessionRetiresHeldReadBeforeSameUserLoginAndCannotBorrowFreshBearer() = runTest {
        val f=Fixture(this);f.authToken="original";var audience: ChannelSharingAudienceSession?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val source=runtime.state.value
            val reader=ChannelSharingAudienceSession.create(account,runtime,VoiceSessionTag(assertNotNull(source.sessionId),source.generation),
                f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});audience=reader;runCurrent()
            assertTrue(reader.state.value.observed)
            f.sharingPositiveRelease=CompletableDeferred();advanceTimeBy(2000);runCurrent()
            assertEquals(2,f.paths.count {it.endsWith("/sharing/audience")})
            f.repository.logout();runCurrent();assertFalse(account.job.isActive)
            assertFalse(reader.state.value.observed)
            f.authToken="fresh";val replacement=f.login()
            assertEquals(account.userId,replacement.userId);assertNotSame(account,replacement)
            f.owner.start(replacement,channel,second,readPreferences={phone},launch=f::launch);runCurrent()
            f.sharingPositiveRelease!!.complete(Unit);runCurrent();advanceTimeBy(2500);runCurrent()
            assertFalse(reader.state.value.observed);assertTrue(reader.state.value.peers.isEmpty())
            assertEquals(listOf<String?>("Bearer original","Bearer original"),f.sharingAuthorizations)
            assertEquals(second,assertNotNull(f.runtime).state.value.operationId)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(replacement.job.isActive)
        } finally {f.sharingPositiveRelease?.complete(Unit);runCurrent();audience?.close();f.close()}
    }

    @Test fun rootSharingAudienceCoalescesReadsAndSurvivesCancelledUiWaiterThenExpiresWithoutHttp() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var audience: ChannelSharingAudienceOwner?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingAudienceOwner.create(account,runtime,original,control,f.dispatcher,{f.time});audience=owner
            f.sharingAudienceBody="""{"channelId":"$channel","serverTimeEpochMillis":1100,"peers":[{"actorId":"${f.peer}","sessionId":"${f.peer}","generation":"${f.key}","mode":"LOCATION","packet":{"sequence":7,"capturedAtEpochMillis":1000,"location":null},"remainingMillis":1000}]}"""
            f.sharingPositiveRelease=CompletableDeferred();val read=owner.refresh();val waiter=async {read.await()};runCurrent()
            assertTrue(f.sharingPositiveEntered.isCompleted);waiter.cancel();assertSame(read,owner.refresh())
            assertEquals(1,f.paths.count {it.endsWith("/sharing/audience")})
            f.sharingPositiveRelease!!.complete(Unit);runCurrent();assertTrue(read.await());assertTrue(owner.state.value.observed)
            assertEquals(1,owner.state.value.peers.size);val issued=f.paths.size
            f.time=1100;owner.tick();assertTrue(owner.state.value.peers.isEmpty());assertEquals(issued,f.paths.size)
            assertTrue(owner.refresh().await());assertTrue(owner.state.value.peers.isEmpty(),"Repeated expired packet cannot acquire a fresh deadline")
            assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive)
            owner.close();assertFalse(runtime.retired.value);assertIs<ChannelSharingPacketReply.Audience>(control.audience())
        } finally {f.sharingPositiveRelease?.complete(Unit);runCurrent();audience?.close();sharing?.close();f.close()}
    }

    @Test fun rootSharingAudienceRetiresOnDisconnectAndRejectsForeignBorrowedControl() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var audience: ChannelSharingAudienceOwner?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            assertFailsWith<IllegalArgumentException> {ChannelSharingAudienceOwner.create(account,runtime,original.copy(generation=VoiceGeneration(original.generation.value+1)),control,f.dispatcher,{f.time})}
            val owner=ChannelSharingAudienceOwner.create(account,runtime,original,control,f.dispatcher,{f.time});audience=owner
            assertTrue(owner.refresh().await());assertTrue(owner.state.value.observed)
            f.owner.stop();runCurrent();owner.tick();assertTrue(owner.retired.value);assertFalse(owner.state.value.observed)
            val issued=f.paths.size;assertFalse(owner.refresh().await());assertEquals(issued,f.paths.size);assertTrue(account.job.isActive)
        } finally {audience?.close();sharing?.close();f.close()}
    }

    @Test fun rootSharingAudienceOldHeldReadCannotPublishAcrossReloginOrRetireNewVoice() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var audience: ChannelSharingAudienceOwner?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingAudienceOwner.create(account,runtime,original,control,f.dispatcher,{f.time});audience=owner
            f.sharingPositiveRelease=CompletableDeferred();val read=owner.refresh();runCurrent();assertTrue(f.sharingPositiveEntered.isCompleted)
            f.repository.logout();runCurrent();f.authToken="replacement-token";val replacement=f.login()
            f.owner.start(replacement,channel,second,readPreferences={phone},launch=f::launch);runCurrent();val newer=assertNotNull(f.runtime)
            f.sharingPositiveRelease!!.complete(Unit);runCurrent();assertTrue(read.isCancelled);assertTrue(owner.retired.value);assertFalse(owner.state.value.observed)
            owner.close();assertTrue(newer.state.value.wanted);assertTrue(replacement.job.isActive);assertFalse(newer.retired.value)
        } finally {f.sharingPositiveRelease?.complete(Unit);runCurrent();audience?.close();sharing?.close();f.close()}
    }

    @Test fun rootSharingAudienceAuthorizationLossClearsDataWithoutStoppingBorrowedVoice() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null;var audience: ChannelSharingAudienceOwner?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value;val original=VoiceSessionTag(assertNotNull(state.sessionId),state.generation)
            val control=ChannelSharingAccountControl.create(account,runtime,original,f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time});sharing=control
            val owner=ChannelSharingAudienceOwner.create(account,runtime,original,control,f.dispatcher,{f.time});audience=owner
            assertTrue(owner.refresh().await());assertTrue(owner.state.value.observed)
            f.sharingAudienceStatus=HttpStatusCode.Forbidden
            assertFailsWith<CancellationException> {owner.refresh().await()}
            assertTrue(owner.retired.value);assertFalse(owner.state.value.observed);assertTrue(owner.state.value.peers.isEmpty())
            assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive);assertFalse(runtime.retired.value)
        } finally {audience?.close();sharing?.close();f.close()}
    }

    @Test fun sharingPacketsRequireEarnedConsentAndLocalHideFencesGenerationWithoutStoppingVoice() = runTest {
        val f=Fixture(this); var sharing: ChannelSharingAccountControl?=null
        try {
            val account=f.login(); f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime); val state=runtime.state.value
            val control=ChannelSharingAccountControl.create(account,runtime,VoiceSessionTag(assertNotNull(state.sessionId),state.generation),f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time})
            sharing=control
            val packet=ChannelSharingPacket(7,1000,null)
            val issued=f.paths.size
            assertEquals(ChannelSharingFailure.ACCESS_DENIED,assertIs<ChannelSharingPacketReply.Failed>(control.publish(f.key,packet)).reason)
            assertEquals(issued,f.paths.size)
            assertIs<ChannelSharingReply.Active>(control.start(f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR))
            assertIs<ChannelSharingPacketReply.Published>(control.publish(f.key,packet))
            assertEquals(channel,assertIs<ChannelSharingPacketReply.Audience>(control.audience()).channelId)
            assertIs<ChannelSharingReply.Negative>(control.requestRevoke(f.key).await())
            val afterHide=f.paths.size
            assertEquals(ChannelSharingFailure.ACCESS_DENIED,assertIs<ChannelSharingPacketReply.Failed>(control.publish(f.key,packet)).reason)
            assertEquals(ChannelSharingFailure.ACCESS_DENIED,assertIs<ChannelSharingReply.Failed>(control.start(f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR)).reason)
            assertEquals(afterHide,f.paths.size);assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive)
            val fresh=assertIs<ChannelSharingReply.Active>(control.start(f.peer,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR)).observation
            assertIs<ChannelSharingPacketReply.Published>(control.publish(f.peer,packet))
            assertIs<ChannelSharingReply.Negative>(control.requestRevoke(f.key).await())
            assertIs<ChannelSharingPacketReply.Published>(control.publish(f.peer,packet))
            f.time=fresh.deadlineMillis;val beforeExpiry=f.paths.size
            assertEquals(ChannelSharingFailure.ACCESS_DENIED,assertIs<ChannelSharingPacketReply.Failed>(control.publish(f.peer,packet)).reason)
            assertEquals(beforeExpiry,f.paths.size);assertTrue(runtime.canShare(VoiceSessionTag(assertNotNull(state.sessionId),state.generation)))
            assertTrue(control.close());assertFalse(runtime.retired.value)
        } finally {sharing?.close();f.close()}
    }

    @Test fun heldSharingPacketCannotSurviveLocalGenerationWithdrawal() = runTest {
        val f=Fixture(this); var sharing: ChannelSharingAccountControl?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value
            val control=ChannelSharingAccountControl.create(account,runtime,VoiceSessionTag(assertNotNull(state.sessionId),state.generation),f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time})
            sharing=control;assertIs<ChannelSharingReply.Active>(control.start(f.key,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR))
            f.sharingPositiveRelease=CompletableDeferred()
            val pending=async {control.publish(f.key,ChannelSharingPacket(7,1000,null))};runCurrent()
            assertTrue(f.paths.last().endsWith("/packet"));assertFalse(pending.isCompleted)
            assertIs<ChannelSharingReply.Negative>(control.requestRevoke(f.key).await())
            f.sharingPositiveRelease!!.complete(Unit);runCurrent();assertTrue(pending.isCancelled)
            assertTrue(runtime.state.value.wanted);assertTrue(account.job.isActive)
        } finally {f.sharingPositiveRelease?.complete(Unit);runCurrent();sharing?.close();f.close()}
    }

    @Test fun oldHeldSharingAudienceCannotReturnAfterSameUserRelogin() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value
            val control=ChannelSharingAccountControl.create(account,runtime,VoiceSessionTag(assertNotNull(state.sessionId),state.generation),f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time})
            sharing=control;f.sharingPositiveRelease=CompletableDeferred()
            val pending=async {control.audience()};runCurrent();assertTrue(f.sharingPositiveEntered.isCompleted)
            f.repository.logout();runCurrent();f.authToken="replacement-token";val replacement=f.login()
            f.owner.start(replacement,channel,second,readPreferences={phone},launch=f::launch);runCurrent();val newer=assertNotNull(f.runtime)
            f.sharingPositiveRelease!!.complete(Unit);runCurrent();assertTrue(pending.isCancelled)
            assertTrue(newer.state.value.wanted);assertTrue(replacement.job.isActive)
            assertTrue(control.close());assertFalse(newer.retired.value)
        } finally {f.sharingPositiveRelease?.complete(Unit);runCurrent();sharing?.close();f.close()}
    }

    @Test fun explicitSharingChildCloseFencesHeldAudienceAndPreservesBorrowedVoice() = runTest {
        val f=Fixture(this);var sharing: ChannelSharingAccountControl?=null
        try {
            val account=f.login();f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            val runtime=assertNotNull(f.runtime);val state=runtime.state.value
            val control=ChannelSharingAccountControl.create(account,runtime,VoiceSessionTag(assertNotNull(state.sessionId),state.generation),f.client,"https://example.test/v2/nearby",f.dispatcher,{f.time})
            sharing=control;f.sharingPositiveRelease=CompletableDeferred()
            val pending=async {control.audience()};runCurrent();assertTrue(f.sharingPositiveEntered.isCompleted)
            assertTrue(control.close());assertFalse(control.cleanupFailed.value)
            f.sharingPositiveRelease!!.complete(Unit);runCurrent();assertTrue(pending.isCancelled)
            assertTrue(account.job.isActive);assertTrue(runtime.state.value.wanted);assertFalse(runtime.retired.value)
        } finally {f.sharingPositiveRelease?.complete(Unit);runCurrent();sharing?.close();f.close()}
    }

    @Test fun expiredEarnedVoiceLeaseCannotIssueSharingEvenWhileLocalVoiceRemainsWanted() = runTest {
        val f = Fixture(this); var sharing: ChannelSharingAccountControl? = null
        try {
            val account = f.login(); f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch); runCurrent()
            val runtime = assertNotNull(f.runtime); val state = runtime.state.value
            val original = VoiceSessionTag(assertNotNull(state.sessionId), state.generation)
            val control = ChannelSharingAccountControl.create(account, runtime, original, f.client, "https://example.test/v2/nearby", f.dispatcher, {f.time})
            sharing = control; assertTrue(runtime.canShare(original))
            val issued = f.paths.size; f.time = assertNotNull(state.leaseDeadlineMillis)
            assertTrue(runtime.state.value.wanted); assertFalse(runtime.canShare(original))
            assertFailsWith<CancellationException> {control.start(f.key, ChannelSharingMode.FULL, ChannelSharingTtl.EIGHT_HOURS)}
            assertEquals(issued, f.paths.size); assertTrue(f.sharingAuthorizations.isEmpty())
            assertEquals(ChannelSharingFailure.ACCESS_DENIED, assertIs<ChannelSharingReply.Failed>(control.requestRevoke(f.key).await()).reason)
            assertTrue(control.close()); assertFalse(control.cleanupFailed.value)
            assertTrue(account.job.isActive); assertTrue(runtime.state.value.wanted); assertFalse(runtime.retired.value)
        } finally {sharing?.close(); f.close()}
    }

    @Test fun originalSharingRevokeSurvivesVoiceDisconnectLogoutAndCancelledUiWaiter() = runTest {
        val f = Fixture(this); var sharing: ChannelSharingAccountControl? = null
        try {
            val account = f.login(); f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch); runCurrent()
            val runtime = assertNotNull(f.runtime); val state = runtime.state.value
            val original = VoiceSessionTag(assertNotNull(state.sessionId), state.generation)
            val control = ChannelSharingAccountControl.create(account, runtime, original, f.client, "https://example.test/v2/nearby", f.dispatcher, {f.time})
            sharing = control
            assertIs<ChannelSharingReply.Active>(control.start(f.key, ChannelSharingMode.LOCATION, ChannelSharingTtl.HALF_HOUR))
            f.owner.stop(); runCurrent(); control.retireAdmission(); f.repository.logout(); runCurrent()
            f.authToken = "replacement-token"; f.login(); f.sharingRevokeRelease = CompletableDeferred()
            val negative = control.requestRevoke(f.key); val waiter = async {negative.await()}; runCurrent()
            assertTrue(f.sharingRevokeEntered.isCompleted); waiter.cancel()
            val issued = f.paths.size
            assertEquals(ChannelSharingFailure.ACCESS_DENIED, assertIs<ChannelSharingReply.Failed>(control.requestRevoke(f.peer).await()).reason)
            assertEquals(issued, f.paths.size); assertFalse(control.close())
            f.sharingRevokeRelease!!.complete(Unit); runCurrent()
            assertIs<ChannelSharingReply.Negative>(negative.await())
            assertEquals<List<String?>>(listOf("Bearer token", "Bearer token"), f.sharingAuthorizations)
            assertTrue(control.close())
            assertTrue(assertNotNull(f.repository.authenticatedAccount.value).job.isActive)
        } finally {f.sharingRevokeRelease?.complete(Unit); runCurrent(); sharing?.close(); f.close()}
    }

    @Test fun oldHeldSharingPositiveCannotReturnPermissionAfterReloginButRemainsOriginallyRevocable() = runTest {
        val f = Fixture(this); var sharing: ChannelSharingAccountControl? = null
        try {
            val account = f.login(); f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch); runCurrent()
            val runtime = assertNotNull(f.runtime); val state = runtime.state.value
            val control = ChannelSharingAccountControl.create(account, runtime, VoiceSessionTag(assertNotNull(state.sessionId), state.generation),
                f.client, "https://example.test/v2/nearby", f.dispatcher, {f.time})
            sharing = control; f.sharingPositiveRelease = CompletableDeferred()
            val positive = async {control.start(f.key, ChannelSharingMode.LOCATION, ChannelSharingTtl.HALF_HOUR)}
            runCurrent(); assertTrue(f.sharingPositiveEntered.isCompleted)
            f.repository.logout(); runCurrent(); f.authToken = "new-login-token"; val current = f.login()
            f.owner.start(current, channel, second, readPreferences = {phone}, launch = f::launch); runCurrent()
            val freshRuntime = assertNotNull(f.runtime)
            f.sharingPositiveRelease!!.complete(Unit); runCurrent(); assertTrue(positive.isCancelled)
            assertIs<ChannelSharingReply.Negative>(control.requestRevoke(f.key).await())
            assertEquals<List<String?>>(listOf("Bearer token", "Bearer token"), f.sharingAuthorizations)
            assertTrue(current.job.isActive); assertFalse(freshRuntime.retired.value); assertTrue(freshRuntime.state.value.wanted)
            assertTrue(control.close()); assertTrue(freshRuntime.state.value.wanted)
        } finally {f.sharingPositiveRelease?.complete(Unit); runCurrent(); sharing?.close(); f.close()}
    }

    @Test fun sharingControlRejectsWrongAccountOriginalTagAndTypedCallBeforeSharingHttp() = runTest {
        val f = Fixture(this); val wrongJob = Job()
        try {
            val account = f.login(); f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch); runCurrent()
            val runtime = assertNotNull(f.runtime); val state = runtime.state.value
            val original = VoiceSessionTag(assertNotNull(state.sessionId), state.generation)
            val wrong = SocialAuthenticatedAccount(account.userId, wrongJob, backgroundScope.coroutineContext) {"wrong"}
            val issued = f.paths.size
            assertFailsWith<IllegalArgumentException> {ChannelSharingAccountControl.create(wrong, runtime, original, f.client, "https://example.test/v2/nearby", f.dispatcher, {f.time})}
            assertFailsWith<IllegalStateException> {ChannelSharingAccountControl.create(account, runtime, original.copy(sessionId = VoiceSessionId(channel)), f.client, "https://example.test/v2/nearby", f.dispatcher, {f.time})}
            assertEquals(issued, f.paths.size)
            f.owner.stop(); runCurrent(); f.callMode = true
            f.owner.start(account, VoiceConversationTarget.OutgoingCall(channel, f.peer, f.key), second, readPreferences = {phone}, launch = f::launch); runCurrent()
            val callState = runtime.state.value; val callTag = VoiceSessionTag(assertNotNull(callState.sessionId), callState.generation)
            assertEquals(original.sessionId, callTag.sessionId, "Same UUID fixture must not substitute for typed CHANNEL authority")
            assertFailsWith<IllegalStateException> {ChannelSharingAccountControl.create(account, runtime, callTag, f.client, "https://example.test/v2/nearby", f.dispatcher, {f.time})}
            assertTrue(f.sharingAuthorizations.isEmpty()); assertTrue(runtime.state.value.wanted)
        } finally {wrongJob.cancel(); f.close()}
    }

    @Test fun discoveryStartsOnceAndDoesNotReissueConsumedNotificationOrAllocateAudio() = runTest {
        val f=Fixture(this);val port=Notifications();val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer}
        var actual:IncomingCallNotificationOwner?=null
        val notifications=IncomingCallAccountCoordinator(f.dispatcher,registry,IncomingCallNotificationFactory {account,runtime ->
            val owner=IncomingCallNotificationOwner(account,runtime,f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key})
            actual=owner;IncomingCallNotificationAttachment(owner,registry.bind(account,owner))
        })
        val discovery=IncomingCallDiscovery(f.repository,f.dispatcher,notifications,f::acquire)
        try {
            discovery.start();discovery.start();runCurrent();assertTrue(f.paths.isEmpty())
            f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING";val account=f.login();runCurrent()
            val owner=assertNotNull(actual);val ticket=assertNotNull(owner.state.value.ticket)
            assertEquals(listOf("/v2/nearby/calls/incoming","/v2/nearby/calls/$channel"),f.paths)
            assertNotNull(owner.decline(ticket,first));runCurrent()
            advanceTimeBy(2_001);runCurrent()
            assertEquals(1,port.shown.size);assertNull(owner.state.value.ticket)
            assertEquals(2,f.paths.count {it.endsWith("/incoming")})
            assertTrue(account.job.isActive);assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty())
        } finally {discovery.close();notifications.retireAll();registry.close();f.close()}
    }

    @Test fun discoveryLogoutRetiresNotificationsAndSameUserLoginGetsFreshReader() = runTest {
        val f=Fixture(this);val port=Notifications();var nonce=0
        val registry=IncomingCallActionRegistry(f.dispatcher) {"a0000000-0000-0000-0000-${(++nonce).toString().padStart(12,'0')}"}
        val owners=mutableListOf<IncomingCallNotificationOwner>()
        val notifications=IncomingCallAccountCoordinator(f.dispatcher,registry,IncomingCallNotificationFactory {account,runtime ->
            val owner=IncomingCallNotificationOwner(account,runtime,f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key})
            owners+=owner;IncomingCallNotificationAttachment(owner,registry.bind(account,owner))
        })
        val discovery=IncomingCallDiscovery(f.repository,f.dispatcher,notifications,f::acquire)
        try {
            f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING";val old=f.login()
            discovery.start();runCurrent();assertEquals(1,owners.size);assertNotNull(owners.single().state.value.ticket)
            f.repository.logout();runCurrent();assertTrue(owners.single().state.value.retired)
            val before=f.paths.size;advanceTimeBy(2_001);runCurrent();assertEquals(before,f.paths.size)
            val fresh=f.login();runCurrent();assertNotSame(old,fresh);assertEquals(2,owners.size)
            assertNotNull(owners.last().state.value.ticket);assertFalse(assertNotNull(f.runtime).retired.value)
            discovery.close();runCurrent();assertTrue(owners.last().state.value.retired)
            assertTrue(fresh.job.isActive);assertFalse(assertNotNull(f.runtime).retired.value)
            assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty())
        } finally {discovery.close();notifications.retireAll();registry.close();f.close()}
    }

    @Test fun discoveryRetriesUnverifiedHintWithoutExtendingAnAlreadyPublishedTicket() = runTest {
        val f=Fixture(this);val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer};val port=Notifications()
        var actual:IncomingCallNotificationOwner?=null
        val notifications=IncomingCallAccountCoordinator(f.dispatcher,registry,IncomingCallNotificationFactory {account,runtime ->
            val owner=IncomingCallNotificationOwner(account,runtime,f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key})
            actual=owner;IncomingCallNotificationAttachment(owner,registry.bind(account,owner))
        })
        val discovery=IncomingCallDiscovery(f.repository,f.dispatcher,notifications,f::acquire)
        try {
            f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING";f.statusRingCredit=null;f.login()
            discovery.start();runCurrent();assertNull(assertNotNull(actual).state.value.ticket)
            assertEquals(1,f.paths.count {it=="/v2/nearby/calls/$channel"})
            f.statusRingCredit=1000;advanceTimeBy(2_001);runCurrent()
            val ticket=assertNotNull(assertNotNull(actual).state.value.ticket)
            advanceTimeBy(2_000);runCurrent();assertSame(ticket,assertNotNull(actual).state.value.ticket)
            assertEquals(2,f.paths.count {it=="/v2/nearby/calls/$channel"});assertEquals(1,port.shown.size)
            f.incomingEmpty=true;advanceTimeBy(2_000);runCurrent()
            assertNull(assertNotNull(actual).state.value.ticket);assertEquals(listOf(ticket),port.cancelled)
            assertTrue(f.allocated.isEmpty());assertEquals(0,f.launches)
        } finally {discovery.close();notifications.retireAll();registry.close();f.close()}
    }

    @Test fun discoveryWithdrawsMissingCallAndFencesItsHeldStatusReply() = runTest {
        val f=Fixture(this);val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer};val port=Notifications()
        var actual:IncomingCallNotificationOwner?=null;val release=CompletableDeferred<Unit>()
        val notifications=IncomingCallAccountCoordinator(f.dispatcher,registry,IncomingCallNotificationFactory {account,runtime ->
            val owner=IncomingCallNotificationOwner(account,runtime,f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key})
            actual=owner;IncomingCallNotificationAttachment(owner,registry.bind(account,owner))
        })
        val discovery=IncomingCallDiscovery(f.repository,f.dispatcher,notifications,f::acquire)
        try {
            f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING";f.statusRelease=release;f.login()
            discovery.start();runCurrent();assertTrue(f.statusEntered.isCompleted)
            f.incomingEmpty=true;advanceTimeBy(2_001);runCurrent()
            assertEquals(2,f.paths.count {it.endsWith("/incoming")})
            release.complete(Unit);runCurrent();assertNull(assertNotNull(actual).state.value.ticket)
            assertTrue(port.shown.isEmpty());assertTrue(f.allocated.isEmpty());assertEquals(0,f.launches)
        } finally {release.complete(Unit);discovery.close();notifications.retireAll();registry.close();f.close()}
    }

    @Test fun notificationAcceptanceQueuesOnlyActualIncomingTargetAndDuplicateIntentCannotQueueAgain() = runTest {
        val f=Fixture(this);val root=voiceController(f,Switches());val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer}
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},Notifications(),{f.key})
        val bridge=NearbyIncomingCallAcceptance(f.repository,registry,root,f.dispatcher)
        try {
            val binding=registry.bind(account,owner);owner.hint(channel);runCurrent()
            val ticket=assertNotNull(owner.state.value.ticket)
            val address=IncomingCallActionAddress(binding.id,ticket.id,IncomingCallActionKind.ACCEPT).uri
            assertTrue(bridge.accept(address,"Incoming"));runCurrent()
            val request=assertNotNull(root.state.value.pending)
            assertEquals(NearbyConversationKind.CALL,request.kind);assertEquals("Incoming",request.label)
            assertFalse(bridge.accept(address,"Duplicate"));runCurrent();assertSame(request,root.state.value.pending)
            assertNull(owner.state.value.ticket);assertEquals(2,f.paths.count {it=="/v2/nearby/calls/$channel"})
            assertFalse(f.paths.any {it.endsWith("/accept")});assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty())
            assertTrue(account.job.isActive)
        } finally {registry.close();owner.close();root.close();f.close()}
    }

    @Test fun notificationAcceptanceRejectsMalformedWrongActionAndServerCancelledCall() = runTest {
        val f=Fixture(this);val root=voiceController(f,Switches());val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer}
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},Notifications(),{f.key})
        val bridge=NearbyIncomingCallAcceptance(f.repository,registry,root,f.dispatcher)
        try {
            val binding=registry.bind(account,owner);owner.hint(channel);runCurrent();val ticket=assertNotNull(owner.state.value.ticket)
            assertFalse(bridge.accept("garbage","Incoming"))
            assertFalse(bridge.accept(IncomingCallActionAddress(binding.id,ticket.id,IncomingCallActionKind.DECLINE).uri,"Incoming"))
            assertSame(ticket,owner.state.value.ticket);assertEquals(1,f.paths.size)
            f.statusPhase="CANCELLED"
            assertFalse(bridge.accept(IncomingCallActionAddress(binding.id,ticket.id,IncomingCallActionKind.ACCEPT).uri,"Incoming"))
            runCurrent();assertNull(root.state.value.pending);assertNull(owner.state.value.ticket)
            assertEquals(2,f.paths.size);assertTrue(f.allocated.isEmpty());assertEquals(0,f.launches)
        } finally {registry.close();owner.close();root.close();f.close()}
    }

    @Test fun discoveryHeldOldRuntimeConstructionCannotDelayOrRepaintSameUserLogin() = runTest {
        val f=Fixture(this);val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer};val port=Notifications()
        val old=f.login();val oldRuntime=f.acquire(old);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var creations=0;var actual:IncomingCallNotificationOwner?=null
        val notifications=IncomingCallAccountCoordinator(f.dispatcher,registry,IncomingCallNotificationFactory {account,runtime ->
            creations++;val owner=IncomingCallNotificationOwner(account,runtime,f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key})
            actual=owner;IncomingCallNotificationAttachment(owner,registry.bind(account,owner))
        })
        val discovery=IncomingCallDiscovery(f.repository,f.dispatcher,notifications) {account ->
            if(account===old) withContext(NonCancellable) {entered.complete(Unit);release.await();oldRuntime}
            else f.acquire(account)
        }
        try {
            f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
            discovery.start();runCurrent();assertTrue(entered.isCompleted)
            f.repository.logout();runCurrent();val fresh=f.login();runCurrent()
            assertNotSame(old,fresh);val ticket=assertNotNull(assertNotNull(actual).state.value.ticket)
            release.complete(Unit);runCurrent();assertEquals(1,creations)
            assertSame(ticket,assertNotNull(actual).state.value.ticket);assertEquals(2,f.paths.size)
            assertTrue(fresh.job.isActive);assertFalse(assertNotNull(f.runtime).retired.value)
            assertTrue(f.allocated.isEmpty());assertEquals(0,f.launches)
        } finally {release.complete(Unit);discovery.close();notifications.retireAll();registry.close();f.close()}
    }

    @Test fun notificationAcceptanceHeldStatusCannotReplaceNewLoginPendingConversation() = runTest {
        val f=Fixture(this);val root=voiceController(f,Switches());val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer}
        val old=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val owner=IncomingCallNotificationOwner(old,f.acquire(old),f.dispatcher,MutableSharedFlow(),{f.time},Notifications(),{f.key})
        val bridge=NearbyIncomingCallAcceptance(f.repository,registry,root,f.dispatcher);val release=CompletableDeferred<Unit>()
        try {
            val binding=registry.bind(old,owner);owner.hint(channel);runCurrent();val ticket=assertNotNull(owner.state.value.ticket)
            f.statusRelease=release
            val accepting=async {bridge.accept(IncomingCallActionAddress(binding.id,ticket.id,IncomingCallActionKind.ACCEPT).uri,"Old")}
            runCurrent();assertEquals(2,f.paths.size);assertFalse(accepting.isCompleted)
            f.repository.logout();runCurrent();val fresh=f.login()
            val request=assertNotNull(root.requestChannel(fresh,channel,"Fresh"));runCurrent()
            assertEquals(request,assertNotNull(root.state.value.pending).id)
            release.complete(Unit);runCurrent();assertFalse(accepting.await())
            assertEquals(request,assertNotNull(root.state.value.pending).id);assertEquals("Fresh",root.state.value.pending?.label)
            assertTrue(fresh.job.isActive);assertTrue(f.allocated.isEmpty());assertEquals(0,f.launches)
        } finally {release.complete(Unit);registry.close();owner.close();root.close();f.close()}
    }

    @Test fun inAppOfferSurvivesNotificationRefusalWithoutEarningNativeActionAndConsumesOnce() = runTest {
        val f=Fixture(this);val port=Notifications().apply {allowed=false};val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer}
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key})
        try {
            val binding=registry.bind(account,owner);owner.hint(channel);runCurrent()
            val offer=assertNotNull(owner.offer.value);assertEquals(channel,offer.call.callId)
            assertNull(owner.state.value.ticket);assertNull(registry.bindingFor(offer.ticket))
            assertNull(registry.accept(binding.id,offer.id));assertFalse(owner.discoveryHint(channel));runCurrent()
            assertEquals(1,port.shown.size);assertEquals(1,f.paths.size)
            assertNull(owner.acceptOffer(IncomingCallOffer(offer.ticket,offer.call)))
            assertEquals(offer.call,owner.acceptOffer(offer));assertNull(owner.offer.value)
            assertNull(owner.acceptOffer(offer));assertFalse(owner.discoveryHint(channel));runCurrent()
            assertEquals(2,f.paths.size);assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty());assertTrue(account.job.isActive)
        } finally {registry.close();owner.close();f.close()}
    }

    @Test fun inAppAcceptanceWithdrawsActualNotificationAndNativeReplayCannotAct() = runTest {
        val f=Fixture(this);val port=Notifications();val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer}
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key})
        try {
            val binding=registry.bind(account,owner);owner.hint(channel);runCurrent()
            val offer=assertNotNull(owner.offer.value);val ticket=assertNotNull(owner.state.value.ticket)
            assertSame(ticket,offer.ticket);assertNotNull(owner.acceptOffer(offer))
            assertNull(owner.offer.value);assertNull(owner.state.value.ticket);assertEquals(listOf(ticket),port.cancelled)
            assertNull(registry.accept(binding.id,ticket.id));assertEquals(2,f.paths.size)
            assertTrue(f.allocated.isEmpty());assertEquals(0,f.launches)
        } finally {registry.close();owner.close();f.close()}
    }

    @Test fun inAppExpiredOfferCannotDeclineOrAcceptAndOriginalDeclineProducerSurvivesUiWaiter() = runTest {
        val f=Fixture(this);val pulses=MutableSharedFlow<Unit>();val port=Notifications().apply {allowed=false}
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        var nonce=0
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,pulses,{f.time},port,
            {"a0000000-0000-0000-0000-${(++nonce).toString().padStart(12,'0')}"})
        val release=CompletableDeferred<Unit>()
        try {
            owner.hint(channel);runCurrent();val expired=assertNotNull(owner.offer.value)
            f.time+=1000;pulses.emit(Unit);runCurrent();assertNull(owner.offer.value)
            assertNull(owner.acceptOffer(expired));assertNull(owner.declineOffer(expired,first));assertEquals(1,f.paths.size)
            owner.hint(channel);runCurrent();val fresh=assertNotNull(owner.offer.value)
            f.declineRelease=release
            val producer=assertNotNull(owner.declineOffer(fresh,second));val waiter=async {producer.await()};runCurrent()
            assertTrue(f.declineEntered.isCompleted);waiter.cancel();release.complete(Unit);runCurrent()
            assertIs<DirectCallReply.Ready>(producer.await());assertNull(owner.offer.value)
            assertEquals(1,f.paths.count {it.endsWith("/decline")});assertFalse(owner.discoveryHint(channel))
            assertTrue(account.job.isActive);assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty())
        } finally {release.complete(Unit);owner.close();f.close()}
    }

    @Test fun incomingUiUsesUnnotifiedOriginalOfferAndAcceptanceOnlyQueuesVisibleAdmission() = runTest {
        val f=Fixture(this);val root=voiceController(f,Switches());val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer}
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},Notifications().apply {allowed=false},{f.key})
        val ui=NearbyIncomingCallUi(f.repository,registry,NearbyIncomingCallAcceptance(f.repository,registry,root,f.dispatcher),f.dispatcher,{first.value})
        var view:NearbyIncomingCallView?=null;val collector=backgroundScope.launch {ui.state.collect {view=it}}
        try {
            registry.bind(account,owner);owner.hint(channel);runCurrent();val original=assertNotNull(view)
            assertNull(owner.state.value.ticket);assertEquals(assertNotNull(owner.offer.value).id,original.offerId)
            assertTrue(ui.accept(original,"Incoming"));runCurrent();assertNull(view)
            assertEquals(NearbyConversationKind.CALL,root.state.value.pending?.kind)
            assertFalse(ui.accept(original,"Replay"));assertFalse(ui.decline(original))
            assertEquals(2,f.paths.size);assertFalse(f.paths.any {it.endsWith("/accept")})
            assertTrue(f.allocated.isEmpty());assertEquals(0,f.launches);assertTrue(account.job.isActive)
        } finally {collector.cancel();registry.close();owner.close();root.close();f.close()}
    }

    @Test fun incomingUiRetiredGenerationCannotDeclineReplacementOfferOrSpendEntropy() = runTest {
        val f=Fixture(this);val root=voiceController(f,Switches());var bindingNonce=0;var entropy=0
        val registry=IncomingCallActionRegistry(f.dispatcher) {"a0000000-0000-0000-0000-${(++bindingNonce).toString().padStart(12,'0')}"}
        val ui=NearbyIncomingCallUi(f.repository,registry,NearbyIncomingCallAcceptance(f.repository,registry,root,f.dispatcher),f.dispatcher,{entropy++;first.value})
        var view:NearbyIncomingCallView?=null;val collector=backgroundScope.launch {ui.state.collect {view=it}}
        val owners=mutableListOf<IncomingCallNotificationOwner>()
        suspend fun bind(account:SocialAuthenticatedAccount):IncomingCallNotificationOwner {
            val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},Notifications().apply {allowed=false},{f.key})
            owners+=owner;registry.bind(account,owner);owner.hint(channel);runCurrent();return owner
        }
        try {
            f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING";val oldAccount=f.login();bind(oldAccount)
            val old=assertNotNull(view);f.repository.logout();runCurrent();assertNull(view)
            val freshAccount=f.login();val freshOwner=bind(freshAccount);val fresh=assertNotNull(view);val before=f.paths.size
            assertFalse(ui.decline(old));assertFalse(ui.accept(old,"Old"));assertEquals(0,entropy);assertEquals(before,f.paths.size)
            assertEquals(fresh,view);assertNotNull(freshOwner.offer.value)
            assertTrue(ui.decline(fresh));runCurrent();assertEquals(1,entropy);assertNull(view)
            assertEquals(1,f.paths.count {it.endsWith("/decline")});assertTrue(freshAccount.job.isActive)
            assertTrue(f.allocated.isEmpty());assertEquals(0,f.launches)
        } finally {collector.cancel();registry.close();owners.forEach {it.close()};root.close();f.close()}
    }

    @Test fun incomingRootDisposalPreservesOfferAndRejectsOldButtonsWhileReplacementCanDecline() = runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val trip=SharingFixture(f)
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val voice=voiceController(f,guard);val sharing=sharingController(f,trip,guard)
        val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer};var entropy=0
        val incoming=NearbyIncomingCallUi(f.repository,registry,NearbyIncomingCallAcceptance(f.repository,registry,voice,f.dispatcher),f.dispatcher,{entropy++;first.value})
        val root=NearbyRootPresenter(backgroundScope,voice,sharing,incoming);var replacement:NearbyRootPresenter?=null
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},Notifications().apply {allowed=false},{f.key})
        val activity=SupervisorJob(backgroundScope.coroutineContext[Job]);val boundary=launchBoundary(f,activity)
        try {
            root.connectChannel(account,channel,"Existing channel");runCurrent()
            val original=assertNotNull(voice.state.value.pending).id;voice.begin(original,true,boundary);runCurrent()
            val ticket=f.gate.current.value;assertTrue(assertNotNull(f.runtime).state.value.wanted)
            registry.bind(account,owner);owner.hint(channel);runCurrent();val view=assertNotNull(root.state.value.incoming)
            val offer=assertNotNull(owner.offer.value);val actions=root.incomingActions;val before=f.paths.size
            root.close();actions.accept(view,"Old");actions.decline(view);runCurrent()
            assertEquals(NearbyRootView(),root.state.value);assertEquals(0,entropy);assertEquals(before,f.paths.size)
            assertSame(offer,owner.offer.value);assertTrue(account.job.isActive)
            val fresh=NearbyRootPresenter(backgroundScope,voice,sharing,incoming);replacement=fresh;runCurrent()
            assertEquals(view,fresh.state.value.incoming);fresh.incomingActions.decline(view);runCurrent()
            assertNull(fresh.state.value.incoming);assertEquals(1,entropy);assertEquals(1,f.paths.count {it.endsWith("/decline")})
            assertEquals(VoiceOperationId(original),fresh.state.value.voice.operation)
            assertTrue(assertNotNull(f.runtime).state.value.wanted);assertSame(ticket,f.gate.current.value)
            assertEquals(listOf(phone),f.allocated);assertEquals(1,f.launches);assertTrue(account.job.isActive)
        } finally {boundary.close();activity.cancel();replacement?.close();root.close();registry.close();owner.close();sharing.close();voice.close();f.close()}
    }

    @Test fun incomingRootDisposalDuringActualAcceptVerificationCannotStartVoiceAfterReply() = runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val trip=SharingFixture(f)
        val guard=NearbySharingSwitchGuard(f.repository,f.owner,f.dispatcher);val voice=voiceController(f,guard);val sharing=sharingController(f,trip,guard)
        val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer}
        val incoming=NearbyIncomingCallUi(f.repository,registry,NearbyIncomingCallAcceptance(f.repository,registry,voice,f.dispatcher),f.dispatcher,{first.value})
        val root=NearbyRootPresenter(backgroundScope,voice,sharing,incoming)
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING";val release=CompletableDeferred<Unit>()
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},Notifications().apply {allowed=false},{f.key})
        try {
            registry.bind(account,owner);owner.hint(channel);runCurrent();val view=assertNotNull(root.state.value.incoming)
            f.statusRelease=release;root.incomingActions.accept(view,"Incoming");runCurrent()
            assertEquals(2,f.paths.size);assertNull(owner.offer.value);assertNull(voice.state.value.pending)
            root.close();release.complete(Unit);runCurrent();assertTrue(f.statusHandlerFinished)
            assertNull(voice.state.value.pending);assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty())
            assertTrue(account.job.isActive);assertEquals(NearbyRootView(),root.state.value)
        } finally {release.complete(Unit);root.close();registry.close();owner.close();sharing.close();voice.close();f.close()}
    }

    @Test fun incomingUiCleanupFailureWithdrawsRegistryOfferWhileAccountAndRuntimeRemainLive() = runTest {
        val f=Fixture(this);val voice=voiceController(f,Switches());val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer}
        val port=Notifications();val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val runtime=f.acquire(account)
        val owner=IncomingCallNotificationOwner(account,runtime,f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key})
        var entropy=0
        val ui=NearbyIncomingCallUi(f.repository,registry,NearbyIncomingCallAcceptance(f.repository,registry,voice,f.dispatcher),f.dispatcher,{entropy++;first.value})
        var view:NearbyIncomingCallView?=null;val collector=backgroundScope.launch {ui.state.collect {view=it}}
        try {
            registry.bind(account,owner);owner.hint(channel);runCurrent();val original=assertNotNull(view)
            assertNotNull(owner.state.value.ticket);port.throwOnCancel=true
            owner.withdrawCall(channel);runCurrent();assertTrue(owner.state.value.retired);assertTrue(owner.cleanupFailed.value)
            assertNull(owner.offer.value);assertNull(registry.offer.value);assertNull(view)
            assertFalse(ui.accept(original,"Retired"));assertFalse(ui.decline(original));assertEquals(0,entropy)
            assertTrue(account.job.isActive);assertFalse(runtime.retired.value);assertEquals(1,f.paths.size)
            assertTrue(f.allocated.isEmpty());assertEquals(0,f.launches)
        } finally {collector.cancel();registry.close();owner.close();voice.close();f.close()}
    }

    @Test fun inAppFailedAcceptanceVerificationAllowsNewVerifiedOfferWithNewTicketOnly() = runTest {
        val f=Fixture(this);val port=Notifications().apply {allowed=false};var nonce=0
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},port,
            {"a0000000-0000-0000-0000-${(++nonce).toString().padStart(12,'0')}"})
        try {
            owner.hint(channel);runCurrent();val old=assertNotNull(owner.offer.value)
            f.statusRingCredit=null;assertNull(owner.acceptOffer(old));assertNull(owner.offer.value)
            assertEquals(2,f.paths.size);assertNull(owner.acceptOffer(old))
            f.statusRingCredit=1000;assertTrue(owner.discoveryHint(channel));runCurrent()
            val fresh=assertNotNull(owner.offer.value);assertNotEquals(old.id,fresh.id)
            assertNull(owner.acceptOffer(old));assertEquals(3,f.paths.size)
            assertEquals(fresh.call,owner.acceptOffer(fresh));assertEquals(4,f.paths.size)
            assertTrue(f.allocated.isEmpty());assertEquals(0,f.launches);assertTrue(account.job.isActive)
        } finally {owner.close();f.close()}
    }

    @Test fun inAppOfferCannotPublishCreditSpentDuringNativeNotificationSubmission() = runTest {
        val f=Fixture(this);val port=Notifications();val account=f.login()
        f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        port.onShow={f.time+=1000}
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key})
        try {
            owner.hint(channel);runCurrent();assertEquals(1,port.shown.size)
            assertNull(owner.offer.value);assertNull(owner.state.value.ticket);assertEquals(port.shown,port.cancelled)
            assertTrue(account.job.isActive);assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty())
        } finally {owner.close();f.close()}
    }

    @Test fun incomingHintSubmissionSurvivesWaiterCancellationAndUsesOneActualOwner() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val registry = IncomingCallActionRegistry(f.dispatcher) {f.peer}; val port = Notifications()
        var creations = 0; var actualOwner: IncomingCallNotificationOwner? = null
        val notifications = IncomingCallAccountCoordinator(f.dispatcher, registry, IncomingCallNotificationFactory {account, runtime ->
            creations++
            val owner = IncomingCallNotificationOwner(account, runtime, f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
            actualOwner = owner; IncomingCallNotificationAttachment(owner, registry.bind(account, owner))
        })
        val dispatch = IncomingCallHintDispatch(f.dispatcher, {f.repository.authenticatedAccount.value}, notifications) {account ->
            entered.complete(Unit); release.await(); f.acquire(account)
        }
        try {
            val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
            f.statusRelease = CompletableDeferred()
            val submitted = assertNotNull(dispatch.submit(account, channel)); val waiter = async {submitted.await()}
            runCurrent(); assertTrue(entered.isCompleted); waiter.cancel(); release.complete(Unit); runCurrent()
            assertTrue(submitted.await()); assertEquals(1, creations); assertTrue(f.statusEntered.isCompleted)
            assertFalse(assertNotNull(dispatch.submit(account, channel)).await()); runCurrent()
            assertEquals(1, creations); assertEquals(1, f.paths.size)
            f.statusRelease!!.complete(Unit); runCurrent()
            assertNotNull(assertNotNull(actualOwner).state.value.ticket)
            assertEquals(1, port.shown.size); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {release.complete(Unit); f.statusRelease?.complete(Unit); notifications.retireAll(); registry.close(); f.close()}
    }

    @Test fun malformedOrStaleAccountHintDoesNotAcquireRuntimeOrWithdrawCurrentNotification() = runTest {
        val f = Fixture(this); val registry = IncomingCallActionRegistry(f.dispatcher) {f.peer}; var acquisitions = 0
        var actualOwner: IncomingCallNotificationOwner? = null
        val notifications = IncomingCallAccountCoordinator(f.dispatcher, registry, IncomingCallNotificationFactory {account, runtime ->
            val owner = IncomingCallNotificationOwner(account, runtime, f.dispatcher, MutableSharedFlow(), {f.time}, Notifications(), {f.key})
            actualOwner = owner; IncomingCallNotificationAttachment(owner, registry.bind(account, owner))
        })
        val dispatch = IncomingCallHintDispatch(f.dispatcher, {f.repository.authenticatedAccount.value}, notifications) {account ->
            acquisitions++; f.acquire(account)
        }
        try {
            val old = f.login(); f.repository.logout(); runCurrent(); val account = f.login()
            assertEquals(old.userId, account.userId); assertNotSame(old, account)
            f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
            assertTrue(assertNotNull(dispatch.submit(account, channel)).await()); runCurrent()
            val ticket = assertNotNull(assertNotNull(actualOwner).state.value.ticket)
            assertNull(dispatch.submit(old, channel)); assertNull(dispatch.submit(account, "../../call"))
            assertEquals(1, acquisitions); assertEquals(1, f.paths.size)
            assertSame(ticket, assertNotNull(actualOwner).state.value.ticket)
        } finally {notifications.retireAll(); registry.close(); f.close()}
    }

    @Test fun lateOldAccountHintCannotAcquireNotificationsAfterSameUserRelogin() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val registry = IncomingCallActionRegistry(f.dispatcher) {f.peer}; var creations = 0
        var actualOwner: IncomingCallNotificationOwner? = null
        val notifications = IncomingCallAccountCoordinator(f.dispatcher, registry, IncomingCallNotificationFactory {account, runtime ->
            creations++
            val owner = IncomingCallNotificationOwner(account, runtime, f.dispatcher, MutableSharedFlow(), {f.time}, Notifications(), {f.key})
            actualOwner = owner; IncomingCallNotificationAttachment(owner, registry.bind(account, owner))
        })
        val oldAccount = f.login(); val oldRuntime = f.acquire(oldAccount)
        val dispatch = IncomingCallHintDispatch(f.dispatcher, {f.repository.authenticatedAccount.value}, notifications) {account ->
            if (account === oldAccount) withContext(NonCancellable) {entered.complete(Unit); release.await(); oldRuntime}
            else f.acquire(account)
        }
        try {
            val old = assertNotNull(dispatch.submit(oldAccount, channel)); runCurrent(); assertTrue(entered.isCompleted)
            f.repository.logout(); runCurrent(); val account = f.login()
            f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
            assertTrue(assertNotNull(dispatch.submit(account, channel)).await()); runCurrent()
            val ticket = assertNotNull(assertNotNull(actualOwner).state.value.ticket)
            release.complete(Unit); runCurrent(); assertTrue(old.isCompleted)
            assertEquals(1, creations); assertEquals(1, f.paths.size)
            assertSame(ticket, assertNotNull(actualOwner).state.value.ticket)
            assertFalse(assertNotNull(f.runtime).retired.value); assertEquals(0, f.launches)
        } finally {release.complete(Unit); notifications.retireAll(); registry.close(); f.close()}
    }

    @Test fun retiredBorrowedRuntimeWithdrawsIncomingNotificationEvenWithLiveAccount() = runTest {
        val f = Fixture(this); val port = Notifications(); val pulses = MutableSharedFlow<Unit>()
        val account = f.login(); val runtime = f.acquire(account)
        f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, runtime, f.dispatcher, pulses, {f.time}, port, {f.key})
        try {
            notifications.hint(channel); runCurrent(); val ticket = assertNotNull(notifications.state.value.ticket)
            runtime.retire(); runCurrent(); assertTrue(runtime.retired.value); assertTrue(account.job.isActive)
            pulses.emit(Unit); runCurrent()
            assertTrue(notifications.state.value.retired); assertNull(notifications.state.value.ticket)
            assertEquals(listOf(ticket), port.cancelled); assertFalse(notifications.hint(channel))
            assertEquals(1, f.paths.size); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {notifications.close(); f.close()}
    }

    @Test fun incomingCoordinatorCachesRootBuilderWhenUiWaiterCancels() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val registry = IncomingCallActionRegistry(f.dispatcher) {f.peer}; var creations = 0
        val account = f.login(); val runtime = f.acquire(account)
        val factory = IncomingCallNotificationFactory {ownedAccount, ownedRuntime ->
            creations++
            val owner = IncomingCallNotificationOwner(ownedAccount, ownedRuntime, f.dispatcher, MutableSharedFlow(), {f.time}, Notifications(), {f.key})
            val result = IncomingCallNotificationAttachment(owner, registry.bind(ownedAccount, owner))
            entered.complete(Unit); release.await(); result
        }
        val coordinator = IncomingCallAccountCoordinator(f.dispatcher, registry, factory)
        try {
            val waiter = async {coordinator.acquire(account, runtime)}; runCurrent(); assertTrue(entered.isCompleted)
            waiter.cancel(); val actual = async {coordinator.acquire(account, runtime)}; runCurrent()
            assertEquals(1, creations); assertTrue(f.paths.isEmpty())
            release.complete(Unit); runCurrent(); val attachment = actual.await()
            assertSame(attachment, coordinator.acquire(account, runtime)); assertEquals(1, creations)
            f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
            attachment.owner.hint(channel); runCurrent(); assertNotNull(attachment.owner.state.value.ticket)
            coordinator.retireAll(); runCurrent(); assertTrue(attachment.owner.state.value.retired)
            assertFalse(runtime.retired.value); assertTrue(account.job.isActive); assertEquals(0, f.launches)
        } finally {release.complete(Unit); coordinator.retireAll(); registry.close(); f.close()}
    }

    @Test fun obsoleteIncomingFactoryReturnCannotCloseNewLoginNotificationOrRuntime() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        var nonce = 0; var creations = 0
        val registry = IncomingCallActionRegistry(f.dispatcher) {if (nonce++ == 0) f.peer else first.value}
        val factory = IncomingCallNotificationFactory {account, runtime ->
            val firstBuild = creations++ == 0
            val owner = IncomingCallNotificationOwner(account, runtime, f.dispatcher, MutableSharedFlow(), {f.time}, Notifications(), {f.key})
            val result = IncomingCallNotificationAttachment(owner, registry.bind(account, owner))
            if (firstBuild) withContext(NonCancellable) {entered.complete(Unit); release.await()}
            result
        }
        val coordinator = IncomingCallAccountCoordinator(f.dispatcher, registry, factory)
        try {
            val oldAccount = f.login(); val old = async {coordinator.acquire(oldAccount, f.acquire(oldAccount))}
            runCurrent(); assertTrue(entered.isCompleted)
            f.repository.logout(); runCurrent(); val account = f.login(); val runtime = f.acquire(account)
            val current = coordinator.acquire(account, runtime)
            f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
            current.owner.hint(channel); runCurrent(); val ticket = assertNotNull(current.owner.state.value.ticket)
            release.complete(Unit); runCurrent(); assertTrue(old.await().owner.state.value.retired)
            assertSame(ticket, current.owner.state.value.ticket); assertFalse(runtime.retired.value)
            assertNotNull(registry.accept(current.binding.id, ticket.id)); assertTrue(account.job.isActive)
            assertEquals(2, creations); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {release.complete(Unit); coordinator.retireAll(); registry.close(); f.close()}
    }

    @Test fun incomingCoordinatorRejectsWrongAccountRuntimeBeforeReplacingEarnedNotification() = runTest {
        val f = Fixture(this); val wrongJob = Job(); var creations = 0
        val registry = IncomingCallActionRegistry(f.dispatcher) {f.peer}
        val factory = IncomingCallNotificationFactory {account, runtime ->
            creations++
            val owner = IncomingCallNotificationOwner(account, runtime, f.dispatcher, MutableSharedFlow(), {f.time}, Notifications(), {f.key})
            IncomingCallNotificationAttachment(owner, registry.bind(account, owner))
        }
        val coordinator = IncomingCallAccountCoordinator(f.dispatcher, registry, factory)
        try {
            val account = f.login(); val runtime = f.acquire(account); val attachment = coordinator.acquire(account, runtime)
            f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
            attachment.owner.hint(channel); runCurrent(); val ticket = assertNotNull(attachment.owner.state.value.ticket)
            val wrong = SocialAuthenticatedAccount(account.userId, wrongJob, backgroundScope.coroutineContext) {"replacement"}
            assertFailsWith<IllegalArgumentException> {coordinator.acquire(wrong, runtime)}
            assertSame(ticket, attachment.owner.state.value.ticket); assertEquals(1, creations)
            assertEquals(1, f.paths.size); assertFalse(runtime.retired.value)
        } finally {wrongJob.cancel(); coordinator.retireAll(); registry.close(); f.close()}
    }

    @Test fun partiallyFailedNativeShowCannotKeepItsActionIssuanceSlot() = runTest {
        val f = Fixture(this); val port = Notifications()
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        val registry = IncomingCallActionRegistry(f.dispatcher) {f.peer}
        try {
            val binding = registry.bind(account, notifications)
            var attempted: IncomingCallNotificationTicket? = null
            port.onShow = {attempted = it; assertSame(binding, registry.bindingFor(it)); error("native show failed")}
            notifications.hint(channel); runCurrent(); val ticket = assertNotNull(attempted)
            assertNull(notifications.state.value.ticket); assertEquals(listOf(ticket), port.cancelled)
            assertNull(registry.bindingFor(ticket)); assertNull(registry.accept(binding.id, ticket.id))
            assertEquals(1, f.paths.size); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {registry.close(); notifications.close(); f.close()}
    }

    @Test fun verifiedPublicationCanMintOnlyItsCurrentOriginalNativeActionBinding() = runTest {
        val f = Fixture(this); val port = Notifications()
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        val registry = IncomingCallActionRegistry(f.dispatcher) {f.peer}
        try {
            val binding = registry.bind(account, notifications)
            var duringShow: IncomingCallActionBinding? = null
            port.onShow = {duringShow = registry.bindingFor(it)}
            notifications.hint(channel); runCurrent(); val ticket = assertNotNull(notifications.state.value.ticket)
            assertSame(binding, duringShow); assertSame(binding, registry.bindingFor(ticket))
            assertNull(registry.bindingFor(IncomingCallNotificationTicket(ticket.id, ticket.owner)))
            assertNotNull(registry.accept(binding.id, ticket.id))
            assertNull(registry.bindingFor(ticket)); assertEquals(2, f.paths.size)
        } finally {registry.close(); notifications.close(); f.close()}
    }

    @Test fun actionRegistryResolvesOnlyOriginalBindingAndTicketBeforeExplicitStartup() = runTest {
        val f = Fixture(this); val port = Notifications()
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        val registry = IncomingCallActionRegistry(f.dispatcher) {f.peer}
        try {
            val binding = registry.bind(account, notifications)
            notifications.hint(channel); runCurrent(); val ticket = assertNotNull(notifications.state.value.ticket)
            assertNull(registry.accept(channel, ticket.id)); assertEquals(1, f.paths.size)
            assertNull(registry.accept(binding.id, channel)); assertEquals(1, f.paths.size)
            assertEquals(channel, assertNotNull(registry.accept(binding.id, ticket.id)).callId)
            assertEquals(2, f.paths.size); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
            assertNull(registry.accept(binding.id, ticket.id)); assertEquals(2, f.paths.size)
        } finally {registry.close(); notifications.close(); f.close()}
    }

    @Test fun sameUserReloginAndDeliberatelyIdenticalTicketCannotReuseOldActionBinding() = runTest {
        val f = Fixture(this); val port = Notifications(); var nonce = 0
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val oldOwner = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        val registry = IncomingCallActionRegistry(f.dispatcher) {if (nonce++ == 0) f.peer else first.value}
        var newOwner: IncomingCallNotificationOwner? = null
        try {
            val oldBinding = registry.bind(account, oldOwner)
            oldOwner.hint(channel); runCurrent(); val oldTicket = assertNotNull(oldOwner.state.value.ticket)
            f.repository.logout(); runCurrent(); val replacement = f.login()
            assertEquals(account.userId, replacement.userId); assertNotSame(account, replacement)
            val owner = IncomingCallNotificationOwner(replacement, f.acquire(replacement), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
            newOwner = owner
            val binding = registry.bind(replacement, owner)
            owner.hint(channel); runCurrent(); val ticket = assertNotNull(owner.state.value.ticket)
            assertEquals(oldTicket.id, ticket.id); assertNotEquals(oldBinding.id, binding.id)
            val issued = f.paths.size
            assertNull(registry.accept(oldBinding.id, oldTicket.id))
            assertNull(registry.decline(oldBinding.id, oldTicket.id, first))
            assertFalse(registry.detach(oldBinding)); assertSame(ticket, owner.state.value.ticket)
            assertEquals(issued, f.paths.size)
            assertNotNull(registry.accept(binding.id, ticket.id)); assertEquals(issued + 1, f.paths.size)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {registry.close(); newOwner?.close(); oldOwner.close(); f.close()}
    }

    @Test fun mismatchedAccountRegistryBindingCannotWithdrawCurrentNotification() = runTest {
        val f = Fixture(this); val port = Notifications(); val wrongJob = Job()
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        val registry = IncomingCallActionRegistry(f.dispatcher) {f.peer}
        try {
            val binding = registry.bind(account, notifications)
            notifications.hint(channel); runCurrent(); val ticket = assertNotNull(notifications.state.value.ticket)
            val wrong = SocialAuthenticatedAccount(account.userId, wrongJob, backgroundScope.coroutineContext) {"replacement"}
            assertFailsWith<IllegalArgumentException> {registry.bind(wrong, notifications)}
            assertSame(ticket, notifications.state.value.ticket); assertTrue(port.cancelled.isEmpty())
            assertNotNull(registry.accept(binding.id, ticket.id)); assertEquals(2, f.paths.size)
        } finally {wrongJob.cancel(); registry.close(); notifications.close(); f.close()}
    }

    @Test fun ringExpiringAtAcceptanceReturnCannotBecomeActivityTarget() = runTest {
        val f = Fixture(this); val port = Notifications(); var reads = 0; var expire = false
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val clock = {if (expire && ++reads >= 3) 1100L else 100L}
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), clock, port, {f.key})
        try {
            notifications.hint(channel); runCurrent(); val ticket = assertNotNull(notifications.state.value.ticket)
            expire = true
            assertNull(notifications.acceptTarget(ticket)); assertEquals(2, f.paths.size)
            assertTrue(reads >= 3); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {notifications.close(); f.close()}
    }

    @Test fun authorizedDeclineStillSubmitsOriginalNegativeWhenNotificationCancelThrows() = runTest {
        val f = Fixture(this); val port = Notifications().apply {throwOnCancel = true}
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        try {
            notifications.hint(channel); runCurrent(); val ticket = assertNotNull(notifications.state.value.ticket)
            val result = assertNotNull(notifications.decline(ticket, first))
            assertEquals(DirectCallPhase.DECLINED, assertIs<DirectCallReply.Ready>(result.await()).call.phase)
            assertTrue(notifications.cleanupFailed.value); assertTrue(notifications.state.value.retired)
            assertEquals(listOf("/v2/nearby/calls/$channel", "/v2/nearby/calls/$channel/decline"), f.paths)
            assertTrue(account.job.isActive); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {notifications.close(); f.close()}
    }

    @Test fun newerHintFencesHeldAcceptanceResolutionWithoutTakingItsNotification() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); val port = Notifications(); var nonce = 0
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port,
            {if (nonce++ == 0) f.key else f.peer})
        try {
            notifications.hint(channel); runCurrent(); val ticket = assertNotNull(notifications.state.value.ticket)
            f.statusRelease = release
            val target = async {notifications.acceptTarget(ticket)}; runCurrent(); assertEquals(2, f.paths.size)
            notifications.hint(second.value); runCurrent(); assertEquals(3, f.paths.size)
            release.complete(Unit); runCurrent(); assertNull(target.await())
            assertEquals(second.value, notifications.state.value.call?.callId)
            assertNotNull(notifications.state.value.ticket); assertEquals(2, port.shown.size)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {release.complete(Unit); notifications.close(); f.close()}
    }

    @Test fun notificationAcceptanceConsumesTicketRechecksStatusAndStillNeedsExplicitForeground() = runTest {
        val f = Fixture(this); val port = Notifications()
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        try {
            notifications.hint(channel); runCurrent(); val ticket = assertNotNull(notifications.state.value.ticket)
            val target = assertNotNull(notifications.acceptTarget(ticket))
            assertEquals(channel, target.callId); assertEquals(2, f.paths.size)
            assertNull(notifications.state.value.ticket); assertEquals(listOf(ticket), port.cancelled)
            assertNull(notifications.acceptTarget(ticket)); assertEquals(2, f.paths.size)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
            f.owner.start(account, VoiceConversationTarget.IncomingCall(target), first,
                readPreferences = {phone}, launch = f::launch); runCurrent()
            assertEquals(1, f.launches); assertEquals(1, f.published)
            assertTrue(f.paths.contains("/v2/nearby/calls/$channel/accept"))
        } finally {notifications.close(); f.close()}
    }

    @Test fun oldNotificationActionsCannotConsumeReplacementTicketOrIssueHttp() = runTest {
        val f = Fixture(this); val port = Notifications(); var nonce = 0
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port,
            {if (nonce++ == 0) f.key else f.peer})
        try {
            notifications.hint(channel); runCurrent(); val old = assertNotNull(notifications.state.value.ticket)
            notifications.hint(channel); runCurrent(); val current = assertNotNull(notifications.state.value.ticket)
            assertNotSame(old, current); val issued = f.paths.size
            assertNull(notifications.acceptTarget(old)); assertNull(notifications.decline(old, first))
            assertEquals(issued, f.paths.size); assertSame(current, notifications.state.value.ticket)
            f.statusPhase = "ACCEPTED"
            assertNull(notifications.acceptTarget(current)); assertEquals(issued + 1, f.paths.size)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {notifications.close(); f.close()}
    }

    @Test fun notificationDeclineSurvivesCancelledUiWaiterCloseAndLogoutOnOriginalCleanupRoot() = runTest {
        val f = Fixture(this); val port = Notifications(); val release = CompletableDeferred<Unit>()
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        try {
            notifications.hint(channel); runCurrent(); val ticket = assertNotNull(notifications.state.value.ticket)
            f.declineRelease = release
            val result = assertNotNull(notifications.decline(ticket, first))
            val waiter = launch {result.await()}; runCurrent(); assertTrue(f.declineEntered.isCompleted)
            waiter.cancel(); notifications.close(); f.repository.logout(); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals(DirectCallPhase.DECLINED, assertIs<DirectCallReply.Ready>(result.await()).call.phase)
            assertEquals(listOf("/v2/nearby/calls/$channel", "/v2/nearby/calls/$channel/decline"), f.paths)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty()); assertEquals(0, f.published)
        } finally {release.complete(Unit); notifications.close(); f.close()}
    }

    @Test fun latestHintPublishesOnlyItsOwnActualStatusAfterOlderHeldResponse() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); val port = Notifications()
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"; f.statusRelease = release
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        try {
            notifications.hint(channel); runCurrent(); assertTrue(f.statusEntered.isCompleted)
            assertTrue(notifications.hint(second.value)); assertFalse(notifications.hint(second.value))
            assertEquals(1, f.paths.size); assertEquals(1, notifications.readInFlight.value)
            release.complete(Unit); runCurrent()
            assertEquals(listOf("/v2/nearby/calls/$channel", "/v2/nearby/calls/${second.value}"), f.paths)
            assertEquals(second.value, notifications.state.value.call?.callId); assertEquals(1, port.shown.size)
            assertEquals(0, notifications.readInFlight.value); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {release.complete(Unit); notifications.close(); f.close()}
    }

    @Test fun notificationCancelFailureRetiresOnlyNotificationOwnerAndKeepsActualChannelVoice() = runTest {
        val f = Fixture(this); val pulses = MutableSharedFlow<Unit>(); val port = Notifications().apply {throwOnCancel = true}
        val account = f.login()
        f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch); runCurrent()
        assertEquals(1, f.published)
        f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, assertNotNull(f.runtime), f.dispatcher, pulses, {f.time}, port, {f.key})
        try {
            notifications.hint(channel); runCurrent(); assertNotNull(notifications.state.value.ticket)
            f.time = 1100; pulses.emit(Unit); runCurrent()
            assertTrue(notifications.state.value.retired); assertTrue(notifications.cleanupFailed.value)
            assertNull(notifications.state.value.ticket); assertFalse(notifications.hint(channel))
            assertTrue(account.job.isActive); assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertEquals(first, f.owner.state.value.operationId); assertEquals(VoiceConversationPhase.JOIN_SUBMITTED, f.owner.state.value.phase)
            assertEquals(1, f.launches); assertEquals(1, f.published); assertEquals(0, f.closes)
        } finally {notifications.close(); f.close()}
    }

    @Test fun incomingNotificationExpiresWithEarnedCreditWithoutStartingVoice() = runTest {
        val f = Fixture(this); val pulses = MutableSharedFlow<Unit>(); val port = Notifications()
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, pulses, {f.time}, port, {f.key})
        try {
            assertTrue(notifications.hint(channel)); runCurrent()
            val ticket = assertNotNull(notifications.state.value.ticket)
            assertEquals(channel, notifications.state.value.call?.callId)
            assertEquals(listOf(1000L), port.remaining); assertEquals(listOf(ticket), port.shown)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
            f.time = 1100; pulses.emit(Unit); runCurrent()
            assertNull(notifications.state.value.ticket); assertEquals(listOf(ticket), port.cancelled)
            assertTrue(account.job.isActive); assertFalse(assertNotNull(f.runtime).state.value.wanted)
        } finally {notifications.close(); f.close()}
    }

    @Test fun heldHintAndPendingDuplicateCannotShowAfterNotificationOwnerClose() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); val port = Notifications()
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"; f.statusRelease = release
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        try {
            assertTrue(notifications.hint(channel)); runCurrent(); assertTrue(f.statusEntered.isCompleted)
            assertEquals(1, notifications.readInFlight.value); assertFalse(notifications.hint(channel))
            assertEquals(1, f.paths.size)
            notifications.close(); runCurrent()
            assertTrue(notifications.state.value.retired)
            // MockEngine's held handler is a separate producer: our cancelled reader unwound.
            assertEquals(0, notifications.readInFlight.value); assertFalse(f.statusHandlerFinished)
            release.complete(Unit); runCurrent()
            assertTrue(f.statusHandlerFinished); assertEquals(0, notifications.readInFlight.value); assertTrue(port.shown.isEmpty())
            assertTrue(account.job.isActive); assertFalse(notifications.hint(channel))
        } finally {release.complete(Unit); notifications.close(); f.close()}
    }

    @Test fun logoutWithdrawsOriginalIncomingNotificationWithoutRetiringBorrowedRuntimeAgain() = runTest {
        val f = Fixture(this); val port = Notifications()
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        try {
            notifications.hint(channel); runCurrent(); val ticket = assertNotNull(notifications.state.value.ticket)
            f.repository.logout(); runCurrent()
            assertTrue(notifications.state.value.retired); assertNull(notifications.state.value.ticket)
            assertEquals(listOf(ticket), port.cancelled)
            assertEquals(1, f.paths.size); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {notifications.close(); f.close()}
    }

    @Test fun refusedNotificationNeverBecomesPublishedStateOrForegroundAdmission() = runTest {
        val f = Fixture(this); val port = Notifications().apply {allowed = false}
        val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
        val notifications = IncomingCallNotificationOwner(account, f.acquire(account), f.dispatcher, MutableSharedFlow(), {f.time}, port, {f.key})
        try {
            notifications.hint(channel); runCurrent()
            assertEquals(1, port.shown.size); assertEquals(port.shown, port.cancelled)
            assertNull(notifications.state.value.ticket); assertTrue(account.job.isActive)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {notifications.close(); f.close()}
    }

    private class CallerNames(private val account:SocialAuthenticatedAccount,
        private val read:suspend ()->String?) : IncomingCallCallerNames {
        var reads=0;var closes=0
        override fun belongsTo(account:SocialAuthenticatedAccount)=this.account===account
        override suspend fun lookup(caller:String):String? {reads++;return read()}
        override fun close() {closes++}
    }

    @Test fun incomingNameEnrichmentKeepsOriginalTicketAndOnlyRemainingCredit() = runTest {
        val f=Fixture(this);val port=Notifications();val release=CompletableDeferred<Unit>()
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val names=CallerNames(account) {release.await();"Саша"}
        val runtime=f.acquire(account)
        val owner=IncomingCallNotificationOwner(account,runtime,f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key},names)
        try {
            owner.hint(channel);runCurrent();val original=assertNotNull(owner.offer.value)
            assertNull(original.callerName);assertEquals(1,names.reads);assertEquals(1,port.shown.size)
            f.time+=400;release.complete(Unit);runCurrent()
            val labeled=assertNotNull(owner.offer.value);assertEquals("Саша",labeled.callerName)
            assertSame(original.ticket,labeled.ticket);assertSame(original.ticket,owner.state.value.ticket)
            assertEquals(listOf(Triple(original.ticket,"Саша",600L)),port.labels);assertEquals(1,port.shown.size)
            assertNull(owner.acceptOffer(original));assertNotNull(owner.acceptOffer(labeled));assertNull(owner.offer.value)
            owner.close();owner.close();assertEquals(1,names.closes)
            assertTrue(account.job.isActive);assertFalse(runtime.retired.value);assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty())
        } finally {release.complete(Unit);owner.close();f.close()}
    }

    @Test fun incomingNameCannotResurrectExpiredOfferAfterCancellationIgnoringRead() = runTest {
        val f=Fixture(this);val port=Notifications();val release=CompletableDeferred<Unit>();val pulses=MutableSharedFlow<Unit>()
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val names=CallerNames(account) {withContext(NonCancellable) {release.await()};"Late"}
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,pulses,{f.time},port,{f.key},names)
        try {
            owner.hint(channel);runCurrent();val original=assertNotNull(owner.offer.value);assertEquals(1,names.reads)
            f.time+=1000;pulses.emit(Unit);runCurrent();assertNull(owner.offer.value)
            release.complete(Unit);runCurrent();assertNull(owner.offer.value);assertTrue(port.labels.isEmpty())
            assertEquals(listOf(original.ticket),port.cancelled);assertEquals(1,port.shown.size)
            assertTrue(account.job.isActive);assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty())
        } finally {release.complete(Unit);owner.close();f.close()}
        assertEquals(1,names.closes)
    }

    @Test fun incomingNameStillLabelsInAppOfferWhenNativePublicationWasRefused() = runTest {
        val f=Fixture(this);val port=Notifications().apply {allowed=false}
        val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val names=CallerNames(account) {"Rider"}
        val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key},names)
        try {
            owner.hint(channel);runCurrent();assertEquals("Rider",assertNotNull(owner.offer.value).callerName)
            assertNull(owner.state.value.ticket);assertTrue(port.labels.isEmpty());assertEquals(1,port.shown.size)
            assertNotNull(owner.acceptOffer(assertNotNull(owner.offer.value)));assertEquals(0,f.launches)
            assertTrue(f.allocated.isEmpty());assertTrue(account.job.isActive)
        } finally {owner.close();f.close()}
        assertEquals(1,names.closes)
    }

    @Test fun incomingNameUiAndNativeAcceptanceUseEarnedNameInsteadOfCallerSuppliedLabel() = runTest {
        for (native in listOf(false,true)) {
            val f=Fixture(this);val root=voiceController(f,Switches());val port=Notifications().apply {allowed=native}
            val registry=IncomingCallActionRegistry(f.dispatcher) {f.peer}
            val account=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
            val names=CallerNames(account) {"Саша"}
            val owner=IncomingCallNotificationOwner(account,f.acquire(account),f.dispatcher,MutableSharedFlow(),{f.time},port,{f.key},names)
            val acceptance=NearbyIncomingCallAcceptance(f.repository,registry,root,f.dispatcher)
            val ui=NearbyIncomingCallUi(f.repository,registry,acceptance,f.dispatcher,{first.value})
            var view:NearbyIncomingCallView?=null;val collector=backgroundScope.launch {ui.state.collect {view=it}}
            try {
                val binding=registry.bind(account,owner);owner.hint(channel);runCurrent()
                val original=assertNotNull(view);assertEquals("Саша",original.callerName)
                val accepted=if (native) acceptance.accept(IncomingCallActionAddress(binding.id,original.offerId,IncomingCallActionKind.ACCEPT).uri,"Forged")
                    else ui.accept(original.copy(callerName="Forged"),"Fallback")
                assertTrue(accepted);runCurrent();assertEquals("Саша",root.state.value.pending?.label)
                assertNull(view);assertEquals(0,f.launches);assertTrue(f.allocated.isEmpty())
            } finally {collector.cancel();registry.close();owner.close();root.close();f.close()}
        }
    }

    @Test fun incomingNameHeldOldLoginCannotRepaintSameUserNewOffer() = runTest {
        val f=Fixture(this);var bindingNonce=0
        val registry=IncomingCallActionRegistry(f.dispatcher) {"a0000000-0000-0000-0000-${(++bindingNonce).toString().padStart(12,'0')}"}
        val old=f.login();f.callMode=true;f.incomingMode=true;f.statusPhase="RINGING"
        val release=CompletableDeferred<Unit>();val oldPort=Notifications()
        val oldNames=CallerNames(old) {withContext(NonCancellable) {release.await()};"Old"}
        val oldOwner=IncomingCallNotificationOwner(old,f.acquire(old),f.dispatcher,MutableSharedFlow(),{f.time},oldPort,{f.key},oldNames)
        var freshOwner:IncomingCallNotificationOwner?=null
        try {
            registry.bind(old,oldOwner);oldOwner.hint(channel);runCurrent();assertEquals(1,oldNames.reads)
            f.repository.logout();runCurrent();val fresh=f.login();assertEquals(old.userId,fresh.userId)
            val freshNames=CallerNames(fresh) {"Fresh"};val freshPort=Notifications()
            freshOwner=IncomingCallNotificationOwner(fresh,f.acquire(fresh),f.dispatcher,MutableSharedFlow(),{f.time},freshPort,{f.key},freshNames)
            val freshRegistry=registry
            try {
                freshRegistry.bind(fresh,assertNotNull(freshOwner));freshOwner.hint(channel);runCurrent()
                assertEquals("Fresh",freshRegistry.offer.value?.original?.callerName)
                release.complete(Unit);runCurrent()
                assertEquals("Fresh",freshRegistry.offer.value?.original?.callerName);assertNull(oldOwner.offer.value)
                assertTrue(oldPort.labels.isEmpty());assertEquals(1,oldNames.closes);assertTrue(fresh.job.isActive)
            } finally {freshRegistry.close()}
        } finally {release.complete(Unit);registry.close();oldOwner.close();freshOwner?.close();f.close()}
    }

    private class Notifications : IncomingCallNotificationPort {
        val labels = mutableListOf<Triple<IncomingCallNotificationTicket,String,Long>>()
        override fun label(ticket:IncomingCallNotificationTicket,callerName:String,remainingMillis:Long):Boolean {
            labels += Triple(ticket,callerName,remainingMillis);return allowed
        }
        var allowed = true
        var throwOnCancel = false
        var onShow: (IncomingCallNotificationTicket) -> Unit = {}
        val shown = mutableListOf<IncomingCallNotificationTicket>()
        val cancelled = mutableListOf<IncomingCallNotificationTicket>()
        val remaining = mutableListOf<Long>()
        override fun show(ticket: IncomingCallNotificationTicket, call: DirectCallSnapshot, remainingMillis: Long): Boolean {
            shown += ticket; remaining += remainingMillis; onShow(ticket); return allowed
        }
        override fun cancel(ticket: IncomingCallNotificationTicket) {cancelled += ticket; if (throwOnCancel) error("cancel failed")}
    }

    @Test fun hintWithoutServerRelativeCreditCannotOfferTimedIncomingRing() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
            f.statusRingCredit = null
            val verifier = IncomingCallHintVerifier(account, f.acquire(account)) {f.time}
            assertEquals(DirectCallFailure.INVALID_RESPONSE, assertIs<IncomingCallHintReply.Failed>(verifier.verify(channel)).reason)
            assertEquals(1, f.paths.size); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {f.close()}
    }

    @Test fun delayedActualHintCannotReplenishExpiredNotificationTime() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
            f.statusElapsed = 1000
            val verifier = IncomingCallHintVerifier(account, f.acquire(account)) {f.time}
            assertEquals(IncomingCallHintReply.Gone, verifier.verify(channel))
            assertEquals(1100, f.time); assertEquals(1, f.paths.size)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {f.close()}
    }

    @Test fun incomingHintUsesActualStatusAndOnlyExplicitAcceptanceStartsForeground() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
            val runtime = f.acquire(account)
            val verifier = IncomingCallHintVerifier(account, runtime) {f.time}
            val ring = assertIs<IncomingCallHintReply.Ringing>(verifier.verify(channel)).call
            assertEquals(listOf("/v2/nearby/calls/$channel"), f.paths)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty()); assertTrue(f.rosters.isEmpty())
            f.owner.start(account, VoiceConversationTarget.IncomingCall(ring), first,
                readPreferences = {phone}, launch = f::launch); runCurrent()
            assertEquals(1, f.launches); assertEquals(1, f.published)
            assertTrue(f.paths.contains("/v2/nearby/calls/$channel/accept"))
        } finally {f.close()}
    }

    @Test fun acceptedTerminalAndOutgoingHintsCannotBecomeIncomingRings() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true; f.incomingMode = true
            val verifier = IncomingCallHintVerifier(account, f.acquire(account)) {f.time}
            for (phase in listOf("ACCEPTED", "ENDED", "CANCELLED")) {
                f.statusPhase = phase
                assertEquals(IncomingCallHintReply.Gone, verifier.verify(channel))
            }
            f.statusPhase = "RINGING"; f.incomingMode = false
            assertEquals(IncomingCallHintReply.Gone, verifier.verify(channel))
            assertEquals(4, f.paths.size); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
            assertFalse(assertNotNull(f.runtime).state.value.wanted)
        } finally {f.close()}
    }

    @Test fun hintCannotBorrowRuntimeFromAnotherAccountObjectWithSameUserId() = runTest {
        val f = Fixture(this)
        val replacementJob = Job()
        try {
            val account = f.login(); val runtime = f.acquire(account)
            val replacement = SocialAuthenticatedAccount(account.userId, replacementJob, backgroundScope.coroutineContext) {"new-token"}
            assertFailsWith<IllegalArgumentException> {IncomingCallHintVerifier(replacement, runtime) {f.time}}
            val verifier = IncomingCallHintVerifier(account, runtime) {f.time}
            assertFailsWith<IllegalArgumentException> {verifier.verify("invalid")}
            assertTrue(f.paths.isEmpty()); assertEquals(0, f.launches)
        } finally {replacementJob.cancel(); f.close()}
    }

    @Test fun heldActualHintResponseCannotOfferRingAfterLogout() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>()
        var offered = false
        try {
            val account = f.login(); f.callMode = true; f.incomingMode = true; f.statusPhase = "RINGING"
            f.statusRelease = release
            val verifier = IncomingCallHintVerifier(account, f.acquire(account)) {f.time}
            val task = async {runCatching {verifier.verify(channel).also {offered = true}}}
            runCurrent(); assertTrue(f.statusEntered.isCompleted)
            f.repository.logout(); runCurrent(); release.complete(Unit); runCurrent()
            assertIs<CancellationException>(task.await().exceptionOrNull()); assertFalse(offered)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun cancelledRosterFactoryFailsOriginalCallInsteadOfLeavingItWithoutPresence() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true
            f.beforeRoster = {throw CancellationException("Reader creation cancelled")}
            f.owner.start(account, VoiceConversationTarget.OutgoingCall(channel, f.peer, f.key), first,
                readPreferences = {phone}, launch = f::launch); runCurrent()
            assertEquals(1, f.published)
            assertEquals(VoiceConversationPhase.FAILED, f.owner.state.value.phase)
            assertNull(f.owner.state.value.callRoster); assertNull(f.gate.current.value)
            assertFalse(assertNotNull(f.runtime).state.value.wanted); assertTrue(account.job.isActive)
            assertTrue(f.paths.contains("/v2/nearby/calls/$channel/cancel-outgoing"))
            assertTrue(f.paths.contains("/v2/nearby/sessions/40000000-0000-0000-0000-000000000004/disconnect"))
        } finally {f.close()}
    }

    @Test fun missingCallRosterBindingRejectsAdmissionBeforeReplacingExistingChannel() = runTest {
        val f = Fixture(this)
        val channelOnly = VoiceConversationStartup(f.dispatcher, f.foreground, f::acquire)
        try {
            val account = f.login()
            channelOnly.start(account, channel, first, readPreferences = {phone}, launch = f::launch); runCurrent()
            val ticket = assertNotNull(f.gate.current.value)
            assertEquals(1, f.published)
            assertFailsWith<IllegalArgumentException> {
                channelOnly.start(account, VoiceConversationTarget.OutgoingCall(channel, f.peer, f.key), second,
                    readPreferences = {phone}, launch = f::launch)
            }
            runCurrent(); assertSame(ticket, f.gate.current.value)
            assertEquals(first, channelOnly.state.value.operationId); assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertEquals(listOf("/v2/nearby/operations/${first.value}/join"), f.paths)
        } finally {channelOnly.stop(); f.close()}
    }

    @Test fun earnedCallRosterIsPublishedAtRootAndLogoutWithdrawsIt() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true
            f.owner.start(account, VoiceConversationTarget.OutgoingCall(channel, f.peer, f.key), first,
                readPreferences = {phone}, launch = f::launch); runCurrent()
            assertEquals(2, assertNotNull(f.owner.state.value.callRoster?.peers).size)
            assertEquals(1, f.owner.state.value.callRoster?.connectedCount)
            assertEquals(assertNotNull(f.runtime).state.value.sessionId, f.rosters.single().ownSession.sessionId)
            f.repository.logout(); runCurrent()
            assertNull(f.owner.state.value.callRoster); assertTrue(f.rosters.single().state.value.retired)
            assertEquals(VoiceConversationPhase.STOPPED, f.owner.state.value.phase)
        } finally {f.close()}
    }

    @Test fun ringingNeverAllocatesRosterOrAudio() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true; f.callRinging = true
            f.owner.start(account, VoiceConversationTarget.OutgoingCall(channel, f.peer, f.key), first,
                readPreferences = {phone}, launch = f::launch); runCurrent()
            assertEquals(listOf("/v2/nearby/calls/$channel/create"), f.paths)
            assertTrue(f.rosters.isEmpty()); assertTrue(f.allocated.isEmpty()); assertNull(f.owner.state.value.callRoster)
            assertTrue(assertNotNull(f.runtime).state.value.wanted)
        } finally {f.close()}
    }

    @Test fun obsoleteHeldRosterFactoryCannotPopulateOrStopSameUuidChannelReplacement() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        try {
            val account = f.login(); f.callMode = true
            f.afterRoster = {withContext(NonCancellable) {entered.complete(Unit); release.await()}}
            f.owner.start(account, VoiceConversationTarget.OutgoingCall(channel, f.peer, f.key), first,
                readPreferences = {phone}, launch = f::launch); runCurrent()
            assertTrue(entered.isCompleted); assertEquals(2, assertNotNull(f.rosters.single().state.value.peers).size)
            assertNull(f.owner.state.value.callRoster)
            f.owner.start(account, channel, second, readPreferences = {phone}, launch = f::launch); runCurrent()
            val ticket = assertNotNull(f.gate.current.value)
            assertEquals(second, assertNotNull(f.runtime).state.value.operationId)
            release.complete(Unit); runCurrent()
            assertTrue(f.rosters.single().state.value.retired); assertNull(f.owner.state.value.callRoster)
            assertSame(ticket, f.gate.current.value); assertTrue(assertNotNull(f.runtime).state.value.wanted)
            assertEquals(second, f.owner.state.value.operationId)
            assertEquals(VoiceConversationPhase.JOIN_SUBMITTED, f.owner.state.value.phase)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun actualRosterAccessLossStopsOriginalCallAndWithdrawsRootPresence() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true
            f.owner.start(account, VoiceConversationTarget.OutgoingCall(channel, f.peer, f.key), first,
                readPreferences = {phone}, launch = f::launch); runCurrent()
            assertEquals(2, assertNotNull(f.owner.state.value.callRoster?.peers).size)
            f.rosterDenied = true; assertTrue(f.rosters.single().refresh()); runCurrent()
            assertNull(f.owner.state.value.callRoster); assertNull(f.gate.current.value)
            assertEquals(VoiceConversationPhase.STOPPED, f.owner.state.value.phase)
            assertFalse(assertNotNull(f.runtime).state.value.wanted); assertTrue(account.job.isActive)
            assertTrue(f.paths.contains("/v2/nearby/calls/$channel/cancel-outgoing"))
        } finally {f.close()}
    }

    @Test fun explicitOutgoingCallWaitsForForegroundAndKeepsPhoneMicHeadsetPreferences() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true; f.acknowledgeLaunch = false
            val target = VoiceConversationTarget.OutgoingCall(channel, f.peer, f.key)
            f.owner.start(account, target, first, readPreferences = {phone}, launch = f::launch)
            runCurrent()
            assertEquals(1, f.launches); assertTrue(f.paths.isEmpty()); assertTrue(f.allocated.isEmpty())
            val ticket = assertNotNull(f.gate.current.value)
            assertTrue(f.gate.acknowledge(ticket, f.service)); runCurrent()
            assertEquals(listOf(phone), f.allocated); assertEquals(1, f.published)
            assertEquals(target, f.owner.state.value.target)
            assertEquals(listOf("/v2/nearby/calls/$channel/create", "/v2/nearby/calls/$channel/resume", "/v2/nearby/calls/$channel/roster"), f.paths)
            f.owner.stop(); runCurrent()
            assertEquals(listOf("/v2/nearby/calls/$channel/cancel-outgoing", "/v2/nearby/sessions/40000000-0000-0000-0000-000000000004/disconnect"), f.paths.takeLast(2))
            assertTrue(account.job.isActive); assertNull(f.gate.current.value)
        } finally {f.close()}
    }

    @Test fun refusedForegroundLaunchCannotRingOrAllocateCallAudio() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true
            f.owner.start(account, VoiceConversationTarget.OutgoingCall(channel, f.peer, f.key), first,
                readPreferences = {error("Must not read preferences")}, launch = {false})
            runCurrent()
            assertTrue(f.paths.isEmpty()); assertTrue(f.allocated.isEmpty())
            assertNull(f.gate.current.value); assertEquals(VoiceConversationPhase.FAILED, f.owner.state.value.phase)
        } finally {f.close()}
    }

    @Test fun incomingDiscoveryNeedsNoForegroundButOnlyExplicitAcceptanceStartsAudio() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true; f.incomingMode = true
            val runtime = f.acquire(account)
            val ring = assertIs<IncomingCallReply.Ready>(runtime.incomingCalls()).calls.single()
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
            val target = VoiceConversationTarget.IncomingCall(ring)
            f.owner.start(account, target, first, readPreferences = {phone}, launch = f::launch); runCurrent()
            assertEquals(1, f.launches); assertEquals(1, f.published)
            assertEquals(target, f.owner.state.value.target)
            assertEquals(listOf("/v2/nearby/calls/incoming", "/v2/nearby/calls/$channel/accept", "/v2/nearby/calls/$channel/roster"), f.paths)
            f.owner.stop(); runCurrent()
            assertEquals(listOf("/v2/nearby/calls/$channel/stop", "/v2/nearby/sessions/40000000-0000-0000-0000-000000000004/disconnect"), f.paths.takeLast(2))
        } finally {f.close()}
    }

    @Test fun unobservedIncomingSnapshotCannotStartForegroundOrAccept() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true; f.incomingMode = true
            val unobserved = DirectCallSnapshot(channel, f.peer, actor, DirectCallPhase.RINGING, 2, 45100, null)
            f.owner.start(account, VoiceConversationTarget.IncomingCall(unobserved), first,
                readPreferences = {error("Unobserved ring")}, launch = f::launch); runCurrent()
            assertEquals(0, f.launches); assertTrue(f.paths.isEmpty()); assertTrue(f.allocated.isEmpty())
            assertEquals(VoiceConversationPhase.FAILED, f.owner.state.value.phase)
            assertTrue(account.job.isActive)
        } finally {f.close()}
    }

    @Test fun explicitRejectSurvivesUiWaiterCancellationAndAccountRetirementWithoutAudio() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>()
        try {
            val account = f.login(); f.callMode = true; f.incomingMode = true
            val runtime = f.acquire(account)
            val ring = assertIs<IncomingCallReply.Ready>(runtime.incomingCalls()).calls.single()
            f.declineRelease = release
            val result = runtime.requestDeclineIncomingCall(ring, first)
            val waiter = launch {result.await()}; runCurrent()
            assertTrue(f.declineEntered.isCompleted)
            waiter.cancel(); f.repository.logout(); runCurrent()
            assertTrue(runtime.retired.value)
            release.complete(Unit); runCurrent()
            val reply = assertIs<DirectCallReply.Ready>(result.await())
            assertEquals(DirectCallPhase.DECLINED, reply.call.phase)
            assertEquals(listOf("/v2/nearby/calls/incoming", "/v2/nearby/calls/$channel/decline"), f.paths)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty()); assertEquals(0, f.published)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun rejectDoesNotUseAnUnobservedIncomingSnapshot() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true; f.incomingMode = true
            val runtime = f.acquire(account)
            val ring = DirectCallSnapshot(channel, f.peer, actor, DirectCallPhase.RINGING, 2, 45100, null)
            assertEquals(DirectCallFailure.ACCESS_DENIED,
                assertIs<DirectCallReply.Failed>(runtime.requestDeclineIncomingCall(ring, first).await()).reason)
            assertTrue(f.paths.isEmpty()); assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty())
        } finally {f.close()}
    }

    @Test fun rejectRaceWithAcceptanceReportsConflictWithoutStoppingOrStartingMedia() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.callMode = true; f.incomingMode = true; f.declineConflict = true
            val runtime = f.acquire(account)
            val ring = assertIs<IncomingCallReply.Ready>(runtime.incomingCalls()).calls.single()
            assertEquals(DirectCallFailure.CONFLICT,
                assertIs<DirectCallReply.Failed>(runtime.requestDeclineIncomingCall(ring, first).await()).reason)
            assertEquals(listOf("/v2/nearby/calls/incoming", "/v2/nearby/calls/$channel/decline"), f.paths)
            assertEquals(0, f.launches); assertTrue(f.allocated.isEmpty()); assertFalse(runtime.state.value.wanted)
        } finally {f.close()}
    }

    @Test fun lateOutgoingPreferencesCannotRingOverNewChannelWithSameDestinationUuid() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var returned = false
        try {
            val account = f.login(); f.callMode = true
            f.owner.start(account, VoiceConversationTarget.OutgoingCall(channel, f.peer, f.key), first,
                readPreferences = {withContext(NonCancellable) {entered.complete(Unit); release.await(); returned = true; phone}},
                launch = f::launch); runCurrent()
            assertTrue(entered.isCompleted); assertTrue(f.paths.isEmpty())
            f.owner.start(account, channel, second, readPreferences = {phone}, launch = f::launch); runCurrent()
            val selected = assertNotNull(f.gate.current.value)
            assertEquals(1, f.published)
            release.complete(Unit); runCurrent(); assertTrue(returned)
            assertSame(selected, f.gate.current.value)
            assertEquals(VoiceConversationTarget.Channel(channel), f.owner.state.value.target)
            assertEquals(listOf("/v2/nearby/operations/${second.value}/join"), f.paths)
            assertEquals(1, f.published); assertEquals(0, f.closes)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun storageFailureReleasesForegroundDemandWithoutJoinOrLogout() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login()
            f.owner.start(account, channel, first, readPreferences = {error("Storage failed")}, launch = f::launch)
            runCurrent()
            assertEquals(1, f.launches)
            assertNull(f.gate.current.value)
            assertEquals(VoiceConversationPhase.FAILED, f.owner.state.value.phase)
            assertEquals(first, f.owner.state.value.operationId)
            assertTrue(f.paths.isEmpty()); assertTrue(f.allocated.isEmpty()); assertTrue(account.job.isActive)
        } finally {f.close()}
    }

    @Test fun explicitStopDuringReadinessPreventsPreferencesAndJoin() = runTest {
        val f = Fixture(this); var reads = 0
        try {
            val account = f.login(); f.acknowledgeLaunch = false
            f.owner.start(account, channel, first, readPreferences = {reads++; phone}, launch = f::launch)
            runCurrent(); val ticket = assertNotNull(f.gate.current.value)
            assertEquals(1, f.launches); assertEquals(0, reads)
            f.owner.stop(); runCurrent()
            assertNull(f.gate.current.value)
            assertFalse(f.gate.acknowledge(ticket, f.service))
            assertEquals(0, reads); assertTrue(f.paths.isEmpty()); assertTrue(f.allocated.isEmpty())
            assertEquals(VoiceConversationPhase.STOPPED, f.owner.state.value.phase)
            assertTrue(account.job.isActive)
        } finally {f.close()}
    }

    @Test fun cancelledUiCallerDoesNotCancelAcceptedRootStartup() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.acknowledgeLaunch = false
            val caller = launch {f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch); awaitCancellation()}
            runCurrent(); val ticket = assertNotNull(f.gate.current.value)
            caller.cancel(); runCurrent()
            assertTrue(f.gate.acknowledge(ticket, f.service)); runCurrent()
            assertEquals(listOf(phone), f.allocated)
            assertEquals(1, f.published)
            assertEquals(first, assertNotNull(f.runtime).state.value.operationId)
            assertEquals(VoiceConversationPhase.JOIN_SUBMITTED, f.owner.state.value.phase)
            assertSame(ticket, f.gate.current.value)
        } finally {f.close()}
    }

    @Test fun lateCancelledPreferencesCannotReleaseOrJoinOverNewerConversation() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var returned = false
        try {
            val account = f.login()
            f.owner.start(account, channel, first, readPreferences = {
                withContext(NonCancellable) {entered.complete(Unit); release.await(); returned = true; phone}
            }, launch = f::launch)
            runCurrent(); assertTrue(entered.isCompleted)
            val oldTicket = assertNotNull(f.gate.current.value)
            f.owner.stop(); runCurrent()
            f.owner.start(account, channel, second, readPreferences = {phone}, launch = f::launch)
            runCurrent(); val newTicket = assertNotNull(f.gate.current.value)
            assertNotSame(oldTicket, newTicket); assertEquals(1, f.published)
            release.complete(Unit); runCurrent(); assertTrue(returned)
            assertSame(newTicket, f.gate.current.value)
            assertEquals(second, f.owner.state.value.operationId)
            assertEquals(second, assertNotNull(f.runtime).state.value.operationId)
            assertEquals(1, f.paths.count {it.endsWith("/join")})
            assertEquals(1, f.published); assertEquals(0, f.closes)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun correlatedBackendFailureReleasesForegroundAfterJoinSubmission() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login(); f.rejectJoin = true
            f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch)
            runCurrent()
            assertEquals(1, f.paths.count {it.endsWith("/join")})
            assertEquals(first, assertNotNull(f.runtime).state.value.operationId)
            assertFalse(assertNotNull(f.runtime).state.value.wanted)
            assertNull(f.gate.current.value)
            assertEquals(VoiceConversationPhase.FAILED, f.owner.state.value.phase)
            assertTrue(f.allocated.isEmpty()); assertTrue(account.job.isActive)
        } finally {f.close()}
    }

    @Test fun logoutDuringPreparationReleasesDemandBeforeHeldReadReturns() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var returned = false
        try {
            val account = f.login()
            f.owner.start(account, channel, first, readPreferences = {
                withContext(NonCancellable) {entered.complete(Unit); release.await(); returned = true; phone}
            }, launch = f::launch)
            runCurrent(); assertTrue(entered.isCompleted); assertNotNull(f.gate.current.value)
            f.repository.logout(); runCurrent()
            assertFalse(account.job.isActive); assertFalse(returned)
            assertNull(f.gate.current.value)
            release.complete(Unit); runCurrent(); assertTrue(returned)
            assertTrue(f.paths.isEmpty()); assertTrue(f.allocated.isEmpty())
            assertEquals(VoiceConversationPhase.STOPPED, f.owner.state.value.phase)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun earlierTerminalOperationCannotReleaseNewPreparation() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val account = f.login()
            f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch)
            runCurrent(); assertEquals(1, f.published)
            f.owner.stop(); runCurrent(); assertFalse(assertNotNull(f.runtime).state.value.wanted)
            f.owner.start(account, channel, second, readPreferences = {entered.complete(Unit); release.await(); phone}, launch = f::launch)
            runCurrent(); assertTrue(entered.isCompleted)
            val ticket = assertNotNull(f.gate.current.value)
            assertEquals(first, assertNotNull(f.runtime).state.value.operationId)
            assertEquals(VoiceConversationPhase.PREPARING, f.owner.state.value.phase)
            release.complete(Unit); runCurrent()
            assertSame(ticket, f.gate.current.value)
            assertEquals(second, assertNotNull(f.runtime).state.value.operationId)
            assertEquals(2, f.published)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun obsoleteAcquisitionReturnCannotLaunchForegroundAfterStop() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var returned = false
        try {
            val account = f.login()
            f.afterAcquire = {withContext(NonCancellable) {entered.complete(Unit); release.await(); returned = true}}
            f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch)
            runCurrent(); assertTrue(entered.isCompleted); assertNotNull(f.runtime)
            f.owner.stop(); runCurrent(); assertFalse(returned)
            release.complete(Unit); runCurrent(); assertTrue(returned)
            assertNull(f.gate.current.value); assertEquals(0, f.launches)
            assertTrue(f.paths.isEmpty()); assertTrue(f.allocated.isEmpty()); assertTrue(account.job.isActive)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun foregroundLossFencesHeldPreferencesBeforeActualReturn() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var returned = false
        try {
            val account = f.login()
            f.owner.start(account, channel, first, readPreferences = {
                withContext(NonCancellable) {entered.complete(Unit); release.await(); returned = true; phone}
            }, launch = f::launch)
            runCurrent(); assertTrue(entered.isCompleted); assertNotNull(f.gate.current.value)
            assertTrue(f.gate.destroyed(f.service)); runCurrent()
            assertFalse(returned); assertEquals(VoiceConversationPhase.STOPPED, f.owner.state.value.phase)
            release.complete(Unit); runCurrent(); assertTrue(returned)
            assertTrue(f.paths.isEmpty()); assertTrue(f.allocated.isEmpty()); assertTrue(account.job.isActive)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun invalidOrReusedOperationPreservesEarnedConversation() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login()
            f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch)
            runCurrent(); val ticket = assertNotNull(f.gate.current.value); assertEquals(1, f.published)
            assertFailsWith<IllegalArgumentException> {f.owner.start(account, "invalid", second, readPreferences = {phone}, launch = f::launch)}
            assertFailsWith<IllegalArgumentException> {f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch)}
            runCurrent()
            assertSame(ticket, f.gate.current.value); assertEquals(first, f.owner.state.value.operationId)
            assertEquals(1, f.launches); assertEquals(0, f.closes)
        } finally {f.close()}
    }

    @Test fun liveAudioPairUsesOriginalConversationAccountAndForegroundWithoutReallocating()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true
        val pair=VoiceAudioPreferences(VoiceInputPreference.PHONE,VoiceOutputPreference.HEADSET)
        try {
            val account=f.login();assertEquals(VoiceAudioChangeResult.STALE,f.owner.configureAudio(first,pair))
            f.owner.start(account,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            assertEquals(1,f.published);val ticket=assertNotNull(f.gate.current.value)
            assertTrue(f.owner.setMuted(first,true));runCurrent();val muted=f.mutes.toList();val deafened=f.deafens.toList()
            assertEquals(VoiceAudioChangeResult.REQUESTED,f.owner.configureAudio(first,pair))
            assertEquals(listOf(pair),f.audioPairs);assertEquals(1,f.allocated.size);assertSame(ticket,f.gate.current.value)
            assertEquals(muted,f.mutes);assertEquals(deafened,f.deafens);assertTrue(account.job.isActive)
            assertEquals(VoiceAudioChangeResult.STALE,f.owner.configureAudio(second,pair));assertEquals(1,f.audioPairs.size)
            f.gate.stop(ticket);runCurrent()
            assertEquals(VoiceAudioChangeResult.STALE,f.owner.configureAudio(first,pair));assertEquals(1,f.audioPairs.size)
        } finally {f.close()}
    }
    @Test fun heldLiveAudioOldLoginCannotAcknowledgeNewAccountOrAutoRetryRejectedAdmission()=runTest {
        val f=Fixture(this);f.useBoundRuntime=true;val release=CompletableDeferred<Unit>()
        val pair=VoiceAudioPreferences(VoiceInputPreference.PHONE,VoiceOutputPreference.HEADSET)
        var pending:Deferred<VoiceAudioChangeResult>?=null
        try {
            val old=f.login();f.owner.start(old,channel,first,readPreferences={phone},launch=f::launch);runCurrent()
            f.audioRelease=release;pending=async {f.owner.configureAudio(first,pair)};runCurrent()
            assertTrue(f.audioEntered.isCompleted);assertEquals(listOf(pair),f.audioPairs)
            f.repository.logout();runCurrent();assertFalse(old.job.isActive)
            val fresh=f.login();assertEquals(old.userId,fresh.userId);assertNotSame(old,fresh)
            f.audioRelease=null;f.owner.start(fresh,channel,second,readPreferences={phone},launch=f::launch);runCurrent()
            assertEquals(1,f.allocated.size);assertEquals(VoiceConversationPhase.FAILED,f.owner.state.value.phase)
            release.complete(Unit);runCurrent();assertEquals(VoiceAudioChangeResult.STALE,pending.await())
            assertEquals(1,f.allocated.size);assertEquals(VoiceConversationPhase.FAILED,f.owner.state.value.phase)
            assertEquals(VoiceAudioChangeResult.STALE,f.owner.configureAudio(second,pair))
            val retry=VoiceOperationId("30000000-0000-0000-0000-000000000009")
            f.owner.start(fresh,channel,retry,readPreferences={phone},launch=f::launch);runCurrent()
            assertEquals(retry,f.owner.state.value.operationId);assertEquals(2,f.allocated.size)
            val next=pair.copy(input=VoiceInputPreference.HEADSET)
            assertEquals(VoiceAudioChangeResult.REQUESTED,f.owner.configureAudio(retry,next))
            assertEquals(listOf(pair,next),f.audioPairs);assertTrue(assertNotNull(f.runtime).state.value.wanted);assertTrue(fresh.job.isActive)
        } finally {release.complete(Unit);pending?.cancel();f.close()}
    }

    @Test fun rootMuteAndDeafenIssueNativeCommandsAndRestoreMuteIntent() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login()
            f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch)
            runCurrent(); assertEquals(1, f.published)
            assertTrue(f.owner.setMuted(first, true)); runCurrent()
            assertTrue(assertNotNull(f.runtime).state.value.muted); assertTrue(f.mutes.last())
            assertTrue(f.owner.setDeafened(first, true)); runCurrent()
            assertTrue(assertNotNull(f.runtime).state.value.deafened); assertTrue(f.deafens.last())
            assertTrue(f.owner.setDeafened(first, false)); runCurrent()
            assertFalse(assertNotNull(f.runtime).state.value.deafened); assertFalse(f.deafens.last())
            assertTrue(assertNotNull(f.runtime).state.value.muted); assertTrue(f.mutes.last())
            assertTrue(f.owner.setMuted(first, false)); runCurrent(); assertFalse(f.mutes.last())
        } finally {f.close()}
    }

    @Test fun pendingAndStaleControlsCannotAlterReplacementConversation() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val account = f.login()
            f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch)
            runCurrent(); assertEquals(1, f.published)
            f.owner.start(account, channel, second, readPreferences = {entered.complete(Unit); release.await(); phone}, launch = f::launch)
            runCurrent(); assertTrue(entered.isCompleted)
            assertFalse(f.owner.setMuted(first, true)); assertFalse(f.owner.setDeafened(first, true))
            assertFalse(f.owner.setMuted(second, true)); assertFalse(f.owner.setDeafened(second, true))
            release.complete(Unit); runCurrent(); assertEquals(2, f.published)
            assertFalse(f.owner.setMuted(first, true)); assertFalse(f.owner.setDeafened(first, true)); runCurrent()
            assertEquals(second, assertNotNull(f.runtime).state.value.operationId)
            assertFalse(assertNotNull(f.runtime).state.value.muted); assertFalse(assertNotNull(f.runtime).state.value.deafened)
            assertFalse(f.mutes.contains(true)); assertFalse(f.deafens.contains(true))
            f.owner.stop(); runCurrent(); assertFalse(f.owner.setMuted(second, true))
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun rootProjectionDoesNotInventMediaFromSuccessfulSdkCalls() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login()
            f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch)
            runCurrent(); assertEquals(1, f.published)
            val initial = assertNotNull(f.owner.state.value.voice)
            assertEquals(first, initial.operationId); assertEquals(VoicePhase.CONNECTING, initial.phase)
            assertFalse(initial.transportConnected); assertFalse(initial.captureStarted); assertFalse(initial.published)
            assertFalse(initial.renderStarted); assertTrue(initial.subscribedPeers.isEmpty())
            val (tag, emit) = f.emitters.single()
            emit(VoiceSessionEvent.TransportConnected(tag)); runCurrent()
            val connected = assertNotNull(f.owner.state.value.voice)
            assertTrue(connected.transportConnected); assertFalse(connected.captureStarted); assertFalse(connected.renderStarted)
            emit(VoiceSessionEvent.CaptureStarted(tag)); emit(VoiceSessionEvent.Published(tag))
            emit(VoiceSessionEvent.Subscribed(tag, "peer")); emit(VoiceSessionEvent.RenderStarted(tag)); runCurrent()
            val earned = assertNotNull(f.owner.state.value.voice)
            assertTrue(earned.captureStarted); assertTrue(earned.published); assertTrue(earned.renderStarted)
            assertEquals(setOf("peer"), earned.subscribedPeers)
            assertTrue(f.owner.setMuted(first, true)); runCurrent(); assertTrue(assertNotNull(f.owner.state.value.voice).muted)
            f.owner.stop(); runCurrent(); assertNull(f.owner.state.value.voice); assertTrue(account.job.isActive)
        } finally {f.close()}
    }

    @Test fun oldMediaCannotPopulatePendingOrReplacementTarget() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val account = f.login()
            f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch)
            runCurrent(); val (oldTag, oldEmit) = f.emitters.single()
            oldEmit(VoiceSessionEvent.TransportConnected(oldTag)); runCurrent()
            assertTrue(assertNotNull(f.owner.state.value.voice).transportConnected)
            f.owner.start(account, channel, second, readPreferences = {entered.complete(Unit); release.await(); phone}, launch = f::launch)
            runCurrent(); assertTrue(entered.isCompleted); assertNull(f.owner.state.value.voice)
            oldEmit(VoiceSessionEvent.RenderStarted(oldTag)); runCurrent(); assertNull(f.owner.state.value.voice)
            release.complete(Unit); runCurrent(); assertEquals(2, f.published)
            val fresh = assertNotNull(f.owner.state.value.voice)
            assertEquals(second, fresh.operationId); assertFalse(fresh.transportConnected); assertFalse(fresh.renderStarted)
            oldEmit(VoiceSessionEvent.RenderStarted(oldTag)); oldEmit(VoiceSessionEvent.Published(oldTag)); runCurrent()
            assertFalse(assertNotNull(f.owner.state.value.voice).renderStarted); assertFalse(assertNotNull(f.owner.state.value.voice).published)
            val (newTag, newEmit) = f.emitters.last()
            newEmit(VoiceSessionEvent.TransportConnected(newTag)); runCurrent()
            assertEquals(second, assertNotNull(f.owner.state.value.voice).operationId)
            assertTrue(assertNotNull(f.owner.state.value.voice).transportConnected)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun logoutClearsEarnedRootMediaSnapshot() = runTest {
        val f = Fixture(this)
        try {
            val account = f.login()
            f.owner.start(account, channel, first, readPreferences = {phone}, launch = f::launch)
            runCurrent(); val (tag, emit) = f.emitters.single()
            emit(VoiceSessionEvent.CaptureStarted(tag)); runCurrent()
            assertTrue(assertNotNull(f.owner.state.value.voice).captureStarted)
            f.repository.logout(); runCurrent()
            assertFalse(account.job.isActive); assertNull(f.owner.state.value.voice)
            assertEquals(VoiceConversationPhase.STOPPED, f.owner.state.value.phase)
            emit(VoiceSessionEvent.Published(tag)); runCurrent(); assertNull(f.owner.state.value.voice)
        } finally {f.close()}
    }

    private inner class Fixture(val test: TestScope,withAudience: Boolean=false,withChannelRoster:Boolean=false) {
        var beforeAudience: suspend ()->Unit={}
        var afterAudience: suspend ()->Unit={}
        val audiences=mutableListOf<ChannelSharingAudienceSession>()
        val audioPairs=mutableListOf<VoiceAudioPreferences>()
        val audioEntered=CompletableDeferred<Unit>()
        var audioRelease:CompletableDeferred<Unit>?=null
        var time = 100L
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        var authToken = "token"
        val repository = DefaultSocialRepository(Transport {authToken}, Store(), test.backgroundScope)
        val sharingAuthorizations = mutableListOf<String?>()
        var sharingPositiveRelease: CompletableDeferred<Unit>? = null
        var sharingRevokeRelease: CompletableDeferred<Unit>? = null
        val sharingPositiveEntered = CompletableDeferred<Unit>()
        val sharingRevokeEntered = CompletableDeferred<Unit>()
        var sharingAudienceBody: String? = null
        var sharingAudienceStatus = HttpStatusCode.OK
        var sharingRevokeStatus=HttpStatusCode.OK
        var sharingCredit=1000L
        var sharingMode="LOCATION"
        var voiceGrantMillis=10000L
        var packetRelease: CompletableDeferred<Unit>?=null
        val packetSequences=mutableListOf<Long>()
        val packetCompletions=mutableListOf<Long>()
        var packetCredit=1000L
        var packetElapsed=0L
        var packetStatus=HttpStatusCode.OK
        val service = Any()
        var nonce = 0
        val gate = VoiceForegroundGate({true}, {"50000000-0000-0000-0000-${(++nonce).toString().padStart(12, '0')}"})
        var launches = 0; var acknowledgeLaunch = true; var rejectJoin = false
        var callMode = false; var incomingMode = false; var incomingEmpty = false
        var callRinging = false; var rosterDenied = false
        var statusPhase: String? = null
        var statusRingCredit: Long? = 1000
        var statusElapsed = 0L
        var statusRelease: CompletableDeferred<Unit>? = null
        val statusEntered = CompletableDeferred<Unit>()
        var statusHandlerFinished = false
        val channelRosters=mutableListOf<VoiceRosterAccountSession>()
        var channelRosterStatus=HttpStatusCode.OK
        var channelRosterName:String?=null
        var channelRosterRelease:CompletableDeferred<Unit>?=null
        val channelRosterEntered=CompletableDeferred<Unit>()
        val channelRosterAuthorizations=mutableListOf<String?>()
        val channelRosterPulses=MutableSharedFlow<Unit>()
        val rosters = mutableListOf<AuthenticatedCallRosterOwner>()
        var beforeRoster: suspend () -> Unit = {}
        var afterRoster: suspend () -> Unit = {}
        var declineRelease: CompletableDeferred<Unit>? = null
        var declineConflict = false
        val declineEntered = CompletableDeferred<Unit>()
        val peer = "70000000-0000-0000-0000-000000000007"
        val key = "80000000-0000-0000-0000-000000000008"
        var callDestination=channel
        private fun callSnapshot(phase: String, outcome: String = "null") =
            """{"callId":"$callDestination","callerId":"${if (incomingMode) peer else actor}","calleeId":"${if (incomingMode) actor else peer}","phase":"$phase","revision":2,"ringDeadlineEpochMillis":45100,"outcome":$outcome}"""
        private fun callGrant() = """{"phase":"CONNECTING","sessionId":"40000000-0000-0000-0000-000000000004","remainingLeaseMillis":10000,"credentials":{"serverUrl":"wss://media.test","participantToken":"secret","roomEpoch":"volty-call-$callDestination"}}"""
        var published = 0; var closes = 0
        val mutes = mutableListOf<Boolean>(); val deafens = mutableListOf<Boolean>()
        val paths = mutableListOf<String>()
        val allocated = mutableListOf<VoiceAudioPreferences>()
        val emitters = mutableListOf<Pair<VoiceSessionTag, (VoiceSessionEvent) -> Unit>>()
        var runtime: AuthenticatedVoiceRuntime? = null
        var useBoundRuntime=false
        var boundRuntime: VoiceAccountRuntimeBinding?=null
        var sharingDispatch: (VoiceSharingDispatch)->Unit = {}
        var afterAcquire: suspend () -> Unit = {}
        val client = HttpClient(MockEngine) {engine {
            dispatcher = this@Fixture.dispatcher
            addHandler {
                paths += it.url.encodedPath
                if(it.url.encodedPath.startsWith("/v2/nearby/channels/") && it.url.encodedPath.endsWith("/roster")) {
                    channelRosterAuthorizations+=it.headers[io.ktor.http.HttpHeaders.Authorization]
                    val release=channelRosterRelease
                    val name=channelRosterName
                    withContext(NonCancellable) {channelRosterEntered.complete(Unit);release?.await()}
                    val destination=it.url.encodedPath.removeSuffix("/roster").substringAfterLast('/')
                    val names=name?.let { """, "profileNames":{"$peer":"$it"}""" } ?: ""
                    return@addHandler respond("""{"channelId":"$destination","revision":8,"serverTimeEpochMillis":100000,"peers":[{"actorId":"$peer","sessionId":"$peer","operationId":"${first.value}","phase":"CONNECTED","deadlineEpochMillis":110000,"mediaPresence":"CONNECTED","participantSid":"observed-peer"}]$names}""",channelRosterStatus)
                }
                if(it.url.encodedPath.endsWith("/sharing/audience")) {
                    sharingAuthorizations += it.headers[io.ktor.http.HttpHeaders.Authorization]
                    withContext(NonCancellable) {sharingPositiveEntered.complete(Unit);sharingPositiveRelease?.await()}
                    return@addHandler respond(sharingAudienceBody ?: """{"channelId":"$channel","serverTimeEpochMillis":1100,"peers":[]}""",sharingAudienceStatus)
                }
                if (it.url.encodedPath.startsWith("/v2/nearby/sharing/")) {
                    sharingAuthorizations += it.headers[io.ktor.http.HttpHeaders.Authorization]
                    if(it.url.encodedPath.endsWith("/packet")) {
                        val body=(it.body as io.ktor.http.content.TextContent).text
                        val sequence=kotlinx.serialization.json.Json.parseToJsonElement(body).let {value ->
                            (value as kotlinx.serialization.json.JsonObject).getValue("sequence").toString().toLong()
                        }
                        packetSequences+=sequence
                        withContext(NonCancellable) {sharingPositiveEntered.complete(Unit);sharingPositiveRelease?.await();packetRelease?.await()}
                        packetCompletions+=sequence
                        time+=packetElapsed
                        val parts=it.url.encodedPath.split('/')
                        return@addHandler respond("""{"sessionId":"${parts[4]}","generation":"${parts[5]}","sequence":$sequence,"remainingMillis":$packetCredit}""",packetStatus)
                    }
                    val negative = it.url.encodedPath.endsWith("/revoke")
                    if (negative) {sharingRevokeEntered.complete(Unit); sharingRevokeRelease?.await()}
                    else withContext(NonCancellable) {sharingPositiveEntered.complete(Unit); sharingPositiveRelease?.await()}
                    val parts = it.url.encodedPath.split('/')
                    return@addHandler respond("""{"sessionId":"${parts[4]}","generation":"${parts[5]}","phase":"${if (negative) "REVOKED" else "ACTIVE"}","mode":${if (negative) "null" else "\"$sharingMode\""},"remainingMillis":${if (negative) 0 else sharingCredit}}""",if(negative) sharingRevokeStatus else HttpStatusCode.OK)
                }
                if (callMode && it.url.encodedPath.contains("/calls/")) {
                    if (it.url.encodedPath in listOf("/v2/nearby/calls/$channel", "/v2/nearby/calls/${second.value}") && statusPhase != null) {
                        withContext(NonCancellable) {statusEntered.complete(Unit); statusRelease?.await(); statusHandlerFinished = true}
                        time += statusElapsed
                        val snapshot = callSnapshot(statusPhase!!, if (statusPhase == "ENDED") "\"CALLER_ENDED\"" else if (statusPhase == "CANCELLED") "\"CALLER_CANCELLED\"" else "null")
                            .replace("\"callId\":\"$channel\"", "\"callId\":\"${it.url.encodedPath.substringAfterLast('/')}\"")
                        return@addHandler respond("""{"call":$snapshot,"remainingRingMillis":${if (statusPhase == "RINGING") statusRingCredit else "null"}}""")
                    }
                    if (it.url.encodedPath.endsWith("roster")) {
                        if (rosterDenied) return@addHandler respond("{}", HttpStatusCode.Forbidden)
                        return@addHandler respond("""{"callId":"$callDestination","revision":8,"serverTimeEpochMillis":100000,"peers":[{"actorId":"$actor","sessionId":"40000000-0000-0000-0000-000000000004","phase":"CONNECTING","deadlineEpochMillis":110000,"mediaPresence":"UNKNOWN"},{"actorId":"$peer","sessionId":"60000000-0000-0000-0000-000000000006","phase":"CONNECTED","deadlineEpochMillis":110000,"mediaPresence":"CONNECTED","participantSid":"observed-peer"}]}""")
                    }
                    if (it.url.encodedPath.endsWith("decline")) {
                        declineEntered.complete(Unit); declineRelease?.await()
                        if (declineConflict) return@addHandler respond("""{"code":"call_not_ringing"}""", HttpStatusCode.Conflict)
                        return@addHandler respond("""{"call":${callSnapshot("DECLINED", "\"CALLEE_DECLINED\"")}}""")
                    }
                    return@addHandler respond(when {
                        it.url.encodedPath.endsWith("incoming") -> if(incomingEmpty) """{"calls":[]}""" else """{"calls":[${callSnapshot("RINGING") }]}"""
                        it.url.encodedPath.endsWith("create") -> """{"call":${callSnapshot(if (callRinging) "RINGING" else "ACCEPTED")}}"""
                        it.url.encodedPath.endsWith("accept") || it.url.encodedPath.endsWith("resume") -> """{"call":${callSnapshot("ACCEPTED")},"session":${callGrant()}}"""
                        else -> """{"call":${callSnapshot("ENDED", if (incomingMode) "\"CALLEE_ENDED\"" else "\"CALLER_ENDED\"")}}"""
                    })
                }
                val operation = it.url.encodedPath.removeSuffix("/join").substringAfterLast('/')
                if (rejectJoin && it.url.encodedPath.endsWith("/join")) respond("{}", HttpStatusCode.Forbidden)
                else respond(if (it.url.encodedPath.endsWith("/join"))
                    """{"phase":"CONNECTING","operationId":"$operation","sessionId":"40000000-0000-0000-0000-000000000004","remainingLeaseMillis":$voiceGrantMillis,"credentials":{"serverUrl":"wss://sfu.example.test","participantToken":"secret","roomEpoch":"room"}}"""
                else """{"phase":"CANCELLED","sessionId":"40000000-0000-0000-0000-000000000004","cleanupPending":false}""")
            }
        }}
        val coordinator = VoiceAccountCoordinator(client, "https://example.test/v2/nearby", dispatcher,
            MutableSharedFlow<Unit>(), {time}, gate::allows)
        val foreground = object : VoiceConversationForegroundPort {
            override val current = gate.current
            override fun request(runtime: AuthenticatedVoiceRuntime) = gate.request(runtime, runtime::requestDisconnect)
            override fun stop(ticket: VoiceForegroundTicket) = gate.stop(ticket)
            override suspend fun awaitReady(ticket: VoiceForegroundTicket): Boolean = combine(gate.current, gate.ready) {selected, ready ->
                when {selected !== ticket -> false; ready === ticket -> gate.allows(ticket.owner); else -> null}
            }.filterNotNull().first()
        }
        val owner = VoiceConversationStartup(dispatcher, foreground, VoiceConversationCallRosterFactory {account, tag, own ->
            beforeRoster()
            AuthenticatedCallRosterOwner.create(account, client, "https://example.test/v2/nearby", tag, own,
                dispatcher, MutableSharedFlow(), {100}).also {rosters += it; afterRoster()}
        }, if(withAudience) VoiceConversationChannelAudienceFactory {account,runtime,original ->
            beforeAudience()
            ChannelSharingAudienceSession.create(account,runtime,original,client,"https://example.test/v2/nearby",dispatcher,{time})
                .also {audiences+=it;afterAudience()}
        } else null, if(withChannelRoster) VoiceConversationChannelRosterFactory {account,tag ->
            VoiceRosterAccountSession(account,client,"https://example.test/v2/nearby",tag,dispatcher,channelRosterPulses,{time})
                .also {channelRosters+=it}
        } else null, ::acquire)
        suspend fun acquire(account: SocialAuthenticatedAccount): AuthenticatedVoiceRuntime {
            val audio = VoiceSessionAudioFactory({error("Must prepare")}, requiresPreparation = true) {tag, prefs, _, emit ->
                allocated += prefs
                emitters += tag to emit
                object : VoiceSdkSession {
                    override suspend fun connect(credentials: VoiceConnectionCredentials) = Unit
                    override suspend fun publishMicrophone(muted: Boolean): Boolean {published++; return true}
                    override suspend fun setMuted(muted: Boolean): Boolean {mutes += muted; return true}
                    override fun setDeafened(deafened: Boolean) {deafens += deafened}
                    override suspend fun configureAudio(preferences:VoiceAudioPreferences):VoiceAudioChangeResult {
                        val release=audioRelease;audioPairs+=preferences
                        withContext(NonCancellable) {audioEntered.complete(Unit);release?.await()}
                        return VoiceAudioChangeResult.REQUESTED
                    }
                    override suspend fun close() {closes++}
                }
            }
            return (if(useBoundRuntime) coordinator.acquireBound(account,audio).also {boundRuntime=it}.voice
                else coordinator.acquire(account, audio, {sharingDispatch(it)})).also {runtime = it; afterAcquire()}
        }
        fun launch(ticket: VoiceForegroundTicket): Boolean {launches++; if (acknowledgeLaunch) assertTrue(gate.acknowledge(ticket, service)); return true}
        suspend fun login(): SocialAuthenticatedAccount {
            test.runCurrent(); assertIs<SocialResult.Success<Unit>>(repository.login(LoginRequest("rider", "password")))
            return assertNotNull(repository.authenticatedAccount.value)
        }
        suspend fun close() {owner.stop(); coordinator.retireAll(); test.runCurrent(); client.close()}
    }
    private class Store : SocialCredentialStore {
        var value: SocialCredentials? = null
        override suspend fun read() = value
        override suspend fun write(credentials: SocialCredentials) {value = credentials}
        override suspend fun clear() {value = null}
    }
    private inner class Transport(private val token: () -> String = {"token"}) : SocialTransport by UnavailableSocialTransport() {
        override suspend fun login(request: LoginRequest) = SocialResult.Success(SessionCredentials(token(), "refresh", Long.MAX_VALUE))
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(SocialSession.Authenticated(SocialUserId(actor), "Rider", SessionTokenState.ACTIVE, true))
    }
}
