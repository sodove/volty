package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.*

class HistoryMediaFilesTest {
    private val id = "aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb"
    private fun fixture(block: (File, HistoryMediaFiles) -> Unit) {
        val root = Files.createTempDirectory("history-media-files-").toFile()
        try { block(root, HistoryMediaFiles(File(root, "media"), 8)) }
        finally { root.deleteRecursively() }
    }
    @Test fun stagedCopyHasExactHashAndCannotBeReadBeforeExplicitPublish() = fixture { root, files ->
        val bytes = "abc".encodeToByteArray()
        var closed = false; var inspected = false
        val staged = files.stage(id, { object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } } }, {},
            { file -> assertContentEquals(bytes, file.readBytes()); inspected = true })
        assertTrue(closed); assertTrue(inspected); assertEquals(3, staged.bytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", staged.sha256)
        assertNull(files.verifiedFile(id, staged.bytes, staged.sha256))
        files.publish(id)
        assertContentEquals(bytes, assertNotNull(files.verifiedFile(id, staged.bytes, staged.sha256)).readBytes())
        assertFalse(File(root, "media/$id.import").exists())
        assertEquals(setOf("$id.media"), files.ownedFiles())
    }
    @Test fun oversizeOrCorruptCopyClosesInputAndLeavesNoPublishedOrTemporaryFile() = fixture { _, files ->
        var closed = false
        assertFailsWith<IOException> { files.stage(id,
            { object : ByteArrayInputStream(ByteArray(9)) { override fun close() { closed = true; super.close() } } }, {}, {}) }
        assertTrue(closed); assertTrue(files.ownedFiles().isEmpty())
        assertFailsWith<IOException> { files.stage(id, { ByteArrayInputStream(byteArrayOf(1, 2)) }, {}, { throw IOException("corrupt") }) }
        assertTrue(files.ownedFiles().isEmpty())
    }
    @Test fun cancellationDuringCopyIsPreservedAndCleansTheOwnedTemporaryFile() = fixture { _, files ->
        var checks = 0; var closed = false
        val cancel = CancellationException("test cancellation")
        val thrown = assertFailsWith<CancellationException> {
            files.stage(id, { object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) { override fun close() { closed = true; super.close() } } },
                { if (++checks == 3) throw cancel }, {})
        }
        assertSame(cancel, thrown); assertTrue(closed); assertTrue(files.ownedFiles().isEmpty())
    }
    @Test fun truncatedOrChangedPublishedFileIsNeverReturnedAsVerified() = fixture { root, files ->
        val staged = files.stage(id, { ByteArrayInputStream("abc".encodeToByteArray()) }, {}, {})
        files.publish(id)
        val file = File(root, "media/$id.media")
        file.writeText("xyz"); assertNull(files.verifiedFile(id, staged.bytes, staged.sha256))
        file.writeText("a"); assertNull(files.verifiedFile(id, staged.bytes, staged.sha256))
    }
    @Test fun traversalAndExistingPublishedFileCannotBeOverwrittenAndForeignFilesRemain() = fixture { root, files ->
        for (bad in listOf("../outside", "ABC", "$id/../x")) assertFailsWith<IllegalArgumentException> {
            files.stage(bad, { error("must not open") }, {}, {}) }
        val staged = files.stage(id, { ByteArrayInputStream("abc".encodeToByteArray()) }, {}, {})
        files.publish(id)
        assertFailsWith<IOException> { files.stage(id, { ByteArrayInputStream("new".encodeToByteArray()) }, {}, {}) }
        val foreign = File(root, "media/foreign.txt").apply { writeText("keep") }
        val nested = File(root, "media/$id.import").apply { mkdirs() }
        val child = File(nested, "keep").apply { writeText("nested") }
        files.remove(id)
        assertEquals("keep", foreign.readText()); assertEquals("nested", child.readText())
        assertNull(files.verifiedFile(id, staged.bytes, staged.sha256))
    }
    @Test fun emptyOrStalledSourceFailsInsteadOfPublishingOrSpinning() = fixture { _, files ->
        assertFailsWith<IOException> { files.stage(id, { ByteArrayInputStream(byteArrayOf()) }, {}, {}) }
        var reads = 0
        assertFailsWith<IOException> { files.stage(id, { object : InputStream() {
            override fun read(): Int = error("bulk reads required")
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (++reads > 8) error("unbounded read loop"); return 0 }
        } }, {}, {}) }
        assertTrue(reads in 1..8); assertTrue(files.ownedFiles().isEmpty())
    }
}
