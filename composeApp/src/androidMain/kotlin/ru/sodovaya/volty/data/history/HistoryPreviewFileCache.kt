package ru.sodovaya.volty.data.history

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

class HistoryPreviewFileCache(
    private val directory: File,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val maxEntries: Int = 64,
    private val maxItemBytes: Int = 1024 * 1024,
    private val maxAgeMillis: Long = 24L * 60 * 60 * 1000,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val directoryPath: Path = directory.toPath()

    init {
        require(maxBytes > 0)
        require(maxEntries > 0)
        require(maxItemBytes > 0)
        require(maxItemBytes.toLong() <= maxBytes)
        require(maxAgeMillis > 0)
    }

    @Synchronized
    fun read(key: String): ByteArray? {
        validateKey(key)
        if (!isRealDirectory()) return null

        val file = directoryPath.resolve("$key.png")
        if (!hasExactName(file)) return null
        val attributes = try {
            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        if (!attributes.isRegularFile || attributes.isSymbolicLink) return null

        val readAt = now()
        if (isExpired(readAt, attributes.lastModifiedTime().toMillis()) ||
            attributes.size() <= 0 || attributes.size() > maxItemBytes.toLong()
        ) {
            deleteOwnedRegularFile(file)
            return null
        }

        val bytes = try {
            readBounded(file)
        } catch (_: NoSuchFileException) {
            return null
        } ?: run {
            deleteOwnedRegularFile(file)
            return null
        }

        // Recheck the entry type after opening so a replaced link is never deliberately touched.
        val currentAttributes = try {
            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        if (!currentAttributes.isRegularFile || currentAttributes.isSymbolicLink) return null
        Files.setLastModifiedTime(file, FileTime.fromMillis(readAt))
        return bytes
    }

    @Synchronized
    fun write(key: String, bytes: ByteArray) {
        validateKey(key)
        require(bytes.isNotEmpty()) { "Preview bytes must not be empty" }
        require(bytes.size <= maxItemBytes) { "Preview exceeds the per-item size limit" }
        require(bytes.size.toLong() <= maxBytes) { "Preview exceeds the cache size limit" }

        val payload = bytes.copyOf()
        val directory = ensureDirectoryForWrite()
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) if (OWNED_TEMP.matches(entry.fileName.toString())) deleteOwnedRegularFile(entry)
        }
        val target = directory.resolve("$key.png")
        ensureTargetIsRegularOrMissing(target)

        val writtenAt = now()
        val temporary = Files.createTempFile(directory, "volty-preview-tmp-v1-$key-", ".tmp")
        var published = false
        try {
            writeBytes(temporary, payload)
            Files.setLastModifiedTime(temporary, FileTime.fromMillis(writtenAt))
            pruneToFit(key, payload.size.toLong(), writtenAt)
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            published = true
        } finally {
            if (!published) deleteOwnedRegularFile(temporary)
        }
    }

    @Synchronized
    fun remove(key: String) {
        validateKey(key)
        if (!isRealDirectory()) return
        deleteOwnedRegularFile(directoryPath.resolve("$key.png"))
    }

    private fun validateKey(key: String) {
        require(key.length == KEY_LENGTH && key.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Preview cache keys must be 64 lowercase hexadecimal characters"
        }
    }

    private fun ensureDirectoryForWrite(): Path {
        val existing = directoryAttributes()
        if (existing != null) {
            if (isRealDirectory(existing)) return directoryPath
            throw IOException("Preview cache path is not a directory")
        }
        try {
            Files.createDirectory(directoryPath)
        } catch (alreadyExists: FileAlreadyExistsException) {
            val raced = directoryAttributes()
            if (raced != null && isRealDirectory(raced)) return directoryPath
            throw IOException("Preview cache path is not a directory", alreadyExists)
        }
        return directoryPath
    }

    private fun ensureTargetIsRegularOrMissing(target: Path) {
        val attributes = try {
            Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return
        }
        if (!hasExactName(target) || !attributes.isRegularFile || attributes.isSymbolicLink) {
            throw IOException("Preview cache target is not a regular file")
        }
    }

    private fun isRealDirectory(): Boolean = directoryAttributes()?.let(::isRealDirectory) ?: false

    private fun directoryAttributes(): BasicFileAttributes? = try {
        Files.readAttributes(directoryPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun isRealDirectory(attributes: BasicFileAttributes): Boolean =
        attributes.isDirectory && !attributes.isSymbolicLink

    private fun readBounded(file: Path): ByteArray? {
        val output = ByteArrayOutputStream()
        val capacity = minOf(BUFFER_BYTES.toLong(), maxItemBytes.toLong() + 1L).toInt()
        val buffer = ByteBuffer.allocate(capacity)
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            while (true) {
                val remainingIncludingOverflow = maxItemBytes.toLong() - output.size().toLong() + 1L
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), remainingIncludingOverflow).toInt())
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (output.size().toLong() + count > maxItemBytes.toLong()) return null
                output.write(buffer.array(), 0, count)
            }
        }
        if (output.size() == 0) return null
        return output.toByteArray()
    }

    private fun writeBytes(file: Path, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        Files.newByteChannel(
            file,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    private fun pruneToFit(protectedKey: String, incomingBytes: Long, currentTime: Long) {
        val candidates = ownedPngFiles()
        val retained = ArrayList<CacheEntry>(candidates.size)
        for (entry in candidates) {
            if (isExpired(currentTime, entry.lastModifiedMillis)) {
                deleteOwnedRegularFile(entry.path)
            } else if (entry.key != protectedKey) {
                retained += entry
            }
        }

        retained.sortWith(compareBy<CacheEntry>({ it.lastModifiedMillis }, { it.key }))
        var totalBytes = retained.fold(0L) { total, entry ->
            if (Long.MAX_VALUE - total < entry.sizeBytes) Long.MAX_VALUE else total + entry.sizeBytes
        }
        while (retained.size >= maxEntries || totalBytes > maxBytes - incomingBytes) {
            val oldest = retained.removeAt(0)
            deleteOwnedRegularFile(oldest.path)
            totalBytes -= oldest.sizeBytes
        }
    }

    private fun ownedPngFiles(): List<CacheEntry> {
        val entries = ArrayList<CacheEntry>()
        Files.newDirectoryStream(directoryPath).use { paths ->
            for (path in paths) {
                val key = keyFromPngName(path.fileName.toString()) ?: continue
                val attributes = try {
                    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    continue
                }
                if (!attributes.isRegularFile || attributes.isSymbolicLink) continue
                entries += CacheEntry(path, key, attributes.size(), attributes.lastModifiedTime().toMillis())
            }
        }
        return entries
    }

    private fun keyFromPngName(name: String): String? {
        if (!name.endsWith(PNG_SUFFIX) || name.length != KEY_LENGTH + PNG_SUFFIX.length) return null
        val key = name.substring(0, KEY_LENGTH)
        return key.takeIf { it.all { character -> character in '0'..'9' || character in 'a'..'f' } }
    }

    private fun deleteOwnedRegularFile(path: Path) {
        if (!hasExactName(path)) return
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return
        }
        if (attributes.isRegularFile && !attributes.isSymbolicLink) Files.deleteIfExists(path)
    }

    // On case-insensitive filesystems a constructed lowercase path can resolve
    // to an unrelated uppercase entry. Ownership follows the actual filename.
    private fun hasExactName(path: Path): Boolean = try {
        Files.newDirectoryStream(directoryPath).use { entries ->
            entries.any { it.fileName.toString() == path.fileName.toString() }
        }
    } catch (_: NoSuchFileException) { false }

    private fun isExpired(currentTime: Long, lastModifiedMillis: Long): Boolean {
        if (currentTime < lastModifiedMillis) return true
        val elapsed = if (lastModifiedMillis < 0 && currentTime > Long.MAX_VALUE + lastModifiedMillis) {
            Long.MAX_VALUE
        } else {
            currentTime - lastModifiedMillis
        }
        return elapsed >= maxAgeMillis
    }

    private data class CacheEntry(
        val path: Path,
        val key: String,
        val sizeBytes: Long,
        val lastModifiedMillis: Long,
    )

    private companion object {
        val OWNED_TEMP = Regex("volty-preview-tmp-v1-[0-9a-f]{64}-[0-9]+\\.tmp")
        const val KEY_LENGTH = 64
        const val PNG_SUFFIX = ".png"
        const val BUFFER_BYTES = 8 * 1024
    }
}
