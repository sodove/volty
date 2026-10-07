package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.sodovaya.volty.domain.voice.VoiceAudioPreferences
import ru.sodovaya.volty.domain.voice.VoiceGeneration
import ru.sodovaya.volty.domain.voice.VoiceSessionEffect
import ru.sodovaya.volty.domain.voice.VoiceSessionEvent
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

class VoiceEngineDriver(
    private val scope: CoroutineScope,
    private val factory: VoiceSdkSessionFactory,
    /** Required nonblocking current intent/lease predicate; no permit-all default. */
    private val authorized: (VoiceSessionTag) -> Boolean,
) {
    private val mutex = Mutex()
    private var command: Command? = null
    private var current: Handle? = null
    private val retired = mutableSetOf<Handle>()
    private val output = MutableSharedFlow<VoiceSessionEvent>(extraBufferCapacity = 64)
    val events = output.asSharedFlow()
    suspend fun start(effect: VoiceSessionEffect.StartEngine, credentials: VoiceConnectionCredentials): Result<Unit> = coroutineScope {
        val requested = Command(effect.tag, currentCoroutineContext()[Job]!!, effect.muted, effect.deafened)
        val registration = mutex.withLock {
            if (!isAuthorized(effect.tag)) return@withLock false to null
            val old = command
            current?.let { retired += it }
            current = null
            command = requested
            true to old
        }
        if (!registration.first) {
            output.tryEmit(VoiceSessionEvent.EngineFailed(effect.tag))
            return@coroutineScope Result.failure(VoiceEngineException(VoiceEngineError.AUTHORIZATION))
        }
        val previous = registration.second
        previous?.job?.cancel()
        var handle: Handle? = null
        try {
            // Do not allocate a second audio stack until previous owned resources are released.
            for (old in mutex.withLock { retired.toList() }) {
                release(old).getOrThrow()
            }
            currentCoroutineContext().ensureActive()
            handle = mutex.withLock {
                checkCommand(requested)
                Handle(requested, factory.create(effect.tag) { event ->
                    scope.launch {
                        val restored = mutex.withLock {
                            if (command === requested && current?.command === requested && isAuthorized(requested.tag) && isSdkEvent(event, requested.tag)) {
                                val owned = checkNotNull(current)
                                val reconcile = when (event) {
                                    is VoiceSessionEvent.Reconnecting, is VoiceSessionEvent.Unpublished -> {
                                        owned.publicationReady = false
                                        owned.pendingInitialPublished = false
                                        owned.publicationVersion++
                                        false
                                    }
                                    is VoiceSessionEvent.Published -> {
                                        val restored = owned.initialPublicationComplete && !owned.publicationReady
                                        if (owned.initialPublicationComplete) owned.publicationReady = true
                                        else owned.pendingInitialPublished = true
                                        restored
                                    }
                                    else -> false
                                }
                                // SDK may announce the track before publishMicrophone returns.
                                // Buffer that evidence until the initial publication succeeds.
                                if (event !is VoiceSessionEvent.Published || owned.initialPublicationComplete)
                                    output.tryEmit(if (event is VoiceSessionEvent.Failed) VoiceSessionEvent.EngineFailed(requested.tag) else event)
                                owned.takeIf { reconcile }
                            } else null
                        }
                        if (restored != null && applyMute(restored).isFailure) {
                            mutex.withLock {
                                if (command === requested && current === restored && isAuthorized(requested.tag))
                                    output.tryEmit(VoiceSessionEvent.EngineFailed(requested.tag))
                            }
                        }
                    }
                }).also { current = it }
            }
            mutex.withLock {
                checkCommand(requested)
                handle.session.setDeafened(requested.deafened)
            }
            assertOwned(handle)
            handle.session.connect(credentials)
            assertOwned(handle)
            // Earlier publication used effect.muted captured before connect.
            // That overwrote mute requested while no publication existed yet.
            // Publish closed first, then reconcile the owned latest intent.
            val publicationVersion = mutex.withLock { checkCommand(requested); handle.publicationVersion }
            if (!handle.session.publishMicrophone(true)) throw VoiceEngineException(VoiceEngineError.PUBLICATION)
            assertOwned(handle)
            mutex.withLock {
                checkCommand(requested)
                handle.initialPublicationComplete = true
                handle.publicationReady = handle.publicationVersion == publicationVersion || handle.pendingInitialPublished
                if (handle.pendingInitialPublished) output.tryEmit(VoiceSessionEvent.Published(requested.tag))
                handle.pendingInitialPublished = false
            }
            applyMute(handle).getOrThrow()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            detach(requested, handle)
            handle?.let { release(it) }
            throw cancelled
        } catch (error: Exception) {
            detach(requested, handle)
            val cleanup = handle?.let { release(it) }
            val code = if (cleanup?.isFailure == true) VoiceEngineError.CLEANUP
                else (error as? VoiceEngineException)?.code ?: VoiceEngineError.CONNECTION
            output.tryEmit(VoiceSessionEvent.EngineFailed(effect.tag))
            Result.failure(VoiceEngineException(code))
        }
    }

    suspend fun stop(generation: VoiceGeneration): Result<Unit> {
        val (pending, handles) = mutex.withLock {
            val pending = command?.takeIf { it.tag.generation == generation }
            if (pending != null) {
                command = null
                current?.let { retired += it }
                current = null
            }
            pending to retired.filter { it.command.tag.generation == generation }
        }
        pending?.job?.cancel()
        for (handle in handles) {
            val result = release(handle)
            if (result.isFailure) return result
        }
        // No handle allocated is also a confirmed local release. An earlier
        // revision emitted nothing here, leaving preallocation stop pending.
        output.tryEmit(VoiceSessionEvent.Stopped(generation))
        return Result.success(Unit)
    }

    suspend fun setMuted(tag: VoiceSessionTag, muted: Boolean): Result<Unit> {
        val (accepted, handle) = mutex.withLock {
            val owned = command?.takeIf { it.tag == tag && isAuthorized(tag) } ?: return@withLock false to null
            check(owned.muteVersion < Long.MAX_VALUE)
            owned.muted = muted
            owned.muteVersion++
            true to current?.takeIf { it.command === owned && it.publicationReady }
        }
        if (!accepted) return Result.failure(VoiceEngineException(VoiceEngineError.MUTE))
        // Intent is accepted even before factory allocation/publication. Start
        // will sample it after initially muted publication becomes available.
        return if (handle == null) Result.success(Unit) else applyMute(handle)
    }

    private suspend fun applyMute(handle: Handle): Result<Unit> = try {
        val completed = withTimeoutOrNull(CONTROL_TIMEOUT_MILLIS) {
            handle.controlMutex.withLock control@{
                do {
                    val intent = mutex.withLock {
                        checkCommand(handle.command)
                        if (current !== handle) throw CancellationException("Voice session superseded")
                        // Reconnect can remove the publication while this control waits.
                        // Preserve intent; only an owned publication can reopen capture.
                        if (!handle.publicationReady) return@withLock null
                        Triple(handle.command.muted, handle.command.muteVersion, handle.publicationVersion)
                    }
                    if (intent == null) return@control
                    val applied = handle.session.setMuted(intent.first)
                    assertOwned(handle)
                    val settled = mutex.withLock {
                        if (!handle.publicationReady || handle.publicationVersion != intent.third) {
                            // A delivered removal supersedes this result, including rejection.
                            true
                        } else {
                            if (!applied) throw VoiceEngineException(VoiceEngineError.MUTE)
                            handle.command.muteVersion == intent.second
                        }
                    }
                } while (!settled)
            }
            true
        }
        if (completed == true) Result.success(Unit) else Result.failure(VoiceEngineException(VoiceEngineError.MUTE))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.failure(VoiceEngineException(VoiceEngineError.MUTE))
    }

    /** Latest exact-handle pair request. Rejection never changes media admission or cleanup. */
    suspend fun configureAudio(tag: VoiceSessionTag, preferences: VoiceAudioPreferences): VoiceAudioChangeResult {
        val request = mutex.withLock {
            val handle = current?.takeIf {
                command === it.command && it.command.tag == tag && it.publicationReady && isAuthorized(tag)
            } ?: return VoiceAudioChangeResult.STALE
            AudioChange(handle, Any()).also { handle.audioRequest = it.nonce }
        }
        return try {
            withTimeoutOrNull(CONTROL_TIMEOUT_MILLIS) {
                request.handle.controlMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!mutex.withLock { ownsAudioChange(request) }) return@withLock VoiceAudioChangeResult.STALE
                    val result = request.handle.session.configureAudio(preferences)
                    currentCoroutineContext().ensureActive()
                    mutex.withLock { if (ownsAudioChange(request)) result else VoiceAudioChangeResult.STALE }
                }
            } ?: VoiceAudioChangeResult.FAILED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutex.withLock { if (ownsAudioChange(request)) VoiceAudioChangeResult.FAILED else VoiceAudioChangeResult.STALE }
        }
    }

    /** Called under ownership mutex; no stale command can even supersede current audio work. */
    private fun ownsAudioChange(request: AudioChange): Boolean = current === request.handle &&
        command === request.handle.command && request.handle.audioRequest === request.nonce &&
        request.handle.publicationReady && isAuthorized(request.handle.command.tag)

    suspend fun setDeafened(tag: VoiceSessionTag, deafened: Boolean): Result<Unit> {
        return try {
            val accepted = mutex.withLock {
                val owned = command?.takeIf { it.tag == tag && isAuthorized(tag) } ?: return@withLock false
                owned.deafened = deafened
                // Synchronous native speaker-mute setter, no suspended SDK work
                // under ownership lock. Allocation samples this same intent.
                current?.takeIf { it.command === owned }?.session?.setDeafened(deafened)
                true
            }
            if (accepted) Result.success(Unit) else Result.failure(VoiceEngineException(VoiceEngineError.MUTE))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(VoiceEngineException(VoiceEngineError.MUTE))
        }
    }

    private suspend fun assertOwned(handle: Handle) {
        currentCoroutineContext().ensureActive()
        mutex.withLock {
            checkCommand(handle.command)
            if (current !== handle) throw CancellationException("Voice session superseded")
        }
    }

    private fun checkCommand(requested: Command) {
        if (command !== requested) throw CancellationException("Voice operation superseded")
        if (!isAuthorized(requested.tag)) throw VoiceEngineException(VoiceEngineError.AUTHORIZATION)
    }

    private fun isAuthorized(tag: VoiceSessionTag): Boolean = try { authorized(tag) } catch (_: Exception) { false }

    private suspend fun detach(requested: Command, handle: Handle?) = withContext(NonCancellable) {
        mutex.withLock {
            if (command === requested) command = null
            if (current === handle) current = null
            handle?.let { retired += it }
        }
    }

    private suspend fun release(handle: Handle): Result<Unit> = withContext(NonCancellable) {
        handle.closeMutex.withLock {
            if (!handle.closed) {
                try {
                    withTimeout(CLOSE_TIMEOUT_MILLIS) { handle.session.close() }
                    handle.closed = true
                } catch (_: Exception) {
                    return@withLock Result.failure(VoiceEngineException(VoiceEngineError.CLEANUP))
                }
            }
            mutex.withLock { retired -= handle }
            Result.success(Unit)
        }
    }

    private class Command(val tag: VoiceSessionTag, val job: Job, var muted: Boolean, var deafened: Boolean) {
        var muteVersion = 0L
    }
    private class AudioChange(val handle: Handle, val nonce: Any)
    private class Handle(val command: Command, val session: VoiceSdkSession) {
        var audioRequest: Any? = null
        val closeMutex = Mutex()
        val controlMutex = Mutex()
        var initialPublicationComplete = false
        var pendingInitialPublished = false
        var publicationReady = false
        var publicationVersion = 0L
        var closed = false
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
        const val CONTROL_TIMEOUT_MILLIS = 5_000L
    }

    private fun isSdkEvent(event: VoiceSessionEvent, tag: VoiceSessionTag): Boolean = when (event) {
        // SFU media cannot acknowledge a backend lease or prove resource release.
        is VoiceSessionEvent.LeaseAck -> false
        is VoiceSessionEvent.Tagged -> event.tag == tag
        is VoiceSessionEvent.Failed -> event.generation == tag.generation
        else -> false
    }
}
