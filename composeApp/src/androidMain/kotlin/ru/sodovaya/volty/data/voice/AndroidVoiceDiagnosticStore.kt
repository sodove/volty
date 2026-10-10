package ru.sodovaya.volty.data.voice

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.voice.VoiceDiagnosticSnapshot
import ru.sodovaya.volty.domain.voice.VoiceDiagnostics
import ru.sodovaya.volty.domain.voice.VoiceDiagnosticReportHistory
import ru.sodovaya.volty.domain.voice.formatVoiceDiagnostics
import ru.sodovaya.volty.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/** Process-local observer that keeps a bounded history of recent audio allocations. */
internal object AndroidVoiceDiagnosticStore {
    private const val FILE_NAME = "volty_voice_diagnostic_store.txt"
    private const val STORE_HEADER = "VOLTY_VOICE_DIAGNOSTICS_V1\n"
    private const val REPORT_SEPARATOR = "\n--VOICE_REPORT--\n"
    private const val MAX_STORE_BYTES = 96 * 1024
    private const val MAX_REPORT_CHARS = 24 * 1024
    private const val MAX_REPORTS = 3
    private const val MAX_TRACE_ENTRIES = 128
    private const val WRITE_INTERVAL_MS = 1_000L

    private val lock = Any()
    private val diskMutex = Mutex()
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeRequests = Channel<Unit>(Channel.CONFLATED)

    /** Reports created during this process; the current report is always the last item. */
    private val processReports = VoiceDiagnosticReportHistory(MAX_REPORTS)
    /** History loaded once from disk, before this process started adding allocations. */
    private var diskHistory: List<String>? = null
    private var storageDirectory: File? = null
    private var currentDiagnostics: VoiceDiagnostics? = null
    private var collector: Job? = null
    private var lastSuccessfulWriteAt = 0L

    init {
        processScope.launch {
            for (request in writeRequests) {
                try {
                    persistLatestThrottled()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Diagnostics must never affect audio allocation or call cleanup.
                }
            }
        }
    }

    /** Attach once per SDK allocation. State collection and persistence run away from audio callbacks. */
    fun attach(context: Context, diagnostics: VoiceDiagnostics) {
        val directory = context.applicationContext.filesDir
        var oldCollector: Job? = null
        synchronized(lock) {
            useDirectoryLocked(directory)
            oldCollector = collector

            // Preserve the previous allocation's final published state before replacing it.
            currentDiagnostics?.let { previous ->
                processReports.replace(previous, formatSnapshot(previous.state.value))
            }

            currentDiagnostics = diagnostics
            processReports.remember(diagnostics, formatSnapshot(diagnostics.state.value))

            collector = processScope.launch {
                while (true) {
                    delay(WRITE_INTERVAL_MS)
                    // StateFlow can be updated for every audio callback. Read only the
                    // latest value once per second so formatting stays off that path.
                    val snapshot = diagnostics.state.value
                    val report = formatSnapshot(snapshot)
                    val accepted = synchronized(lock) {
                        if (currentDiagnostics !== diagnostics) {
                            false
                        } else {
                            processReports.replace(diagnostics, report)
                            true
                        }
                    }
                    if (accepted) requestWrite()
                }
            }
        }
        oldCollector?.cancel()
        requestWrite()
    }

    /** Save the last state after CLOSED and stop observing this allocation. */
    fun saveFinal(diagnostics: VoiceDiagnostics) {
        val finalReport = formatSnapshot(diagnostics.state.value)
        val job = synchronized(lock) {
            if (!processReports.replace(diagnostics, finalReport)) return
            // A late final snapshot belongs to its retained slot. It cannot stop
            // the current allocation's collector or replace its report.
            if (currentDiagnostics === diagnostics) {
                currentDiagnostics = null
                collector.also { collector = null }
            } else null
        }
        job?.cancel()
        requestWrite()
    }

    /** Returns the latest in-memory report immediately and at most two older reports. */
    fun export(context: Context): String {
        val directory = context.applicationContext.filesDir
        synchronized(lock) { useDirectoryLocked(directory) }
        val reports = runBlocking(Dispatchers.IO) {
            diskMutex.withLock {
                val base = loadDiskHistoryLocked(directory)
                synchronized(lock) {
                    currentDiagnostics?.let { latest ->
                        processReports.replace(latest, formatSnapshot(latest.state.value))
                    }
                    (base + processReports.reports()).takeLast(MAX_REPORTS)
                }
            }
        }
        if (reports.isEmpty()) return "voice_diagnostics=NOT_AVAILABLE"
        return reports.mapIndexed { index, report ->
            "--- recent voice report ${index + 1} ---\n$report"
        }.joinToString("\n\n")
    }

    private fun useDirectoryLocked(directory: File) {
        if (storageDirectory == directory) return
        storageDirectory = directory
        diskHistory = null
        processReports.clear()
        currentDiagnostics = null
        collector?.cancel()
        collector = null
    }

    private fun requestWrite() {
        writeRequests.trySend(Unit)
    }

    private suspend fun persistLatestThrottled() {
        val waitMs = synchronized(lock) {
            if (lastSuccessfulWriteAt == 0L) 0L
            else (WRITE_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastSuccessfulWriteAt)).coerceAtLeast(0L)
        }
        if (waitMs > 0) delay(waitMs)

        diskMutex.withLock {
            val directory = synchronized(lock) { storageDirectory } ?: return
            val base = loadDiskHistoryLocked(directory)
            val reports = synchronized(lock) { (base + processReports.reports()).takeLast(MAX_REPORTS) }
            if (reports.isEmpty()) return
            writeStore(directory, reports)
            synchronized(lock) { lastSuccessfulWriteAt = SystemClock.elapsedRealtime() }
        }
    }

    /** Must be called under [diskMutex]. */
    private fun loadDiskHistoryLocked(directory: File): List<String> {
        synchronized(lock) { diskHistory?.let { return it } }
        val loaded = readStore(directory)
        return synchronized(lock) {
            if (storageDirectory != directory) return@synchronized loaded
            if (diskHistory == null) diskHistory = loaded
            diskHistory.orEmpty()
        }
    }

    private fun readStore(directory: File): List<String> {
        val atomicFile = AtomicFile(File(directory, FILE_NAME))
        val bytes = try {
            atomicFile.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                var total = 0
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_STORE_BYTES + 1 - total))
                    if (count < 0) break
                    total += count
                    if (total > MAX_STORE_BYTES) return emptyList()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (_: FileNotFoundException) {
            return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val text = String(bytes, StandardCharsets.UTF_8)
        if (!text.startsWith(STORE_HEADER) || text.any { it.code !in 0x0A..0x7E }) return emptyList()
        return text.removePrefix(STORE_HEADER)
            .split(REPORT_SEPARATOR)
            .filter(::isStoredReport)
            .takeLast(MAX_REPORTS)
    }

    private fun isStoredReport(report: String): Boolean =
        report.length <= MAX_REPORT_CHARS &&
            report.startsWith("voice_diagnostics=AVAILABLE\n") &&
            "livekit_version=2.28.0" in report &&
            "webrtc_version=144.7559.09" in report

    private fun writeStore(directory: File, reports: List<String>) {
        if (!directory.exists() && !directory.mkdirs()) return
        val payload = (STORE_HEADER + reports.takeLast(MAX_REPORTS).joinToString(REPORT_SEPARATOR))
            .toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_STORE_BYTES) return

        val atomicFile = AtomicFile(File(directory, FILE_NAME))
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(payload)
            output.flush()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }

    /** Same typed formatter as the tested domain export, with bounded lists and no generation. */
    private fun formatSnapshot(snapshot: VoiceDiagnosticSnapshot): String {
        val bounded = snapshot.copy(
            recordingDeviceTypes = snapshot.recordingDeviceTypes.take(16),
            recordingSources = snapshot.recordingSources.take(16),
            ownedPlaybackDeviceTypes = snapshot.ownedPlaybackDeviceTypes.take(16),
            trace = snapshot.trace.takeLast(MAX_TRACE_ENTRIES),
        )
        val body = bounded.formatVoiceDiagnostics().lineSequence()
            .filterNot { it.startsWith("generation=") }.joinToString("\n")
        return ("voice_diagnostics=AVAILABLE\nvolty_version=${BuildConfig.VERSION_NAME}\nlivekit_version=2.28.0\nwebrtc_version=144.7559.09\n" + body)
            .take(MAX_REPORT_CHARS)
    }
}
