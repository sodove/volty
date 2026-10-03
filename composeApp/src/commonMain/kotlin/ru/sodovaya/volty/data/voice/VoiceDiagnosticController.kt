package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.voice.*

data class VoiceDiagnosticGroup(val id: String, val name: String)
interface VoiceDiagnosticPort {
    suspend fun groups(): List<VoiceDiagnosticGroup>
    suspend fun leaveLegacyVoice()
    suspend fun join(group: String): VoiceConnectionCredentials
    suspend fun leave(group: String)
}
enum class VoiceDiagnosticPhase { IDLE, CONNECTING, RUNNING, STOPPING, ERROR, CLEANUP_FAILED }
data class VoiceDiagnosticUiState(
    val groups: List<VoiceDiagnosticGroup> = emptyList(),
    val phase: VoiceDiagnosticPhase = VoiceDiagnosticPhase.IDLE,
    val error: VoiceEngineError? = null,
    val diagnostics: VoiceDiagnostics? = null,
    val muted: Boolean = false,
    val deafened: Boolean = false,
)

/** Commands run on one owner dispatcher (Android Main). Tokens are never stored in UI state. */
class VoiceDiagnosticController(
    private val scope: CoroutineScope,
    private val port: VoiceDiagnosticPort,
    private val makeDriver: (VoiceSessionTag, VoiceAudioPreferences, VoiceDiagnostics) -> VoiceEngineDriver,
) {
    private val mutable = MutableStateFlow(VoiceDiagnosticUiState())
    val state = mutable.asStateFlow()
    private val stopMutex = Mutex()
    private var generation = 0L
    private var activeTag: VoiceSessionTag? = null
    private var activeGroup: String? = null
    private var startJob: Job? = null
    private var eventJob: Job? = null
    private var driver: VoiceEngineDriver? = null

    /** Diagnostic intent only: legacy credentials provide no v2 lease evidence. */
    fun authorizesEngine(tag: VoiceSessionTag): Boolean = activeTag == tag &&
        mutable.value.phase in listOf(VoiceDiagnosticPhase.CONNECTING, VoiceDiagnosticPhase.RUNNING)

    fun loadGroups() { scope.launch {
        try { val groups = port.groups(); mutable.update { it.copy(groups = groups, error = null) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(error = VoiceEngineError.CONNECTION) } }
    } }

    fun start(group: String, preferences: VoiceAudioPreferences) {
        if (activeTag != null || mutable.value.phase !in listOf(VoiceDiagnosticPhase.IDLE, VoiceDiagnosticPhase.ERROR)) return
        val tag = VoiceSessionTag(VoiceSessionId("diagnostic-${++generation}"), VoiceGeneration(generation))
        val diagnostics = VoiceDiagnostics(tag.generation)
        activeTag = tag
        mutable.update { it.copy(phase = VoiceDiagnosticPhase.CONNECTING, error = null, diagnostics = diagnostics, muted = false, deafened = false) }
        startJob = scope.launch {
            try {
                port.leaveLegacyVoice()
                ensureActive()
                if (activeTag != tag || mutable.value.phase != VoiceDiagnosticPhase.CONNECTING) return@launch
                activeGroup = group // Only this experiment's issued join may require leave.
                val credentials = port.join(group)
                ensureActive()
                if (activeTag != tag || mutable.value.phase != VoiceDiagnosticPhase.CONNECTING) return@launch
                val owned = makeDriver(tag, preferences, diagnostics).also { driver = it }
                eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { owned.events.collect { event ->
                    val failed = when (event) {
                        is VoiceSessionEvent.EngineFailed -> event.tag == tag
                        is VoiceSessionEvent.Failed -> event.generation == tag.generation
                        else -> false
                    }
                    if (activeTag == tag && failed) failAndStop(tag, VoiceEngineError.CONNECTION)
                } }
                val result = owned.start(VoiceSessionEffect.StartEngine(tag), credentials)
                ensureActive()
                if (activeTag != tag || mutable.value.phase != VoiceDiagnosticPhase.CONNECTING) return@launch
                if (result.isSuccess) mutable.update { it.copy(phase = VoiceDiagnosticPhase.RUNNING) }
                else failAndStop(tag, (result.exceptionOrNull() as? VoiceEngineException)?.code ?: VoiceEngineError.CONNECTION)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failAndStop(tag, (error as? VoiceEngineException)?.code ?: VoiceEngineError.CONNECTION) }
        }
    }

    fun setMuted(value: Boolean) { scope.launch {
        val tag = activeTag ?: return@launch
        val owned = driver ?: return@launch
        if (mutable.value.phase != VoiceDiagnosticPhase.RUNNING) return@launch
        val result = owned.setMuted(tag, value)
        if (activeTag == tag && mutable.value.phase == VoiceDiagnosticPhase.RUNNING) {
            if (result.isSuccess) mutable.update { it.copy(muted = value) }
            else failAndStop(tag, VoiceEngineError.MUTE)
        }
    } }

    fun setDeafened(value: Boolean) { scope.launch {
        val tag = activeTag ?: return@launch
        val owned = driver ?: return@launch
        if (mutable.value.phase != VoiceDiagnosticPhase.RUNNING) return@launch
        val result = owned.setDeafened(tag, value)
        if (activeTag == tag && mutable.value.phase == VoiceDiagnosticPhase.RUNNING) {
            if (result.isSuccess) mutable.update { it.copy(deafened = value) }
            else failAndStop(tag, VoiceEngineError.MUTE)
        }
    } }

    private fun failAndStop(tag: VoiceSessionTag, error: VoiceEngineError) {
        scope.launch {
            if (activeTag != tag || mutable.value.phase == VoiceDiagnosticPhase.STOPPING) return@launch
            val stopped = stop()
            if (stopped && activeTag == null) mutable.update { it.copy(phase = VoiceDiagnosticPhase.ERROR, error = error) }
        }
    }

    suspend fun stop(): Boolean = stopMutex.withLock {
        val tag = activeTag ?: return@withLock true
        mutable.update { it.copy(phase = VoiceDiagnosticPhase.STOPPING) }
        // Await even a cancellation-resistant token request before leave, so a late join
        // cannot run after our server cleanup. No replacement can start during this wait.
        startJob?.cancelAndJoin()
        startJob = null
        eventJob?.cancelAndJoin()
        eventJob = null
        if (driver?.stop(tag.generation)?.isFailure == true) {
            mutable.update { it.copy(phase = VoiceDiagnosticPhase.CLEANUP_FAILED, error = VoiceEngineError.CLEANUP) }
            return@withLock false
        }
        driver = null
        try { activeGroup?.let { port.leave(it) }; activeGroup = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.update { it.copy(phase = VoiceDiagnosticPhase.CLEANUP_FAILED, error = VoiceEngineError.CLEANUP) }
            return@withLock false
        }
        activeTag = null
        mutable.update { it.copy(phase = VoiceDiagnosticPhase.IDLE, error = null) }
        true
    }
}
