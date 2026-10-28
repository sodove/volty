package ru.sodovaya.volty.presentation.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryExportComponentTest {
    private val tick = HistoryTick(1000, 10, 1)
    private val source = HistoryFavoriteSource("ride", "bike", HistoryStorageKind.V2, tick)
    private val range = HistoryRange(1000, 2000)
    private val success = HistoryExportResult.Success("ready.json", 100, "digest", "application/json")
    private fun TestScope.owner(repository: HistoryExportRepository) = HistoryExportComponent(
        DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, repository)

    @Test fun onlyExplicitChoiceExportsPinnedSourceAndRangeAndDuplicateTapIsIgnored() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<HistoryExportRequest>()
        val c = owner(object : HistoryExportRepository {
            override suspend fun export(request: HistoryExportRequest): HistoryExportResult {
                requests += request; entered.complete(Unit); release.await(); return success
            }
        })
        try {
            c.open(source, range); runCurrent(); assertTrue(requests.isEmpty())
            c.start(HistoryExportFormat.LOGUI); c.start(HistoryExportFormat.CSV); runCurrent()
            assertTrue(entered.isCompleted); assertTrue(c.state.value.busy)
            assertEquals(listOf(HistoryExportRequest(source, range, HistoryExportFormat.LOGUI)), requests)
            release.complete(Unit); runCurrent(); assertEquals(success, c.state.value.ready); assertFalse(c.state.value.busy)
        } finally { release.complete(Unit); c.close() }
    }
    @Test fun dismissCancelsAndLateResultCannotReplaceNewerSelection() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val c = owner(object : HistoryExportRepository {
            override suspend fun export(request: HistoryExportRequest): HistoryExportResult = withContext(NonCancellable) {
                entered.complete(Unit); release.await(); success
            }
        })
        try {
            c.open(source, range); c.start(HistoryExportFormat.LOGUI); runCurrent(); assertTrue(entered.isCompleted)
            c.dismiss(); assertNull(c.state.value.source); assertFalse(c.state.value.busy)
            val newer = source.copy(rideId = "other"); c.open(newer, range)
            release.complete(Unit); runCurrent()
            assertEquals(newer, c.state.value.source); assertNull(c.state.value.ready); assertFalse(c.state.value.busy)
        } finally { release.complete(Unit); c.close(); runCurrent() }
    }
    @Test fun typedFailureCanRetryAndClosingBeforeDispatchDoesNotIssueWork() = runTest {
        var calls = 0
        val repo = object : HistoryExportRepository {
            override suspend fun export(request: HistoryExportRequest): HistoryExportResult {
                calls++; return if (calls == 1) HistoryExportResult.Failed(HistoryExportFailure.NO_ROUTE) else success
            }
        }
        val c = owner(repo)
        try {
            c.open(source, range); c.start(HistoryExportFormat.GPX); runCurrent()
            assertEquals(HistoryExportFailure.NO_ROUTE, c.state.value.error); assertFalse(c.state.value.busy)
            c.start(HistoryExportFormat.LOGUI); runCurrent(); assertEquals(success, c.state.value.ready); assertEquals(2, calls)
            c.open(source, range); c.start(HistoryExportFormat.CSV); c.close(); runCurrent(); assertEquals(2, calls)
        } finally { c.close() }
    }
}
