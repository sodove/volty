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
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import ru.sodovaya.volty.domain.audio.MicProcessingSettings
import ru.sodovaya.volty.domain.audio.VoiceListenSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.org.webrtc.RTCStatsReport
import ru.sodovaya.volty.domain.voice.*

class AndroidVoiceSdkSessionFactory(
    context: Context,
    private val preferences: VoiceAudioPreferences,
    private val listen: StateFlow<VoiceListenSettings>,
    private val mic: StateFlow<MicProcessingSettings>,
    private val diagnostics: VoiceDiagnostics,
    private val actorFor: (identity: String) -> String? = { null },
) : VoiceSdkSessionFactory {
    private val application = context.applicationContext
    override fun create(tag: VoiceSessionTag, emit: (VoiceSessionEvent) -> Unit): VoiceSdkSession {
        return AndroidVoiceSdkSession(application, tag, preferences, listen, mic, diagnostics, emit, actorFor)
    }
}

/** One allocation per tag. SDK alone owns reconnect; no app-level redial loop. */
internal class AndroidVoiceSdkSession(
    private val context: Context,
    private val tag: VoiceSessionTag,
    private val preferences: VoiceAudioPreferences,
    private val listen: StateFlow<VoiceListenSettings>,
    private val mic: StateFlow<MicProcessingSettings>,
    private val diagnostics: VoiceDiagnostics,
    private val emit: (VoiceSessionEvent) -> Unit,
    private val actorFor: (identity: String) -> String? = { null },
) : VoiceSdkSession {
    init { AndroidVoiceDiagnosticStore.attach(context, diagnostics) }
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
    @Volatile private var routingTransition = false
    private val duckGate = MusicDuckGate()
    private var ducking = false
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
                ownedAudio.muteMicrophone(true)
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
        try { ownedRoom.connect(credentials.serverUrl, credentials.token) }
        catch (cancelled: CancellationException) { record(VoiceDiagnosticCode.CONNECT_CANCELLED); throw cancelled }
        catch (error: Exception) { record(VoiceDiagnosticCode.CONNECT_FAILED); throw error }
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
        val ownedAudio = OwnedVoiceAudio(context, tag, diagnostics, ::event, ownedKeeper, mic.value).also { audio = it }
        check(ownedAudio.configurePlayback(preferences) == VoiceAudioChangeResult.REQUESTED)
        ownedAudio.setMicProcessing(mic.value)
        ownedAudio.muteMicrophone(true) // Initial intent installed before any native capture.
        ownedAudio.mutePlayback(deafened || !outputAvailable)
        ownedAudio.applyInput(manager, preferences.input)
        val ownedRoutes = VoiceAudioRouteMonitor(context, manager, preferences, diagnostics, ownedKeeper, { ownedAudio.preferredInputId },
            outputAllowed = { allowed ->
                outputAvailable = allowed
                if (!closing) ownedAudio.mutePlayback(deafened || !allowed || routingTransition)
            }, inputLost = {
                if (!closing) {
                    ownedAudio.muteMicrophone(true)
                    record(VoiceDiagnosticCode.INPUT_UNAVAILABLE)
                    event(VoiceSessionEvent.EngineFailed(tag))
                }
            }).also { routes = it }
        val ownedRoom = LiveKit.create(context, overrides = LiveKitOverrides(audioOptions = AudioOptions(
            audioDeviceModule = ownedAudio.module, audioHandler = ownedRoutes,
            disableAudioPrewarming = true, disableCommunicationModeWorkaround = true,
        ))).also { room = it }
        scope.launch { ownedRoom.events.collect { onRoomEvent(it, ownedRoom) } }
        // Gains follow the settings at once; reconcileRemoteAudio re-applies them on every
        // (re)subscription and on the one-second poll, so a reconnect never resets a volume.
        scope.launch { listen.collect { room?.let(::reconcileRemoteAudio) } }
        // A new stage per change, built here (main) and swapped in by reference; AGC is a track
        // option and only applies on the next publication.
        scope.launch { mic.collect { audio?.setMicProcessing(it) } }
        scope.launch {
            while (isActive) {
                delay(DUCK_TICK_MILLIS)
                applyDuck(duckGate.onTick(SystemClock.elapsedRealtime()))
            }
        }
        return ownedRoom
    }

    override suspend fun publishMicrophone(muted: Boolean): Boolean {
        val (ownedRoom, track) = resources.withLock {
            check(!closing)
            val ownedRoom = checkNotNull(room)
            checkNotNull(audio).muteMicrophone(muted)
            val track = ownedRoom.localParticipant.createAudioTrack(
                options = LocalAudioTrackOptions(autoGainControl = mic.value.autoGain),
            ).also { localTrack = it }
            track.enabled = !muted
            ownedRoom to track
        }
        val published = try { ownedRoom.localParticipant.publishAudioTrack(track) }
        catch (error: Exception) { record(VoiceDiagnosticCode.PUBLICATION_FAILED); throw error }
        currentCoroutineContext().ensureActive()
        check(!closing)
        if (published) {
            val publication = ownedRoom.localParticipant.trackPublications.values
                .filterIsInstance<LocalTrackPublication>().firstOrNull { it.track === track } ?: return false
            publication.muted = muted
            record(VoiceDiagnosticCode.PUBLISHED); event(VoiceSessionEvent.Published(tag))
        }
        if (!published) record(VoiceDiagnosticCode.PUBLICATION_FAILED)
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
            checkNotNull(audio).muteMicrophone(muted)
            publication.muted = muted
            VoiceMuteApplyResult.APPLIED
        }
    }

    override fun setDeafened(deafened: Boolean) {
        this.deafened = deafened
        if (!closing) audio?.mutePlayback(deafened || !outputAvailable || routingTransition)
    }

    override suspend fun configureAudio(preferences: VoiceAudioPreferences): VoiceAudioChangeResult =
        configureAudioIfCurrent(preferences) { true }

    override suspend fun configureAudioIfCurrent(preferences: VoiceAudioPreferences, current: () -> Boolean): VoiceAudioChangeResult = resources.withLock {
        currentCoroutineContext().ensureActive()
        if (closing || !current()) return@withLock VoiceAudioChangeResult.STALE
        val ownedAudio = audio ?: return@withLock VoiceAudioChangeResult.UNAVAILABLE
        val ownedRoutes = routes ?: return@withLock VoiceAudioChangeResult.UNAVAILABLE
        if (!ownedAudio.playbackAvailable(preferences)) return@withLock VoiceAudioChangeResult.UNAVAILABLE
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previous = diagnostics.state.value.preferences
        routingTransition = true
        ownedAudio.mutePlayback(true)
        var safe = false
        try {
            voiceAudioRouteTransaction(preferences, previous, { !closing && current() }, { pair, admitted ->
                ownedRoutes.configureOutputAndInput(pair.output, pair.input == VoiceInputPreference.PHONE,
                    requestInput = {
                        val playback = ownedAudio.configurePlayback(pair)
                        if (playback == VoiceAudioChangeResult.REQUESTED) ownedAudio.requestInput(manager, pair.input)
                        else playback
                    }, currentRequest = admitted)
            }, { safe = it }).also { result ->
                if (result == VoiceAudioChangeResult.REQUESTED && !closing && current())
                    diagnostics.update { it.copy(preferences = preferences) }
            }
        } finally {
            routingTransition = false
            if (!closing) {
                if (safe && current()) ownedAudio.mutePlayback(deafened || !outputAvailable)
                else {
                    ownedAudio.muteMicrophone(true)
                    ownedAudio.mutePlayback(true)
                    if (current()) event(VoiceSessionEvent.EngineFailed(tag))
                }
            }
        }
    }

    override suspend fun close() = closeMutex.withLock {
        closing = true
        remoteEvidence.close()
        publicationRecovery.close()
        duckGate.release()
        ducking = false
        routes?.setDuck(false)
        scope.coroutineContext.cancelChildren()
        // Each reference clears only after successful release, preserving failed cleanup for retry.
        resources.withLock {
        var failed = false
        try { audio?.stopNative() } catch (_: Exception) { failed = true }
        try { room?.release(); room = null } catch (_: Exception) { failed = true }
        try { localTrack?.dispose(); localTrack = null } catch (_: Exception) { failed = true }
        try { routes?.close(); routes = null } catch (_: Exception) { failed = true }
        try { keeper?.close(); keeper = null } catch (_: Exception) { failed = true }
        try { audio?.close(); audio = null } catch (_: Exception) { failed = true }
        if (failed) {
            record(VoiceDiagnosticCode.CLEANUP_ERROR)
            AndroidVoiceDiagnosticStore.saveFinal(diagnostics)
            throw VoiceEngineException(VoiceEngineError.CLEANUP)
        }
        scope.cancel()
        record(VoiceDiagnosticCode.CLOSED)
        AndroidVoiceDiagnosticStore.saveFinal(diagnostics)
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
                audio?.muteMicrophone(true)
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
                diagnostics.update { it.copy(sdkDisconnectReason = voiceDisconnectCause(event.reason.name)) }
                record(VoiceDiagnosticCode.DISCONNECTED)
                this.event(voiceDisconnectionEvent(tag, event.reason))
            }
            is RoomEvent.FailedToConnect -> {
                record(VoiceDiagnosticCode.CONNECT_FAILED)
                event(VoiceSessionEvent.EngineFailed(tag))
            }
            is RoomEvent.TrackPublished -> if (event.participant == ownedRoom.localParticipant &&
                event.publication.kind == Track.Kind.AUDIO && event.publication.track === localTrack &&
                ownedPublication(ownedRoom) === event.publication && publicationRecovery.sdkPublished()) {
                record(VoiceDiagnosticCode.PUBLISHED)
                this.event(VoiceSessionEvent.Published(tag))
            }
            is RoomEvent.TrackUnpublished -> if (event.participant == ownedRoom.localParticipant &&
                event.publication.kind == Track.Kind.AUDIO && ownedPublication(ownedRoom) == null) {
                audio?.muteMicrophone(true)
                this.event(VoiceSessionEvent.Unpublished(tag))
                publicationRecovery.unpublished(event.publication.muted)
            }
            is RoomEvent.TrackSubscribed -> if (event.track.kind == Track.Kind.AUDIO) {
                reconcileRemoteAudio(ownedRoom)
            }
            is RoomEvent.TrackUnsubscribed -> if (event.track.kind == Track.Kind.AUDIO) {
                reconcileRemoteAudio(ownedRoom)
            }
            is RoomEvent.ActiveSpeakersChanged -> {
                val remote = event.speakers.any { it.identity != ownedRoom.localParticipant.identity }
                applyDuck(if (listen.value.duckMusicWhileSpeaking)
                    duckGate.onSpeakers(remote, SystemClock.elapsedRealtime()) else duckGate.release())
            }
            else -> Unit
        }
    }

    /** Focus is requested only on the false->true edge and abandoned on true->false. */
    private fun applyDuck(wanted: Boolean) {
        val want = wanted && !closing && listen.value.duckMusicWhileSpeaking
        if (wanted && !want) duckGate.release()
        if (want == ducking) return
        ducking = want
        routes?.setDuck(want)
    }

    private fun reconcileRemoteAudio(ownedRoom: Room) {
        if (closing) return
        val settings = listen.value
        val gains = LinkedHashMap<io.livekit.android.room.track.RemoteAudioTrack, Double>()
        ownedRoom.remoteParticipants.values.forEach { participant ->
            val gain = listenGain(settings, participant.identity?.value?.let(actorFor))
            participant.trackPublications.values.forEach { publication ->
                (publication.track as? io.livekit.android.room.track.RemoteAudioTrack)?.let { track ->
                    gains[track] = gain
                    // SDK rendering. Phone capture plays through PhoneVoicePlayback, scaled there.
                    track.setVolume(gain)
                }
            }
        }
        audio?.remoteTracks(gains.keys.toList(), gains)
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
            audio?.samplePlayback()
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
                val complete = reports.size == remote.size && reports.all { it.audioPackets("inbound-rtp", "packetsReceived") != null }
                val inbound = if (complete)
                    sumCompleteVoiceCounters(inboundRows.map { (it.members["packetsReceived"] as? Number)?.toLong() }) else null
                val lost = if (complete) sumCompleteVoiceCounters(inboundRows.map { (it.members["packetsLost"] as? Number)?.toLong() }) else null
                val jitters = inboundRows.map { (it.members["jitter"] as? Number)?.toDouble()?.takeIf { value -> value.isFinite() && value >= 0 } }
                val jitter = if (complete && jitters.isNotEmpty() && jitters.all { it != null })
                    (jitters.filterNotNull().max() * 1000).toLong() else null
                val delays = inboundRows.map { (it.members["jitterBufferDelay"] as? Number)?.toDouble()?.takeIf { value -> value.isFinite() && value >= 0 } }
                val counts = inboundRows.map { (it.members["jitterBufferEmittedCount"] as? Number)?.toLong() }
                val emitted = sumCompleteVoiceCounters(counts)
                val meanDelay = if (complete && emitted != null && emitted > 0 && delays.all { it != null })
                    (delays.filterNotNull().sum() * 1000 / emitted).toLong() else null
                diagnostics.update { it.copy(outboundPackets = outbound, inboundPackets = inbound,
                    inboundPacketsLost = lost, inboundJitterMaxMillis = jitter, jitterBufferDelayMeanMillis = meanDelay,
                    remoteAudioTracks = remote.size) }
                record(VoiceDiagnosticCode.STATS)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Missing stats remains unknown, never invent zero. */
                diagnostics.update { it.copy(outboundPackets = null, inboundPackets = null,
                    inboundPacketsLost = null, inboundJitterMaxMillis = null, jitterBufferDelayMeanMillis = null) }
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

private const val DUCK_TICK_MILLIS = 250L
