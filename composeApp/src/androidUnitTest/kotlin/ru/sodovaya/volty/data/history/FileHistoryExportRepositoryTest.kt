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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class FileHistoryExportRepositoryTest {
    private val tick = HistoryTick(1000, 10, 1)
    private val ride = HistoryRide("ride", "vehicle", tick, tick.copy(epochMillis = 2000, elapsedMillis = 1010), HistoryRideStatus.COMPLETED)
    private val request = HistoryExportRequest(ride.favoriteSource(), HistoryRange(1000, 2000), HistoryExportFormat.JSON)
    private val metadata = HistoryExportMetadata(ride, request.range, emptyList())
    private val source = object : HistoryExportSource {
        override suspend fun open(request: HistoryExportRequest) = HistoryExportInput(metadata, emptyFlow())
    }
    private suspend fun fixture(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("volty-export-test-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun publishesOnlyCompleteUtf8FileWithActualChecksumAndScopedName() = runTest {
        fixture { root ->
            val result = assertIs<HistoryExportResult.Success>(FileHistoryExportRepository(root, source).export(request))
            assertTrue(Regex("volty-ride-[a-f0-9-]{36}\\.json").matches(result.relativePath))
            val file = File(root, result.relativePath)
            val bytes = file.readBytes()
            assertEquals(bytes.size.toLong(), result.bytes)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, result.sha256)
            assertTrue(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.containsKey("schemaVersion"))
            assertEquals("application/json", result.mime)
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
                    assertFalse(root.list()!!.any { it.endsWith(".json") })
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
                        publicationObserved = root.list()!!.any { it.endsWith(".json") }
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
