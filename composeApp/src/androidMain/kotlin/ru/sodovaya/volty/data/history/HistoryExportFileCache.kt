package ru.sodovaya.volty.data.history

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Disposable files only. Caller serializes with all preview/export publication. */
class HistoryExportFileCache(private val directory: File,
    private val maxBytes: Long = 256L * 1024 * 1024, private val maxEntries: Int = 32,
    private val maxAgeMillis: Long = 24L * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis) {
    init { require(maxBytes > 0 && maxEntries > 0 && maxAgeMillis > 0) }
    fun prepare(reserveBytes: Long, reserveEntries: Int = 1) {
        require(reserveBytes in 0..maxBytes && reserveEntries in 0..maxEntries)
        val root = directory.toPath()
        Files.createDirectories(root)
        requireDirectory(root)
        val retained = mutableListOf<Pair<Path, BasicFileAttributes>>()
        val at = now()
        Files.newDirectoryStream(root).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (!READY.matches(name) && !TEMP.matches(name)) continue
                val attributes = Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attributes.isRegularFile || attributes.isSymbolicLink) continue
                val modified = attributes.lastModifiedTime().toMillis()
                if (TEMP.matches(name) || attributes.size() <= 0 || attributes.size() > maxBytes ||
                    (at >= modified && at - modified >= maxAgeMillis)) Files.deleteIfExists(entry)
                else retained += entry to attributes
            }
        }
        // Keep newest artifacts within the budget left for the in-flight file.
        var bytes = 0L; var count = 0
        for ((path, attributes) in retained.sortedByDescending { it.second.lastModifiedTime() }) {
            val size = attributes.size()
            if (count >= maxEntries - reserveEntries || size > maxBytes - reserveBytes - bytes) Files.deleteIfExists(path)
            else { bytes += size; count++ }
        }
    }
    fun clearOwned() {
        val root = directory.toPath()
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        requireDirectory(root)
        Files.newDirectoryStream(root).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if ((READY.matches(name) || TEMP.matches(name)) && Files.isRegularFile(entry, NOFOLLOW_LINKS))
                    Files.deleteIfExists(entry)
            }
        }
    }
    private fun requireDirectory(root: Path) {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw IOException("Export cache is not a directory")
    }
    companion object {
        /** [historyExportFileName]: `volty_<vehicle>_<yyyy-MM-dd_HH-mm-ss>[_records].<ext>`. */
        private val NAMED = Regex("volty_[\\p{L}\\p{N}_-]{1,40}_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(_records)?\\.(gpx|csv)")
        /** Names written before 0.7.59, including the retired `.json`; still swept from the cache. */
        private val LEGACY = Regex("volty-ride-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.(gpx|csv|json)")
        private val READY = Regex("(?:${NAMED.pattern})|(?:${LEGACY.pattern})")
        /** Only a current export name may be shared or saved. */
        fun isReadyName(name: String): Boolean = NAMED.matches(name)
        private val TEMP = Regex("volty-export-tmp-v1-[a-zA-Z0-9-]+\\.tmp")
    }
}
