package ru.sodovaya.volty.data.history

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import ru.sodovaya.volty.domain.history.*

/** Owns only disposable export artifacts, never the original recording. */
class FileHistoryExportRepository(
    private val directory: File,
    private val source: HistoryExportSource,
    private val encoder: HistoryExportEncoder = StreamingHistoryExportEncoder(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxFileBytes: Long = 64L * 1024 * 1024,
    private val gate: Mutex = Mutex(),
    private val reconcile: () -> Boolean = { true },
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : HistoryExportRepository {
    init { require(maxFileBytes > 0) }

    override suspend fun export(request: HistoryExportRequest): HistoryExportResult {
        var ownedReady: Path? = null
        try {
            return withContext(dispatcher) {
                gate.withLock {
                    try {
                        if (!reconcile()) throw IOException("Derived history cleanup pending")
                        write(request) { ownedReady = it }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: HistoryExportException) { HistoryExportResult.Failed(failure.reason) }
                    catch (_: IOException) { HistoryExportResult.Failed(HistoryExportFailure.STORAGE) }
                    catch (_: SecurityException) { HistoryExportResult.Failed(HistoryExportFailure.STORAGE) }
                }
            }
        } catch (cancel: CancellationException) {
            // withContext has prompt cancellation when returning to UI. The IO
            // block's own finally cannot see a cancellation at that boundary.
            withContext(NonCancellable + dispatcher) {
                ownedReady?.let { path ->
                    try { Files.deleteIfExists(path) }
                    catch (failure: Exception) { cancel.addSuppressed(failure) }
                }
            }
            throw cancel
        }
    }

    private suspend fun write(request: HistoryExportRequest, onPublished: (Path) -> Unit): HistoryExportResult.Success {
        val input = source.open(request)
        HistoryExportFileCache(directory).prepare(maxFileBytes)
        val root = directory.toPath()
        Files.createDirectories(root)
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw IOException("Export directory unavailable")
        // A later export of the same range replaces the earlier file of that name (ATOMIC_MOVE).
        val name = historyExportFileName(request.format, historyExportVehicleName(input.metadata),
            request.range.startEpochMillis, zone())
        if (!HistoryExportFileCache.isReadyName(name)) throw HistoryExportException(HistoryExportFailure.INVALID_DATA)
        val target = root.resolve(name)
        val temporary = Files.createTempFile(root, "volty-export-tmp-v1-", ".tmp")
        var published = false
        var completed = false
        try {
            var bytes = 0L
            var counts = HistoryExportCounts()
            val digest = MessageDigest.getInstance("SHA-256")
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                counts = encoder.write(request.format, input.metadata, input.records) { fragment ->
                    currentCoroutineContext().ensureActive()
                    // Encoders supply bounded fragments; enforce bytes, not UTF-16 characters.
                    if (fragment.length.toLong() > maxFileBytes - bytes) throw HistoryExportException(HistoryExportFailure.LIMIT)
                    val encoded = fragment.toByteArray(Charsets.UTF_8)
                    if (encoded.size.toLong() > maxFileBytes - bytes) throw HistoryExportException(HistoryExportFailure.LIMIT)
                    val buffer = ByteBuffer.wrap(encoded)
                    while (buffer.hasRemaining()) { currentCoroutineContext().ensureActive(); channel.write(buffer) }
                    digest.update(encoded)
                    bytes += encoded.size
                }
                if (bytes == 0L) throw HistoryExportException(HistoryExportFailure.INVALID_DATA)
                channel.force(true)
            }
            // Stream completion already revalidates its pinned SQL snapshot. Check
            // source identity again after flushing, before offering a finished artifact.
            if (source.open(request).metadata != input.metadata) throw HistoryExportException(HistoryExportFailure.SOURCE_CHANGED)
            currentCoroutineContext().ensureActive()
            Files.move(temporary, target, ATOMIC_MOVE)
            published = true
            onPublished(target)
            currentCoroutineContext().ensureActive()
            val result = HistoryExportResult.Success(name, bytes,
                digest.digest().joinToString("") { "%02x".format(it) }, request.format.mime, counts.droppedLate)
            completed = true
            return result
        } finally {
            // A failed/canceled operation owns exactly these paths. Never sweep
            // unrelated cache files or touch the original SQL recording here.
            Files.deleteIfExists(temporary)
            if (published && !completed) Files.deleteIfExists(target)
        }
    }
}
