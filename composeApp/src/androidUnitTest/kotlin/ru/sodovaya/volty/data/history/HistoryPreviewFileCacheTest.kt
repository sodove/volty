package ru.sodovaya.volty.data.history

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryPreviewFileCacheTest {
    @Test
    fun nextWriteReclaimsOnlyOwnedTempsLeftByAnInterruptedProcess() = withTempDirectory { root ->
        val directory=File(root,"preview").apply{mkdirs()}
        val orphan=File(directory,"volty-preview-tmp-v1-$keyA-123.tmp").apply{writeBytes(ByteArray(100))}
        val foreign=File(directory,"foreign.tmp").apply{writeText("keep")}
        val nested=File(directory,"volty-preview-tmp-v1-$keyB-456.tmp").apply{mkdirs()}
        File(nested,"keep").writeText("nested")
        val cache=HistoryPreviewFileCache(directory,maxBytes=6,maxItemBytes=4)
        cache.write(keyC,byteArrayOf(1,2,3))
        assertFalse(orphan.exists());assertEquals("keep",foreign.readText());assertEquals("nested",File(nested,"keep").readText())
        assertContentEquals(byteArrayOf(1,2,3),cache.read(keyC))
    }

    @Test
    fun caseInsensitiveAliasOfAForeignFilenameIsNeverReadReplacedOrRemoved() = withTempDirectory { root ->
        val directory = File(root,"preview").apply { mkdirs() }
        val foreign = File(directory,"${"A".repeat(64)}.png").apply { writeText("foreign") }
        val aliasExists = File(directory,"$keyA.png").exists()
        val cache = HistoryPreviewFileCache(directory)
        assertNull(cache.read(keyA))
        cache.remove(keyA)
        assertEquals("foreign",foreign.readText())
        if (aliasExists) assertFailsWith<java.io.IOException> { cache.write(keyA,byteArrayOf(1)) }
        else cache.write(keyA,byteArrayOf(1))
        assertEquals("foreign",foreign.readText())
    }

    @Test
    fun bytes_round_trip_across_cache_instances_without_constructor_io() = withTempDirectory { root ->
        val directory = File(root, "preview")
        val now = 10_000L
        val bytes = byteArrayOf(0, 3, 17, -1, 42)
        val first = HistoryPreviewFileCache(directory, now = { now })

        assertFalse(directory.exists())
        first.write(keyA, bytes)
        assertContentEquals(bytes, first.read(keyA))
        assertContentEquals(bytes, HistoryPreviewFileCache(directory, now = { now }).read(keyA))
    }

    @Test
    fun replacing_an_entry_publishes_only_the_new_file_and_remove_deletes_it() = withTempDirectory { root ->
        val directory = File(root, "preview")
        val cache = HistoryPreviewFileCache(directory, now = { 20_000L })

        cache.write(keyA, byteArrayOf(1, 2))
        cache.write(keyA, byteArrayOf(7, 8, 9))

        assertContentEquals(byteArrayOf(7, 8, 9), cache.read(keyA))
        assertEquals(setOf("$keyA.png"), directory.list()?.toSet())
        cache.remove(keyA)
        assertNull(cache.read(keyA))
        assertFalse(File(directory, "$keyA.png").exists())
    }

    @Test
    fun count_limit_evicts_the_least_recently_accessed_entry() = withTempDirectory { root ->
        val directory = File(root, "preview")
        var now = 30_000L
        val cache = HistoryPreviewFileCache(directory, maxEntries = 2, now = { now })

        cache.write(keyA, byteArrayOf(1))
        now += 5_000
        cache.write(keyB, byteArrayOf(2))
        now += 5_000
        assertContentEquals(byteArrayOf(1), cache.read(keyA))
        now += 5_000
        cache.write(keyC, byteArrayOf(3))

        assertContentEquals(byteArrayOf(1), cache.read(keyA))
        assertNull(cache.read(keyB))
        assertContentEquals(byteArrayOf(3), cache.read(keyC))
    }

    @Test
    fun byte_limit_evicts_old_entries_until_the_new_item_fits() = withTempDirectory { root ->
        val directory = File(root, "preview")
        var now = 40_000L
        val cache = HistoryPreviewFileCache(directory, maxBytes = 6, maxEntries = 10, maxItemBytes = 4, now = { now })

        cache.write(keyA, byteArrayOf(1, 2, 3, 4))
        now += 5_000
        cache.write(keyB, byteArrayOf(5, 6, 7, 8))

        assertNull(cache.read(keyA))
        assertContentEquals(byteArrayOf(5, 6, 7, 8), cache.read(keyB))
    }

    @Test
    fun expired_and_future_dated_entries_are_not_returned() = withTempDirectory { root ->
        val directory = File(root, "preview")
        var now = 50_000L
        val cache = HistoryPreviewFileCache(directory, maxAgeMillis = 1_000, now = { now })

        cache.write(keyA, byteArrayOf(1))
        now += 1_001
        assertNull(cache.read(keyA))

        now = 60_000L
        cache.write(keyB, byteArrayOf(2))
        now -= 1
        assertNull(cache.read(keyB))
    }

    @Test
    fun invalid_keys_fail_before_filesystem_access_and_remove_preserves_foreign_entries() = withTempDirectory { root ->
        val directory = File(root, "preview")
        val unopened = HistoryPreviewFileCache(directory)
        val invalid = listOf("../${"a".repeat(62)}", "A".repeat(64), "a".repeat(63), "a".repeat(65))
        for (key in invalid) {
            assertFailsWith<IllegalArgumentException> { unopened.read(key) }
            assertFailsWith<IllegalArgumentException> { unopened.write(key, byteArrayOf(1)) }
            assertFailsWith<IllegalArgumentException> { unopened.remove(key) }
            assertFalse(directory.exists())
        }

        val cache = HistoryPreviewFileCache(directory)
        cache.write(keyA, byteArrayOf(1))
        val foreign = File(directory, "notes.txt").apply { writeText("keep") }
        val foreignTemp = File(directory, "foreign.tmp").apply { writeText("keep temp") }
        val uppercasePng = File(directory, "${"D".repeat(64)}.png").apply { writeText("keep png") }
        val nested = File(directory, "nested").apply { mkdirs() }
        File(nested, "data").writeText("keep nested")
        val outside = File(root, "outside.txt").apply { writeText("outside") }
        val link = File(directory, "foreign-link")
        val madeLink = try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (_: Exception) {
            false
        }

        cache.remove(keyA)

        assertEquals("keep", foreign.readText())
        assertEquals("keep temp", foreignTemp.readText())
        assertEquals("keep png", uppercasePng.readText())
        assertEquals("keep nested", File(nested, "data").readText())
        assertEquals("outside", outside.readText())
        if (madeLink) assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun empty_and_oversize_replacements_leave_the_previous_entry_intact() = withTempDirectory { root ->
        val directory = File(root, "preview")
        val cache = HistoryPreviewFileCache(directory, maxItemBytes = 4)
        val original = byteArrayOf(1, 2, 3)
        cache.write(keyA, original)

        assertFailsWith<IllegalArgumentException> { cache.write(keyA, byteArrayOf(1, 2, 3, 4, 5)) }
        assertFailsWith<IllegalArgumentException> { cache.write(keyB, byteArrayOf()) }

        assertContentEquals(original, cache.read(keyA))
        assertFalse(File(directory, "$keyB.png").exists())
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("volty-history-preview-test-").toFile()
        try {
            block(root)
        } finally {
            val canonicalRoot = root.canonicalFile
            val canonicalTemp = File(System.getProperty("java.io.tmpdir")).canonicalFile
            check(canonicalRoot.parentFile == canonicalTemp)
            check(canonicalRoot.name.startsWith("volty-history-preview-test-"))
            canonicalRoot.deleteRecursively()
        }
    }

    private companion object {
        val keyA = "a".repeat(64)
        val keyB = "b".repeat(64)
        val keyC = "c".repeat(64)
    }
}
