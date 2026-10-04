package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
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

    private inner class Fixture(val test: TestScope) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val repository = DefaultSocialRepository(Transport(), Store(), test.backgroundScope)
        val service = Any()
        var nonce = 0
        val gate = VoiceForegroundGate({true}, {"50000000-0000-0000-0000-${(++nonce).toString().padStart(12, '0')}"})
        var launches = 0; var acknowledgeLaunch = true; var rejectJoin = false
        var published = 0; var closes = 0
        val mutes = mutableListOf<Boolean>(); val deafens = mutableListOf<Boolean>()
        val paths = mutableListOf<String>()
        val allocated = mutableListOf<VoiceAudioPreferences>()
        var runtime: AuthenticatedVoiceRuntime? = null
        var afterAcquire: suspend () -> Unit = {}
        val client = HttpClient(MockEngine) {engine {
            dispatcher = this@Fixture.dispatcher
            addHandler {
                paths += it.url.encodedPath
                val operation = it.url.encodedPath.removeSuffix("/join").substringAfterLast('/')
                if (rejectJoin && it.url.encodedPath.endsWith("/join")) respond("{}", HttpStatusCode.Forbidden)
                else respond(if (it.url.encodedPath.endsWith("/join"))
                    """{"phase":"CONNECTING","operationId":"$operation","sessionId":"40000000-0000-0000-0000-000000000004","remainingLeaseMillis":10000,"credentials":{"serverUrl":"wss://sfu.example.test","participantToken":"secret","roomEpoch":"room"}}"""
                else """{"phase":"CANCELLED","sessionId":"40000000-0000-0000-0000-000000000004","cleanupPending":false}""")
            }
        }}
        val coordinator = VoiceAccountCoordinator(client, "https://example.test/v2/nearby", dispatcher,
            MutableSharedFlow<Unit>(), {100}, gate::allows)
        val foreground = object : VoiceConversationForegroundPort {
            override val current = gate.current
            override fun request(runtime: AuthenticatedVoiceRuntime) = gate.request(runtime, runtime::requestDisconnect)
            override fun stop(ticket: VoiceForegroundTicket) = gate.stop(ticket)
            override suspend fun awaitReady(ticket: VoiceForegroundTicket): Boolean = combine(gate.current, gate.ready) {selected, ready ->
                when {selected !== ticket -> false; ready === ticket -> gate.allows(ticket.owner); else -> null}
            }.filterNotNull().first()
        }
        val owner = VoiceConversationStartup(dispatcher, foreground) {account ->
            val audio = VoiceSessionAudioFactory({error("Must prepare")}, requiresPreparation = true) {_, prefs, _, _ ->
                allocated += prefs
                object : VoiceSdkSession {
                    override suspend fun connect(credentials: VoiceConnectionCredentials) = Unit
                    override suspend fun publishMicrophone(muted: Boolean): Boolean {published++; return true}
                    override suspend fun setMuted(muted: Boolean): Boolean {mutes += muted; return true}
                    override fun setDeafened(deafened: Boolean) {deafens += deafened}
                    override suspend fun close() {closes++}
                }
            }
            coordinator.acquire(account, audio, {}).also {runtime = it; afterAcquire()}
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
