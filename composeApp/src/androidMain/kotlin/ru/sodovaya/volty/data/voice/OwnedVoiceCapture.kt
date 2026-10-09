package ru.sodovaya.volty.data.voice

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.nio.ByteBuffer
import ru.sodovaya.volty.domain.voice.*

/** The callback owns PCM, including its final mute gate; ADM's mute runs before this callback. */
internal class OwnedVoiceCapture(
    private val diagnostics: VoiceDiagnostics,
    private val state: (Boolean) -> Unit,
    private val failed: (VoiceDiagnosticCode) -> Unit,
) {
    private val lock = Any()
    private val gate = VoiceCaptureFrameGate()
    private var record: AudioRecord? = null
    private var device: AudioDeviceInfo? = null
    private var preference = VoiceInputPreference.AUTO
    private var closed = false
    private var fault = false
    private var mismatchedFrames = 0
    private var sampleRate = 0
    private var channels = 0
    private var scratch: ByteBuffer? = null
    private var pending: VoiceCaptureFrameBuffer? = null
    private var chunkBytes: ByteArray? = null
    val preferredInputId: Int? get() = synchronized(lock) { device?.id }

    fun mute(value: Boolean) = synchronized(lock) { gate.mute(value) }

    fun request(manager: AudioManager, requested: VoiceInputPreference): VoiceAudioChangeResult = synchronized(lock) {
        if (closed) return@synchronized VoiceAudioChangeResult.STALE
        val selected = if (requested == VoiceInputPreference.AUTO) null else
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                if (requested == VoiceInputPreference.PHONE) it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                else it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        if (requested != VoiceInputPreference.AUTO && selected == null) return@synchronized VoiceAudioChangeResult.UNAVAILABLE
        // Rebuild on source changes, rather than pretending a preference can change AudioSource.
        if (requested != preference || selected?.id != device?.id) stopLocked()
        preference = requested
        device = selected
        gate.request(selected?.id)
        fault = false
        mismatchedFrames = 0
        VoiceAudioChangeResult.REQUESTED
    }

    fun fill(buffer: ByteBuffer, format: Int, count: Int, rate: Int): Long {
        val startedAt = System.nanoTime()
        val deadline = startedAt + FRAME_NANOS
        val revision = synchronized(lock) { gate.beginRead() }
        var bytes = 0
        try {
            while (bytes < buffer.capacity() && System.nanoTime() < deadline) {
                val read = synchronized(lock) {
                    if (closed || fault) return@synchronized -1
                    require(format == AudioFormat.ENCODING_PCM_16BIT && count in 1..2)
                    if (record == null || rate != sampleRate || count != channels) {
                        stopLocked()
                        val mask = if (count == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
                        val minimum = AudioRecord.getMinBufferSize(rate, mask, format)
                        check(minimum > 0)
                        val allocated = AudioRecord.Builder()
                            .setAudioSource(if (preference == VoiceInputPreference.PHONE)
                                MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                            .setAudioFormat(AudioFormat.Builder().setEncoding(format).setSampleRate(rate).setChannelMask(mask).build())
                            .setBufferSizeInBytes(maxOf(minimum, buffer.capacity() * 4)).build()
                        record = allocated // Retain it even if native setup fails, for cleanup.
                        check(allocated.state == AudioRecord.STATE_INITIALIZED)
                        check(allocated.setPreferredDevice(device))
                        allocated.startRecording()
                        check(allocated.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        sampleRate = rate; channels = count
                        scratch = ByteBuffer.allocateDirect(buffer.capacity())
                        pending = VoiceCaptureFrameBuffer(buffer.capacity())
                        chunkBytes = ByteArray(buffer.capacity())
                        state(true) // Actual owned AudioRecord, never the disabled ADM callback.
                    }
                    val owned = checkNotNull(record)
                    val frame = checkNotNull(pending)
                    frame.begin(revision)
                    val chunk = checkNotNull(scratch).apply { clear() }
                    val size = owned.read(chunk, buffer.capacity() - frame.size, AudioRecord.READ_NON_BLOCKING)
                    if (size < 0 && !closed && !fault) {
                        fault = true
                        failed(VoiceDiagnosticCode.RECORD_RUNTIME_ERROR)
                    }
                    val routed = owned.routedDevice?.id
                    diagnostics.update { it.copy(ownedCaptureDeviceType = owned.routedDevice?.type) }
                    if (size > 0) {
                        chunk.limit(size); chunk.position(0)
                        val values = checkNotNull(chunkBytes)
                        chunk.get(values, 0, size)
                        frame.append(values, size, routed)
                    }
                    bytes = frame.size
                    size
                }
                if (read < 0) break
                if (bytes < buffer.capacity()) Thread.sleep(1)
            }
        } catch (_: Exception) {
            synchronized(lock) {
                if (!closed && !fault) { fault = true; failed(VoiceDiagnosticCode.RECORD_RUNTIME_ERROR) }
            }
        }
        synchronized(lock) {
            val frame = pending
            val pcm = frame?.complete()
            val complete = pcm != null
            val actual = frame?.device
            // A vendor may accept the preference and still route to the Bluetooth mic.
            // Such PCM is never sent under an explicit Phone choice.
            if (gate.permitsFrame(revision, actual, complete)) {
                buffer.clear(); buffer.put(checkNotNull(pcm))
            } else {
                buffer.clear(); while (buffer.hasRemaining()) buffer.put(0.toByte())
            }
            if (complete) frame?.consume()
            if (complete && !closed) diagnostics.addCaptureBytes(bytes)
            if (!closed && !fault && complete && device != null && actual != device?.id) {
                mismatchedFrames++
                if (mismatchedFrames >= 30) { fault = true; failed(VoiceDiagnosticCode.INPUT_UNAVAILABLE) }
            } else if (complete) mismatchedFrames = 0
        }
        buffer.rewind()
        // With ADM AudioRecord disabled, the callback MUST pace the native capture loop.
        val remaining = deadline - System.nanoTime()
        if (remaining > 0) SystemClock.sleep((remaining + 999_999) / 1_000_000)
        return startedAt
    }

    fun stop() = synchronized(lock) { stopLocked() }
    fun close() = synchronized(lock) { closed = true; gate.close(); stopLocked() }
    private fun stopLocked() {
        val owned = record ?: return
        if (owned.recordingState == AudioRecord.RECORDSTATE_RECORDING) owned.stop()
        owned.release()
        record = null; scratch = null; pending = null; chunkBytes = null
        diagnostics.update { it.copy(ownedCaptureDeviceType = null) }
        state(false)
    }

    private companion object { const val FRAME_NANOS = 10_000_000L }
}
