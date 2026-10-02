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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.sodovaya.volty.domain.voice.VoiceGeneration
import ru.sodovaya.volty.domain.voice.VoiceSessionEffect
import ru.sodovaya.volty.domain.voice.VoiceSessionEvent
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

class VoiceEngineDriver(private val scope: CoroutineScope, private val factory: VoiceSdkSessionFactory) {
    private val mutex = Mutex()
    private var command: Command? = null
    private var current: Handle? = null
    private val retired = mutableSetOf<Handle>()
    private val output = MutableSharedFlow<VoiceSessionEvent>(extraBufferCapacity = 64)
    val events = output.asSharedFlow()
    suspend fun start(effect: VoiceSessionEffect.StartEngine, credentials: VoiceConnectionCredentials): Result<Unit> = coroutineScope {
        val requested = Command(effect.tag, currentCoroutineContext()[Job]!!)
        val previous = mutex.withLock {
            val old = command
            current?.let { retired += it }
            current = null
            command = requested
            old
        }
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
                        mutex.withLock {
                            if (command === requested && current?.command === requested && isSdkEvent(event, requested.tag)) {
                                output.tryEmit(event)
                            }
                        }
                    }
                }).also { current = it }
            }
            handle.session.setDeafened(effect.deafened)
            handle.session.connect(credentials)
            assertOwned(handle)
            if (!handle.session.publishMicrophone(effect.muted)) throw VoiceEngineException(VoiceEngineError.PUBLICATION)
            assertOwned(handle)
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
            output.tryEmit(VoiceSessionEvent.Failed(effect.tag.generation))
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
        if (pending != null || handles.isNotEmpty()) output.tryEmit(VoiceSessionEvent.Stopped(generation))
        return Result.success(Unit)
    }

    suspend fun setMuted(tag: VoiceSessionTag, muted: Boolean): Result<Unit> = control(tag, VoiceEngineError.MUTE) {
        if (!it.setMuted(muted)) throw VoiceEngineException(VoiceEngineError.MUTE)
    }

    suspend fun setDeafened(tag: VoiceSessionTag, deafened: Boolean): Result<Unit> = control(tag, VoiceEngineError.MUTE) {
        it.setDeafened(deafened)
    }

    private suspend fun control(tag: VoiceSessionTag, code: VoiceEngineError, action: suspend (VoiceSdkSession) -> Unit): Result<Unit> {
        val handle = mutex.withLock { current?.takeIf { it.command.tag == tag } }
            ?: return Result.failure(VoiceEngineException(code))
        return try {
            action(handle.session)
            assertOwned(handle)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(VoiceEngineException(code))
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
    }

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

    private class Command(val tag: VoiceSessionTag, val job: Job)
    private class Handle(val command: Command, val session: VoiceSdkSession) {
        val closeMutex = Mutex()
        var closed = false
    }

    private companion object { const val CLOSE_TIMEOUT_MILLIS = 5_000L }

    private fun isSdkEvent(event: VoiceSessionEvent, tag: VoiceSessionTag): Boolean = when (event) {
        // SFU media cannot acknowledge a backend lease or prove resource release.
        is VoiceSessionEvent.LeaseAck -> false
        is VoiceSessionEvent.Tagged -> event.tag == tag
        is VoiceSessionEvent.Failed -> event.generation == tag.generation
        else -> false
    }
}
