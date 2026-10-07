package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.VoiceDiagnosticSnapshot
import ru.sodovaya.volty.domain.voice.VoiceSessionEvent
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

/** Caller serializes native callbacks, reset, observation and event enqueue. */
internal class VoiceNativeAudioEvidence(private val tag: VoiceSessionTag) {
    private val capture = StreamEvidence()
    private val render = StreamEvidence()
    private var closed = false

    fun reconnecting(snapshot: VoiceDiagnosticSnapshot) {
        if (!owns(snapshot)) return
        capture.reset(snapshot.captureBytes)
        render.reset(snapshot.renderBytes)
    }

    fun captureState(started: Boolean, snapshot: VoiceDiagnosticSnapshot): Boolean {
        if (!owns(snapshot)) return false
        capture.actualState(started, snapshot.captureBytes)
        return true
    }

    fun renderState(started: Boolean, snapshot: VoiceDiagnosticSnapshot): Boolean {
        if (!owns(snapshot)) return false
        render.actualState(started, snapshot.renderBytes)
        return true
    }

    fun observe(snapshot: VoiceDiagnosticSnapshot): List<VoiceSessionEvent> {
        if (!owns(snapshot)) return emptyList()
        return buildList {
            when (capture.observe(snapshot.captureRunning, snapshot.captureBytes)) {
                true -> add(VoiceSessionEvent.CaptureStarted(tag))
                false -> add(VoiceSessionEvent.CaptureStopped(tag))
                null -> Unit
            }
            when (render.observe(snapshot.renderRunning, snapshot.renderBytes)) {
                true -> add(VoiceSessionEvent.RenderStarted(tag))
                false -> add(VoiceSessionEvent.RenderStopped(tag))
                null -> Unit
            }
        }
    }

    fun close() { closed = true }
    private fun owns(snapshot: VoiceDiagnosticSnapshot) = !closed && snapshot.generation == tag.generation

    private class StreamEvidence {
        private var highWater = 0L
        private var reportedRunning = false
        fun reset(bytes: Long) { consume(bytes); reportedRunning = false }
        fun actualState(started: Boolean, bytes: Long) { consume(bytes); reportedRunning = started }
        fun observe(running: Boolean?, bytes: Long): Boolean? {
            val fresh = bytes >= 0 && bytes > highWater
            // Progress while false/unknown is consumed too. It cannot be reused
            // to acknowledge a later running flag without fresh native samples.
            consume(bytes)
            return when {
                running == false && reportedRunning -> { reportedRunning = false; false }
                running == true && !reportedRunning && fresh -> { reportedRunning = true; true }
                else -> null
            }
        }
        private fun consume(bytes: Long) { if (bytes >= 0) highWater = maxOf(highWater, bytes) }
    }
}

internal data class VoiceRemoteAudioObservation(
    val peer: String,
    val audioPublication: Boolean,
    val subscribed: Boolean,
    val hasAudioTrack: Boolean,
)

/** Caller supplies the current owned SDK map, never a late callback's payload as state. */
internal class VoiceRemoteAudioEvidence(private val tag: VoiceSessionTag) {
    private var peers = emptySet<String>()
    private var closed = false
    fun reconnecting() { if (!closed) peers = emptySet() }
    fun reconcile(observations: List<VoiceRemoteAudioObservation>): List<VoiceSessionEvent> {
        if (closed) return emptyList()
        val current = observations.asSequence().filter {
            it.peer.isNotBlank() && it.audioPublication && it.subscribed && it.hasAudioTrack
        }.map { it.peer }.toSet()
        val result = buildList {
            for (peer in (peers - current).sorted()) add(VoiceSessionEvent.Unsubscribed(tag, peer))
            for (peer in (current - peers).sorted()) add(VoiceSessionEvent.Subscribed(tag, peer))
        }
        peers = current
        return result
    }
    fun close() { closed = true; peers = emptySet() }
}
