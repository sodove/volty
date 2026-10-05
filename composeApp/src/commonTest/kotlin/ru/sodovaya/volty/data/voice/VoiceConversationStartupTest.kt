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
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceConversationStartupTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val channel = "20000000-0000-0000-0000-000000000002"
    private val first = VoiceOperationId("30000000-0000-0000-0000-000000000003")
    private val second = VoiceOperationId("30000000-0000-0000-0000-000000000005")
    private val phone = VoiceAudioPreferences(VoiceInputPreference.PHONE, VoiceOutputPreference.HEADSET)

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

    private class Notifications : IncomingCallNotificationPort {
        var allowed = true
        var throwOnCancel = false
        val shown = mutableListOf<IncomingCallNotificationTicket>()
        val cancelled = mutableListOf<IncomingCallNotificationTicket>()
        val remaining = mutableListOf<Long>()
        override fun show(ticket: IncomingCallNotificationTicket, call: DirectCallSnapshot, remainingMillis: Long): Boolean {
            shown += ticket; remaining += remainingMillis; return allowed
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

    private inner class Fixture(val test: TestScope) {
        var time = 100L
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val repository = DefaultSocialRepository(Transport(), Store(), test.backgroundScope)
        val service = Any()
        var nonce = 0
        val gate = VoiceForegroundGate({true}, {"50000000-0000-0000-0000-${(++nonce).toString().padStart(12, '0')}"})
        var launches = 0; var acknowledgeLaunch = true; var rejectJoin = false
        var callMode = false; var incomingMode = false
        var callRinging = false; var rosterDenied = false
        var statusPhase: String? = null
        var statusRingCredit: Long? = 1000
        var statusElapsed = 0L
        var statusRelease: CompletableDeferred<Unit>? = null
        val statusEntered = CompletableDeferred<Unit>()
        var statusHandlerFinished = false
        val rosters = mutableListOf<AuthenticatedCallRosterOwner>()
        var beforeRoster: suspend () -> Unit = {}
        var afterRoster: suspend () -> Unit = {}
        var declineRelease: CompletableDeferred<Unit>? = null
        var declineConflict = false
        val declineEntered = CompletableDeferred<Unit>()
        val peer = "70000000-0000-0000-0000-000000000007"
        val key = "80000000-0000-0000-0000-000000000008"
        private fun callSnapshot(phase: String, outcome: String = "null") =
            """{"callId":"$channel","callerId":"${if (incomingMode) peer else actor}","calleeId":"${if (incomingMode) actor else peer}","phase":"$phase","revision":2,"ringDeadlineEpochMillis":45100,"outcome":$outcome}"""
        private fun callGrant() = """{"phase":"CONNECTING","sessionId":"40000000-0000-0000-0000-000000000004","remainingLeaseMillis":10000,"credentials":{"serverUrl":"wss://media.test","participantToken":"secret","roomEpoch":"volty-call-$channel"}}"""
        var published = 0; var closes = 0
        val mutes = mutableListOf<Boolean>(); val deafens = mutableListOf<Boolean>()
        val paths = mutableListOf<String>()
        val allocated = mutableListOf<VoiceAudioPreferences>()
        val emitters = mutableListOf<Pair<VoiceSessionTag, (VoiceSessionEvent) -> Unit>>()
        var runtime: AuthenticatedVoiceRuntime? = null
        var afterAcquire: suspend () -> Unit = {}
        val client = HttpClient(MockEngine) {engine {
            dispatcher = this@Fixture.dispatcher
            addHandler {
                paths += it.url.encodedPath
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
                        return@addHandler respond("""{"callId":"$channel","revision":8,"serverTimeEpochMillis":100000,"peers":[{"actorId":"$actor","sessionId":"40000000-0000-0000-0000-000000000004","phase":"CONNECTING","deadlineEpochMillis":110000,"mediaPresence":"UNKNOWN"},{"actorId":"$peer","sessionId":"60000000-0000-0000-0000-000000000006","phase":"CONNECTED","deadlineEpochMillis":110000,"mediaPresence":"CONNECTED","participantSid":"observed-peer"}]}""")
                    }
                    if (it.url.encodedPath.endsWith("decline")) {
                        declineEntered.complete(Unit); declineRelease?.await()
                        if (declineConflict) return@addHandler respond("""{"code":"call_not_ringing"}""", HttpStatusCode.Conflict)
                        return@addHandler respond("""{"call":${callSnapshot("DECLINED", "\"CALLEE_DECLINED\"")}}""")
                    }
                    return@addHandler respond(when {
                        it.url.encodedPath.endsWith("incoming") -> """{"calls":[${callSnapshot("RINGING") }]}"""
                        it.url.encodedPath.endsWith("create") -> """{"call":${callSnapshot(if (callRinging) "RINGING" else "ACCEPTED")}}"""
                        it.url.encodedPath.endsWith("accept") || it.url.encodedPath.endsWith("resume") -> """{"call":${callSnapshot("ACCEPTED")},"session":${callGrant()}}"""
                        else -> """{"call":${callSnapshot("ENDED", if (incomingMode) "\"CALLEE_ENDED\"" else "\"CALLER_ENDED\"")}}"""
                    })
                }
                val operation = it.url.encodedPath.removeSuffix("/join").substringAfterLast('/')
                if (rejectJoin && it.url.encodedPath.endsWith("/join")) respond("{}", HttpStatusCode.Forbidden)
                else respond(if (it.url.encodedPath.endsWith("/join"))
                    """{"phase":"CONNECTING","operationId":"$operation","sessionId":"40000000-0000-0000-0000-000000000004","remainingLeaseMillis":10000,"credentials":{"serverUrl":"wss://sfu.example.test","participantToken":"secret","roomEpoch":"room"}}"""
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
        }, ::acquire)
        suspend fun acquire(account: SocialAuthenticatedAccount): AuthenticatedVoiceRuntime {
            val audio = VoiceSessionAudioFactory({error("Must prepare")}, requiresPreparation = true) {tag, prefs, _, emit ->
                allocated += prefs
                emitters += tag to emit
                object : VoiceSdkSession {
                    override suspend fun connect(credentials: VoiceConnectionCredentials) = Unit
                    override suspend fun publishMicrophone(muted: Boolean): Boolean {published++; return true}
                    override suspend fun setMuted(muted: Boolean): Boolean {mutes += muted; return true}
                    override fun setDeafened(deafened: Boolean) {deafens += deafened}
                    override suspend fun close() {closes++}
                }
            }
            return coordinator.acquire(account, audio, {}).also {runtime = it; afterAcquire()}
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
    private inner class Transport : SocialTransport by UnavailableSocialTransport() {
        override suspend fun login(request: LoginRequest) = SocialResult.Success(SessionCredentials("token", "refresh", Long.MAX_VALUE))
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(SocialSession.Authenticated(SocialUserId(actor), "Rider", SessionTokenState.ACTIVE, true))
    }
}
