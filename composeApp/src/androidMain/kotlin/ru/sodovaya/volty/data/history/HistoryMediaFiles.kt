package ru.sodovaya.volty.data.history

import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import ru.sodovaya.volty.domain.history.HistoryMediaFailure

data class HistoryStagedMedia(val bytes: Long, val sha256: String)
class HistoryMediaFileException(val reason: HistoryMediaFailure) : IOException(reason.name)

/** Private file adapter. The SQL import journal decides when a staged file may publish. */
class HistoryMediaFiles(private val directory: File, private val maxItemBytes: Long = 256L * 1024 * 1024) {
    init { require(maxItemBytes in 1..256L * 1024 * 1024) }
    private val root = directory.toPath()

    fun stage(id: String, open: () -> InputStream, checkCancelled: () -> Unit,
        validate: (File) -> Unit): HistoryStagedMedia {
        validateId(id)
        checkCancelled()
        requireRoot(create = true)
        val temporary = root.resolve("$id.import")
        if (Files.exists(root.resolve("$id.media"), NOFOLLOW_LINKS)) throw IOException("Media already published")
        var created = false
        var prepared = false
        var failure: Throwable? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            open().use { input ->
                FileChannel.open(temporary, CREATE_NEW, WRITE).use { output ->
                    created = true
                    val buffer = ByteArray(8192)
                    var stalled = 0
                    while (true) {
                        checkCancelled()
                        // Read only one byte past the cap, even for a provider without a size hint.
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), maxItemBytes - count + 1).toInt())
                        checkCancelled()
                        if (read < 0) break
                        if (read == 0) {
                            if (++stalled >= 3) throw HistoryMediaFileException(HistoryMediaFailure.INVALID_MEDIA)
                            continue
                        }
                        stalled = 0
                        count += read
                        if (count > maxItemBytes) throw HistoryMediaFileException(HistoryMediaFailure.LIMIT)
                        digest.update(buffer, 0, read)
                        val bytes = ByteBuffer.wrap(buffer, 0, read)
                        while (bytes.hasRemaining()) { checkCancelled(); output.write(bytes) }
                    }
                    if (count == 0L) throw HistoryMediaFileException(HistoryMediaFailure.INVALID_MEDIA)
                    output.force(true)
                }
            }
            checkCancelled()
            validate(temporary.toFile())
            checkCancelled()
            prepared = true
            return HistoryStagedMedia(count, digest.digest().hex())
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Never remove a pre-existing import when CREATE_NEW rejected this attempt.
            if (created && !prepared) try { deleteOwnedFile(temporary) }
            catch (cleanup: Exception) { failure?.addSuppressed(cleanup) ?: throw cleanup }
        }
    }

    fun publish(id: String) {
        validateId(id)
        if (!requireRoot(false)) throw IOException("Missing media directory")
        val temporary = root.resolve("$id.import")
        if (!isOwnedRegular(temporary)) throw IOException("Missing staged media")
        val final = root.resolve("$id.media")
        if (Files.exists(final, NOFOLLOW_LINKS)) throw IOException("Media already published")
        Files.move(temporary, final, ATOMIC_MOVE)
    }

    fun verifiedFile(id: String, bytes: Long, sha256: String): File? {
        validateId(id)
        if (bytes !in 1..maxItemBytes || !sha256.matches(Regex("[0-9a-f]{64}")) || !requireRoot(false)) return null
        val path = root.resolve("$id.media")
        if (!isOwnedRegular(path) || Files.size(path) != bytes) return null
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                if (count > bytes) return null
                digest.update(buffer, 0, read)
            }
        }
        return path.toFile().takeIf { count == bytes && digest.digest().hex() == sha256 }
    }

    fun remove(id: String) {
        validateId(id)
        if (!requireRoot(false)) return
        deleteOwnedFile(root.resolve("$id.import"))
        deleteOwnedFile(root.resolve("$id.media"))
    }

    fun ownedFiles(): Set<String> {
        if (!requireRoot(false)) return emptySet()
        return Files.newDirectoryStream(root).use { paths ->
            paths.filter { OWNED_NAME.matches(it.fileName.toString()) && isOwnedRegular(it) }
                .map { it.fileName.toString() }.toSet()
        }
    }

    private fun requireRoot(create: Boolean): Boolean {
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            if (!create) return false
            Files.createDirectories(root)
        }
        val attrs = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attrs.isDirectory || attrs.isSymbolicLink) throw IOException("Invalid media directory")
        return true
    }
    private fun isOwnedRegular(path: Path): Boolean {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return false
        // On case-insensitive hosts an uppercase foreign name may resolve through
        // this path. Only exact namespace entries belong to this adapter.
        val exact = Files.newDirectoryStream(root).use { paths -> paths.any { it.fileName.toString() == path.fileName.toString() } }
        if (!exact) return false
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        return attrs.isRegularFile && !attrs.isSymbolicLink
    }
    private fun deleteOwnedFile(path: Path) { if (isOwnedRegular(path)) Files.deleteIfExists(path) }
    private fun validateId(id: String) { require(ID.matches(id)) }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private companion object {
        const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val ID = Regex(UUID)
        val OWNED_NAME = Regex("$UUID\\.(import|media)")
    }
}
