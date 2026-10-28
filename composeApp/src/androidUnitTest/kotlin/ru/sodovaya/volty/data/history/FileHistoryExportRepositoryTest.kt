package ru.sodovaya.volty.data.history

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.TimeZone
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class FileHistoryExportRepositoryTest {
    private val tick = HistoryTick(1000, 10, 1)
    private val ride = HistoryRide("ride", "vehicle", tick, tick.copy(epochMillis = 2000, elapsedMillis = 1010), HistoryRideStatus.COMPLETED)
    private val request = HistoryExportRequest(ride.favoriteSource(), HistoryRange(1000, 2000), HistoryExportFormat.LOGUI)
    private val metadata = HistoryExportMetadata(ride, request.range, emptyList(), vehicleName = "Мой EXN/2")
    private val source = object : HistoryExportSource {
        override suspend fun open(request: HistoryExportRequest) = HistoryExportInput(metadata, emptyFlow())
    }
    private suspend fun fixture(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("volty-export-test-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun publishesOnlyCompleteUtf8FileWithActualChecksumAndScopedName() = runTest {
        fixture { root ->
            val result = assertIs<HistoryExportResult.Success>(FileHistoryExportRepository(root, source,
                zone = { TimeZone.UTC }).export(request))
            // The rider's vehicle name, cleaned for a file system, then the range start in local time.
            assertEquals("volty_Мой-EXN-2_1970-01-01_00-00-01.csv", result.relativePath)
            val file = File(root, result.relativePath)
            val bytes = file.readBytes()
            assertEquals(bytes.size.toLong(), result.bytes)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, result.sha256)
            assertEquals("t_day:Time:s:3:0:1\n", bytes.toString(Charsets.UTF_8))
            assertEquals("text/csv", result.mime)
            assertEquals(listOf(result.relativePath), root.list()!!.toList())
        }
    }

    @Test fun cancellationAfterFirstFragmentRemovesTemporaryWithoutPublishing() = runTest {
        fixture { root ->
            var issued = false
            val encoder = object : HistoryExportEncoder {
                override suspend fun write(format: HistoryExportFormat, metadata: HistoryExportMetadata,
                    records: kotlinx.coroutines.flow.Flow<HistoryExportRecord>, write: suspend (String) -> Unit): HistoryExportCounts {
                    write("начало"); issued = true
                    assertTrue(root.list()!!.any { it.endsWith(".tmp") })
                    assertFalse(root.list()!!.any { it.endsWith(".csv") })
                    throw CancellationException("user dismissed")
                }
            }
            assertFailsWith<CancellationException> { FileHistoryExportRepository(root, source, encoder).export(request) }
            assertTrue(issued); assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test fun failedStreamNeverLeavesReadyFileAndPreservesForeignFiles() = runTest {
        fixture { root ->
            val keep = File(root, "original.json").apply { writeText("keep") }
            val encoder = object : HistoryExportEncoder {
                override suspend fun write(format: HistoryExportFormat, metadata: HistoryExportMetadata,
                    records: kotlinx.coroutines.flow.Flow<HistoryExportRecord>, write: suspend (String) -> Unit): HistoryExportCounts {
                    write("partial"); throw HistoryExportException(HistoryExportFailure.SOURCE_CHANGED)
                }
            }
            assertEquals(HistoryExportResult.Failed(HistoryExportFailure.SOURCE_CHANGED), FileHistoryExportRepository(root, source, encoder).export(request))
            assertEquals("keep", keep.readText()); assertEquals(listOf("original.json"), root.list()!!.toList())
        }
    }

    @Test fun utf8ByteLimitIsAppliedBeforeOversizedFragmentIsWritten() = runTest {
        fixture { root ->
            val encoder = object : HistoryExportEncoder {
                override suspend fun write(format: HistoryExportFormat, metadata: HistoryExportMetadata,
                    records: kotlinx.coroutines.flow.Flow<HistoryExportRecord>, write: suspend (String) -> Unit): HistoryExportCounts {
                    write("ёж"); return HistoryExportCounts()
                }
            }
            assertEquals(HistoryExportResult.Failed(HistoryExportFailure.LIMIT), FileHistoryExportRepository(root, source, encoder, maxFileBytes = 3).export(request))
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test fun storageFailureAfterWriteIsTypedAndLeavesNoPartial() = runTest {
        fixture { root ->
            val encoder = object : HistoryExportEncoder {
                override suspend fun write(format: HistoryExportFormat, metadata: HistoryExportMetadata,
                    records: kotlinx.coroutines.flow.Flow<HistoryExportRecord>, write: suspend (String) -> Unit): HistoryExportCounts {
                    write("partial"); throw IOException("storage full")
                }
            }
            assertEquals(HistoryExportResult.Failed(HistoryExportFailure.STORAGE), FileHistoryExportRepository(root, source, encoder).export(request))
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellationOnReturnFromIoAlsoRemovesAlreadyPublishedArtifact() = runTest {
        fixture { root ->
            lateinit var caller: Job
            var opens = 0
            var publicationObserved = false
            val capturedSource = object : HistoryExportSource {
                override suspend fun open(request: HistoryExportRequest): HistoryExportInput {
                    opens++
                    if (opens == 2) currentCoroutineContext()[Job]!!.invokeOnCompletion {
                        // The IO transaction completed, but its result has not
                        // yet resumed the caller on the UI dispatcher.
                        publicationObserved = root.list()!!.any { it.endsWith(".csv") }
                        caller.cancel()
                    }
                    return HistoryExportInput(metadata, emptyFlow())
                }
            }
            val repo = FileHistoryExportRepository(root, capturedSource, dispatcher = StandardTestDispatcher(testScheduler))
            caller = launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) { repo.export(request) }
            try {
                runCurrent()
                assertEquals(2, opens); assertTrue(publicationObserved); assertTrue(caller.isCancelled)
                assertTrue(root.list()!!.isEmpty(), "A canceled export must not leave a finished file")
            } finally { caller.cancel() }
        }
    }
}
