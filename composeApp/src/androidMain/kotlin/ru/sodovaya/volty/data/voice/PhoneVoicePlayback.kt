package ru.sodovaya.volty.data.voice

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Build
import io.livekit.android.room.track.RemoteAudioTrack
import livekit.org.webrtc.AudioTrackSink
import ru.sodovaya.volty.domain.voice.*

/** Separate media playback for Phone capture; no SCO or communication-endpoint selection. */
internal class PhoneVoicePlayback(
    private val context: Context,
    private val manager: AudioManager,
    private val diagnostics: VoiceDiagnostics,
    private val running: (Boolean) -> Unit,
    private val failed: () -> Unit,
) {
    private val lock = Any()
    private val streams = mutableMapOf<RemoteAudioTrack, Stream>()
    private val pendingDetach = mutableSetOf<Stream>()
    private var enabled = false
    private var muted = false
    private var closed = false
    private var output = VoiceOutputPreference.AUTO
    private var reported = false
    private val blockedDevices = mutableSetOf<Int>()
    private var receiverRegistered = false
    private var retiredUnderruns = 0L
    private var writeMaxMicros = 0L
    private var observedTrack = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            synchronized(lock) {
                if (!enabled || closed || output == VoiceOutputPreference.SPEAKER) return
                preferred(output)?.id?.let { blockedDevices.add(it) }
                streams.values.forEach { it.release() }
                report()
            }
        }
    }

    fun available(active: Boolean, requested: VoiceOutputPreference): Boolean = synchronized(lock) {
        !closed && (!active || requested == VoiceOutputPreference.AUTO || preferred(requested) != null)
    }

    fun configure(active: Boolean, requested: VoiceOutputPreference): VoiceAudioChangeResult = synchronized(lock) {
        if (closed) return@synchronized VoiceAudioChangeResult.STALE
        if (active && requested != VoiceOutputPreference.AUTO && preferred(requested) == null)
            return@synchronized VoiceAudioChangeResult.UNAVAILABLE
        if (active != enabled || requested != output) streams.values.forEach { it.release() }
        if (active && !receiverRegistered) {
            ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        enabled = active; output = requested
        report()
        VoiceAudioChangeResult.REQUESTED
    }

    fun mute(value: Boolean) = synchronized(lock) {
        muted = value
        if (value) streams.values.forEach { it.clearBufferedAudio() }
        report()
    }

    fun reconcile(current: List<RemoteAudioTrack>) {
        val removed: List<Stream>
        val added: List<Stream>
        synchronized(lock) {
            if (closed) return
            removed = (streams.keys - current.toSet()).map { streams.remove(it)!! }
            // Fence callbacks before invoking SDK removeSink, which can wait on its RTC thread.
            removed.forEach { it.retired = true; it.release() }
            pendingDetach.addAll(removed)
            added = current.filter { it !in streams }.map { remote -> Stream(remote).also { streams[remote] = it } }
            report()
        }
        detach(removed)
        added.forEach { stream ->
            stream.remote.addSink(stream)
            // close may have removed this stream before addSink reached the RTC thread.
            val retired = synchronized(lock) {
                (closed || stream.retired).also { if (it) pendingDetach.add(stream) }
            }
            if (retired) detach(listOf(stream))
        }
    }

    fun close() {
        val removed = synchronized(lock) {
            closed = true
            if (receiverRegistered) { context.unregisterReceiver(noisy); receiverRegistered = false }
            streams.values.forEach { it.retired = true; it.release(); pendingDetach.add(it) }
            streams.clear(); report()
            pendingDetach.toList()
        }
        detach(removed)
    }

    /** Native counters are sampled by the existing one-second session poll, not every frame. */
    fun sampleDiagnostics() = synchronized(lock) {
        val underruns = if (observedTrack) retiredUnderruns + streams.values.sumOf { it.track?.underrunCount?.toLong() ?: 0L } else null
        diagnostics.update { it.copy(playbackUnderruns = underruns,
            playbackWriteMaxMicros = if (observedTrack) writeMaxMicros else null) }
    }

    private fun write(track: AudioTrack, bytes: ByteArray, count: Int): Int {
        val started = System.nanoTime()
        return try { track.write(bytes, 0, count, AudioTrack.WRITE_NON_BLOCKING) }
        finally { writeMaxMicros = maxOf(writeMaxMicros, (System.nanoTime() - started) / 1000) }
    }

    private fun detach(list: List<Stream>) {
        list.forEach { stream ->
            stream.remote.removeSink(stream)
            synchronized(lock) { pendingDetach.remove(stream) }
        }
    }

    private fun preferred(choice: VoiceOutputPreference): AudioDeviceInfo? {
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return when (choice) {
            VoiceOutputPreference.SPEAKER -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } ?:
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }
        }
    }

    private fun report() {
        val active = streams.values.any { it.track?.playState == AudioTrack.PLAYSTATE_PLAYING }
        diagnostics.update { it.copy(ownedPlaybackDeviceTypes = streams.values.mapNotNull { stream -> stream.track?.routedDevice?.type }.distinct()) }
        if (active != reported) { reported = active; running(active) }
    }

    private inner class Stream(val remote: RemoteAudioTrack) : AudioTrackSink {
        var retired = false
        var track: AudioTrack? = null
        private var rate = 0
        private var channels = 0
        private var fault = false
        private var buffered: VoicePlaybackBuffer? = null
        private var primed = false
        override fun onData(data: java.nio.ByteBuffer, bits: Int, sampleRate: Int, channelCount: Int, frames: Int, timestamp: Long) {
            synchronized(lock) {
                if (closed || retired || !enabled || fault) return
                try {
                    require(bits == 16 && channelCount in 1..2 && frames > 0)
                    val bytes = frames * channelCount * 2
                    require(bytes <= data.remaining())
                    val selected = preferred(output)
                    blockedDevices.retainAll(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet())
                    if (selected?.id in blockedDevices) { release(); report(); return }
                    if (output != VoiceOutputPreference.AUTO && selected == null) { release(); report(); return }
                    if (track != null && (rate != sampleRate || channels != channelCount || track?.preferredDevice?.id != selected?.id)) release()
                    val owned = track ?: run {
                        val mask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                        val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                        check(minimum > 0)
                        AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(maxOf(minimum, sampleRate * channelCount * 2 * 80 / 1000))
                            .setTransferMode(AudioTrack.MODE_STREAM).build().also {
                                track = it
                                observedTrack = true
                                check(it.state == AudioTrack.STATE_INITIALIZED)
                                check(it.setPreferredDevice(selected))
                                rate = sampleRate; channels = channelCount
                                buffered = VoicePlaybackBuffer(sampleRate * channelCount * 2 * 120 / 1000, channelCount * 2)
                            }
                    }
                    if (muted) return
                    if (!primed) {
                        // Start with a small native reserve instead of playing an empty buffer.
                        val silence = ByteArray(sampleRate * channelCount * 2 * 40 / 1000)
                        check(write(owned, silence, silence.size) >= 0)
                        primed = true
                    }
                    if (owned.playState != AudioTrack.PLAYSTATE_PLAYING) owned.play()
                    val actual = owned.routedDevice
                    val permitted = voicePlaybackFrameAllowed(output, selected?.id, actual?.id)
                    // Prime an unconfirmed route with silence. Never leak headset-only voice to the speaker.
                    val backlog = checkNotNull(buffered)
                    if (!permitted) backlog.clear()
                    val pcm = ByteArray(bytes)
                    if (permitted) data.duplicate().get(pcm)
                    backlog.append(pcm)
                    // A nonblocking AudioTrack write may accept only a prefix or zero bytes.
                    // Keep the tail for later callbacks; cap delay instead of growing forever.
                    do {
                        val written = backlog.drain { values, count -> write(owned, values, count) }
                        if (permitted) diagnostics.addRenderBytes(written)
                    } while (written > 0 && backlog.size > 0)
                    report()
                } catch (_: Exception) { fault = true; failed() }
            }
        }
        fun clearBufferedAudio() {
            buffered?.clear(); primed = false
            track?.let { it.pause(); it.flush() }
        }
        fun release() {
            track?.let { retiredUnderruns += it.underrunCount; it.release() }
            track = null; buffered = null; primed = false; fault = false
        }
    }
}
