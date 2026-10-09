package ru.sodovaya.volty.data.voice

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.org.webrtc.RTCStatsReport
import ru.sodovaya.volty.domain.voice.*

class AndroidVoiceSdkSessionFactory(
    context: Context,
    private val preferences: VoiceAudioPreferences,
    private val diagnostics: VoiceDiagnostics,
) : VoiceSdkSessionFactory {
    private val application = context.applicationContext
    override fun create(tag: VoiceSessionTag, emit: (VoiceSessionEvent) -> Unit): VoiceSdkSession =
        AndroidVoiceSdkSession(application, tag, preferences, diagnostics, emit)
}

/** One allocation per tag. SDK alone owns reconnect; no app-level redial loop. */
internal class AndroidVoiceSdkSession(
    private val context: Context,
    private val tag: VoiceSessionTag,
    private val preferences: VoiceAudioPreferences,
    private val diagnostics: VoiceDiagnostics,
    private val emit: (VoiceSessionEvent) -> Unit,
) : VoiceSdkSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val closeMutex = Mutex()
    private val resources = Mutex()
    private var room: Room? = null
    private var audio: OwnedVoiceAudio? = null
    private var routes: VoiceAudioRouteMonitor? = null
    private var keeper: VoiceCommunicationKeeper? = null
    private var localTrack: LocalAudioTrack? = null
    @Volatile private var closing = false
    @Volatile private var deafened = false
    @Volatile private var outputAvailable = preferences.output == VoiceOutputPreference.AUTO
    private val remoteEvidence = VoiceRemoteAudioEvidence(tag)
    private val publicationRecovery = VoicePublicationRecovery(scope, object : VoicePublicationRecoveryPort {
        override suspend fun restoreClosed(): Boolean = withContext(Dispatchers.Main.immediate) {
            resources.withLock {
                if (closing) return@withLock false
                val ownedRoom = room ?: return@withLock false
                val track = localTrack ?: return@withLock false
                val ownedAudio = audio ?: return@withLock false
                // SDK full reconnect skips its saved muted publication. Reuse
                // the same track, always closed; the driver alone applies intent.
                ownedAudio.module.setMicrophoneMute(true)
                track.enabled = false
                val existing = ownedPublication(ownedRoom)
                if (existing != null) {
                    existing.muted = true
                    return@withLock true
                }
                val restored = ownedRoom.localParticipant.publishAudioTrack(track)
                currentCoroutineContext().ensureActive()
                if (closing || !restored) return@withLock false
                val publication = ownedPublication(ownedRoom) ?: return@withLock false
                publication.muted = true
                true
            }
        }
        override fun hasOwnedPublication(): Boolean = !closing && ownedPublication() != null
    }, published = {
        record(VoiceDiagnosticCode.PUBLISHED)
        event(VoiceSessionEvent.Published(tag))
    }, failed = { event(VoiceSessionEvent.EngineFailed(tag)) })

    private fun ownedPublication(ownedRoom: Room? = room): LocalTrackPublication? {
        val track = localTrack ?: return null
        return ownedRoom?.localParticipant?.trackPublications?.values
            ?.filterIsInstance<LocalTrackPublication>()?.firstOrNull { it.track === track }
    }

    override suspend fun connect(credentials: VoiceConnectionCredentials) {
        val ownedRoom = resources.withLock { allocateRoom() }
        record(VoiceDiagnosticCode.CONNECTING)
        ownedRoom.connect(credentials.serverUrl, credentials.token)
        currentCoroutineContext().ensureActive()
        check(!closing)
        scope.launch { pollStats(ownedRoom) }
    }

    private fun allocateRoom(): Room {
        check(!closing && room == null)
        diagnostics.update { it.copy(preferences = preferences) }
        LiveKit.init(context)
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ownedKeeper = VoiceCommunicationKeeper(diagnostics).also { keeper = it }
        val ownedAudio = OwnedVoiceAudio(context, tag, diagnostics, ::event, ownedKeeper).also { audio = it }
        ownedAudio.module.setMicrophoneMute(true) // Initial intent installed before any native capture.
        ownedAudio.module.setSpeakerMute(deafened || !outputAvailable)
        ownedAudio.applyInput(manager, preferences.input)
        val ownedRoutes = VoiceAudioRouteMonitor(context, manager, preferences, diagnostics, ownedKeeper, { ownedAudio.preferredInputId },
            outputAllowed = { allowed ->
                outputAvailable = allowed
                if (!closing) ownedAudio.module.setSpeakerMute(deafened || !allowed)
            }, inputLost = {
                if (!closing) {
                    ownedAudio.module.setMicrophoneMute(true)
                    record(VoiceDiagnosticCode.INPUT_UNAVAILABLE)
                    event(VoiceSessionEvent.EngineFailed(tag))
                }
            }).also { routes = it }
        val ownedRoom = LiveKit.create(context, overrides = LiveKitOverrides(audioOptions = AudioOptions(
            audioDeviceModule = ownedAudio.module, audioHandler = ownedRoutes,
            disableAudioPrewarming = true, disableCommunicationModeWorkaround = true,
        ))).also { room = it }
        scope.launch { ownedRoom.events.collect { onRoomEvent(it, ownedRoom) } }
        return ownedRoom
    }

    override suspend fun publishMicrophone(muted: Boolean): Boolean {
        val (ownedRoom, track) = resources.withLock {
            check(!closing)
            val ownedRoom = checkNotNull(room)
            checkNotNull(audio).module.setMicrophoneMute(muted)
            val track = ownedRoom.localParticipant.createAudioTrack().also { localTrack = it }
            track.enabled = !muted
            ownedRoom to track
        }
        val published = ownedRoom.localParticipant.publishAudioTrack(track)
        currentCoroutineContext().ensureActive()
        check(!closing)
        if (published) {
            val publication = ownedRoom.localParticipant.trackPublications.values
                .filterIsInstance<LocalTrackPublication>().firstOrNull { it.track === track } ?: return false
            publication.muted = muted
            record(VoiceDiagnosticCode.PUBLISHED); event(VoiceSessionEvent.Published(tag))
        }
        return published
    }

    override suspend fun setMuted(muted: Boolean): Boolean =
        setMutedIfCurrent(muted) { true } == VoiceMuteApplyResult.APPLIED

    override suspend fun setMutedIfCurrent(muted: Boolean, current: () -> Boolean): VoiceMuteApplyResult = withContext(Dispatchers.Main.immediate) {
        resources.withLock {
            if (closing) return@withLock VoiceMuteApplyResult.SUPERSEDED
            val publication = ownedPublication() ?: return@withLock VoiceMuteApplyResult.REJECTED
            currentCoroutineContext().ensureActive()
            // Main dispatch and closed-republish resource locking may suspend.
            // Recheck intent/lease after both, with no await before native writes.
            if (!current()) return@withLock VoiceMuteApplyResult.SUPERSEDED
            checkNotNull(audio).module.setMicrophoneMute(muted)
            publication.muted = muted
            VoiceMuteApplyResult.APPLIED
        }
    }

    override fun setDeafened(deafened: Boolean) {
        this.deafened = deafened
        if (!closing) audio?.module?.setSpeakerMute(deafened || !outputAvailable)
    }

    override suspend fun configureAudio(preferences: VoiceAudioPreferences): VoiceAudioChangeResult = resources.withLock {
        currentCoroutineContext().ensureActive()
        if (closing) return@withLock VoiceAudioChangeResult.STALE
        val ownedAudio = audio ?: return@withLock VoiceAudioChangeResult.UNAVAILABLE
        val ownedRoutes = routes ?: return@withLock VoiceAudioChangeResult.UNAVAILABLE
        if (preferences.input == VoiceInputPreference.AUTO && ownedAudio.preferredInputId != null)
            return@withLock VoiceAudioChangeResult.REQUIRES_RECONNECT
        val request = currentCoroutineContext()[Job]
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = ownedRoutes.configureOutputAndInput(preferences.output,
            requestInput = { ownedAudio.requestInput(manager, preferences.input) },
            currentRequest = { !closing && request?.isActive == true })
        currentCoroutineContext().ensureActive()
        if (closing) VoiceAudioChangeResult.STALE else {
            if (result == VoiceAudioChangeResult.REQUESTED) diagnostics.update { it.copy(preferences = preferences) }
            result
        }
    }

    override suspend fun close() = closeMutex.withLock {
        closing = true
        remoteEvidence.close()
        publicationRecovery.close()
        scope.coroutineContext.cancelChildren()
        // Each reference clears only after successful release, preserving failed cleanup for retry.
        resources.withLock {
        var failed = false
        try { room?.release(); room = null } catch (_: Exception) { failed = true }
        try { localTrack?.dispose(); localTrack = null } catch (_: Exception) { failed = true }
        try { routes?.close(); routes = null } catch (_: Exception) { failed = true }
        try { keeper?.close(); keeper = null } catch (_: Exception) { failed = true }
        try { audio?.close(); audio = null } catch (_: Exception) { failed = true }
        if (failed) throw VoiceEngineException(VoiceEngineError.CLEANUP)
        scope.cancel()
        record(VoiceDiagnosticCode.CLOSED)
        }
    }

    private suspend fun onRoomEvent(event: RoomEvent, ownedRoom: Room) {
        if (closing) return
        when (event) {
            is RoomEvent.Connected -> {
                publicationRecovery.connected()
                record(VoiceDiagnosticCode.CONNECTED); event(VoiceSessionEvent.TransportConnected(tag))
            }
            is RoomEvent.Reconnecting -> {
                // Close native capture across the gap without changing SDK's
                // saved publication.muted, which selects its republish path.
                audio?.module?.setMicrophoneMute(true)
                publicationRecovery.reconnecting()
                remoteEvidence.reconnecting()
                val announce = {
                    record(VoiceDiagnosticCode.RECONNECTING)
                    event(VoiceSessionEvent.Reconnecting(tag))
                }
                audio?.reconnecting(announce) ?: announce()
            }
            is RoomEvent.Reconnected -> {
                record(VoiceDiagnosticCode.RECONNECTED)
                event(VoiceSessionEvent.TransportConnected(tag))
                publicationRecovery.connected()
                // A soft resume may keep the publication and emit no TrackPublished.
                // Inspect the actual owned publication; transport alone is not evidence.
                if (ownedPublication(ownedRoom) != null && publicationRecovery.sdkPublished()) {
                    record(VoiceDiagnosticCode.PUBLISHED)
                    this.event(VoiceSessionEvent.Published(tag))
                }
                reconcileRemoteAudio(ownedRoom)
            }
            is RoomEvent.Disconnected -> {
                record(VoiceDiagnosticCode.DISCONNECTED)
                this.event(voiceDisconnectionEvent(tag, event.reason))
            }
            is RoomEvent.FailedToConnect -> event(VoiceSessionEvent.EngineFailed(tag))
            is RoomEvent.TrackPublished -> if (event.participant == ownedRoom.localParticipant &&
                event.publication.kind == Track.Kind.AUDIO && event.publication.track === localTrack &&
                ownedPublication(ownedRoom) === event.publication && publicationRecovery.sdkPublished()) {
                record(VoiceDiagnosticCode.PUBLISHED)
                this.event(VoiceSessionEvent.Published(tag))
            }
            is RoomEvent.TrackUnpublished -> if (event.participant == ownedRoom.localParticipant &&
                event.publication.kind == Track.Kind.AUDIO && ownedPublication(ownedRoom) == null) {
                audio?.module?.setMicrophoneMute(true)
                this.event(VoiceSessionEvent.Unpublished(tag))
                publicationRecovery.unpublished(event.publication.muted)
            }
            is RoomEvent.TrackSubscribed -> if (event.track.kind == Track.Kind.AUDIO) {
                reconcileRemoteAudio(ownedRoom)
            }
            is RoomEvent.TrackUnsubscribed -> if (event.track.kind == Track.Kind.AUDIO) {
                reconcileRemoteAudio(ownedRoom)
            }
            else -> Unit
        }
    }

    private fun reconcileRemoteAudio(ownedRoom: Room) {
        if (closing) return
        val observations = ownedRoom.remoteParticipants.values.flatMap { participant ->
            participant.trackPublications.values.map { publication ->
                VoiceRemoteAudioObservation(participant.sid.toString(),
                    audioPublication = publication.kind == Track.Kind.AUDIO,
                    subscribed = publication.subscribed,
                    hasAudioTrack = publication.track?.kind == Track.Kind.AUDIO)
            }
        }
        for (observation in remoteEvidence.reconcile(observations)) {
            record(if (observation is VoiceSessionEvent.Subscribed) VoiceDiagnosticCode.SUBSCRIBED else VoiceDiagnosticCode.UNSUBSCRIBED)
            event(observation)
        }
    }

    private suspend fun pollStats(ownedRoom: Room) {
        while (currentCoroutineContext().isActive) {
            delay(1000)
            // Actual ADM samples/current SDK tracks remain evidence even if
            // an unrelated RTC statistics request fails. No new polling loop.
            audio?.refreshMediaEvidence()
            reconcileRemoteAudio(ownedRoom)
            try {
                // Earlier draft assumed whole-PC reports; RTCEngine actually calls
                // getStats(sender/receiver). Read each receiver and deduplicate internal IDs.
                val outbound = localTrack?.getRTCStats().audioPackets("outbound-rtp", "packetsSent")
                val remote = ownedRoom.remoteParticipants.values.asSequence()
                    .flatMap { it.trackPublications.values.asSequence() }
                    .mapNotNull { it.track }.filter { it.kind == Track.Kind.AUDIO }.toList()
                val reports = remote.mapNotNull { it.getRTCStats() }
                val inboundRows = reports.flatMap { it.statsMap.values }.distinctBy { it.id }
                    .filter { it.type == "inbound-rtp" && (it.members["kind"] == "audio" || it.members["mediaType"] == "audio") }
                val inbound = if (reports.size == remote.size && reports.all { it.audioPackets("inbound-rtp", "packetsReceived") != null })
                    sumCompleteVoiceCounters(inboundRows.map { (it.members["packetsReceived"] as? Number)?.toLong() }) else null
                diagnostics.update { it.copy(outboundPackets = outbound, inboundPackets = inbound) }
                record(VoiceDiagnosticCode.STATS)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Missing stats remains unknown, never invent zero. */
                diagnostics.update { it.copy(outboundPackets = null, inboundPackets = null) }
            }
        }
    }

    private fun RTCStatsReport?.audioPackets(type: String, field: String): Long? {
        val counters = this?.statsMap?.values?.filter { it.type == type &&
            (it.members["kind"] == "audio" || it.members["mediaType"] == "audio") }
            ?.map { (it.members[field] as? Number)?.toLong() }.orEmpty()
        return sumCompleteVoiceCounters(counters)
    }
    private fun event(event: VoiceSessionEvent) { if (!closing) emit(event) }
    private fun record(code: VoiceDiagnosticCode) = diagnostics.record(SystemClock.elapsedRealtime(), code)
}
