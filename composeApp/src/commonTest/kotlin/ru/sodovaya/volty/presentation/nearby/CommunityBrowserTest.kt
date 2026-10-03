package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityBrowserTest {
    private fun summary(id: String) = CommunitySummary(id, id, "", CommunityVisibility.PUBLIC, 1)
    private fun details(id: String) = CommunityDetails(3, summary(id), "owner", emptyList())
    private class Request<T>(val query: String = "", val after: String? = null, val limit: Int = 0) {
        val response = CompletableDeferred<CommunityResult<T>>()
        var returned = false
        suspend fun await(): CommunityResult<T> {
            val result = withContext(NonCancellable) { response.await() }
            returned = true
            return result
        }
    }
    private class Fake : CommunityPort {
        val catalogs = mutableListOf<Request<List<CommunitySummary>>>()
        val memberships = mutableListOf<Request<List<CommunitySummary>>>()
        val details = mutableListOf<Request<CommunityDetails>>()
        override suspend fun catalog(query: String, after: String?, limit: Int) =
            Request<List<CommunitySummary>>(query, after, limit).also { catalogs += it }.await()
        override suspend fun memberships(after: String?, limit: Int) =
            Request<List<CommunitySummary>>(after = after, limit = limit).also { memberships += it }.await()
        override suspend fun detail(community: String) = Request<CommunityDetails>(community).also { details += it }.await()
        override suspend fun create(community: String, name: String, description: String, visibility: CommunityVisibility): CommunityResult<CommunityDetails> = error("Browsing must not create")
        override suspend fun joinPublic(community: String): CommunityResult<CommunityDetails> = error("Browsing must not join")
    }

    @Test fun olderSearchSurvivingCancellationCannotReplaceCurrentQuery() = runTest {
        val port = Fake(); val browser = CommunityBrowser(this, port, 2)
        browser.search("old"); runCurrent(); browser.search("new"); runCurrent()
        assertEquals(listOf("old", "new"), port.catalogs.map { it.query })
        port.catalogs[1].response.complete(CommunityResult.Ready(listOf(summary("b")))); runCurrent()
        port.catalogs[0].response.complete(CommunityResult.Ready(listOf(summary("a")))); runCurrent()
        assertTrue(port.catalogs[0].returned, "Old response reached the request continuation after cancellation")
        assertEquals("new", browser.state.value.query)
        assertEquals(listOf("b"), browser.state.value.catalog.items?.map { it.id })
        browser.close()
    }
    @Test fun selectionChangeClearsOldDetailsAndRejectsOldReply() = runTest {
        val port = Fake(); val browser = CommunityBrowser(this, port)
        browser.openCommunity("a"); runCurrent(); browser.openCommunity("b"); runCurrent()
        assertEquals(listOf("a", "b"), port.details.map { it.query })
        assertNull(browser.state.value.detail)
        port.details[0].response.complete(CommunityResult.Ready(details("a"))); runCurrent()
        assertTrue(port.details[0].returned)
        assertNull(browser.state.value.detail); assertTrue(browser.state.value.detailLoading)
        port.details[1].response.complete(CommunityResult.Ready(details("b"))); runCurrent()
        assertEquals("b", browser.state.value.detail?.community?.id)
        browser.clearSelection(); assertNull(browser.state.value.detail); assertNull(browser.state.value.selectedCommunity)
        browser.close()
    }
    @Test fun paginationUsesAcceptedCursorAndDoesNotDuplicateRequests() = runTest {
        val port = Fake(); val browser = CommunityBrowser(this, port, 2)
        assertNull(browser.state.value.catalog.items)
        browser.search("ride"); runCurrent()
        port.catalogs.single().response.complete(CommunityResult.Ready(listOf(summary("a"), summary("b")))); runCurrent()
        browser.loadMoreCatalog(); browser.loadMoreCatalog(); runCurrent()
        assertEquals(2, port.catalogs.size); assertEquals("b", port.catalogs.last().after); assertEquals(2, port.catalogs.last().limit)
        port.catalogs.last().response.complete(CommunityResult.Ready(listOf(summary("c")))); runCurrent()
        assertEquals(listOf("a", "b", "c"), browser.state.value.catalog.items?.map { it.id })
        assertFalse(browser.state.value.catalog.hasMore)
        browser.loadMoreCatalog(); runCurrent(); assertEquals(2, port.catalogs.size)
        browser.close()
    }
    @Test fun failedMorePreservesRowsAndRetriesSameCursor() = runTest {
        val port = Fake(); val browser = CommunityBrowser(this, port, 2)
        browser.refreshMemberships(); runCurrent()
        port.memberships.single().response.complete(CommunityResult.Ready(listOf(summary("a"), summary("b")))); runCurrent()
        browser.loadMoreMemberships(); runCurrent()
        port.memberships.last().response.complete(CommunityResult.Failed(CommunityFailure.UNAVAILABLE)); runCurrent()
        assertEquals(listOf("a", "b"), browser.state.value.memberships.items?.map { it.id })
        assertEquals(CommunityFailure.UNAVAILABLE, browser.state.value.memberships.error)
        browser.loadMoreMemberships(); runCurrent(); assertEquals(listOf(null, "b", "b"), port.memberships.map { it.after })
        port.memberships.last().response.complete(CommunityResult.Ready(emptyList())); runCurrent()
        assertNull(browser.state.value.memberships.error); assertFalse(browser.state.value.memberships.hasMore)
        browser.close()
    }
    @Test fun refreshResetsCursorAndRetiresOldPagination() = runTest {
        val port = Fake(); val browser = CommunityBrowser(this, port, 2)
        browser.refreshMemberships(); runCurrent()
        port.memberships.single().response.complete(CommunityResult.Ready(listOf(summary("a"), summary("b")))); runCurrent()
        browser.loadMoreMemberships(); runCurrent(); browser.refreshMemberships(); runCurrent()
        assertEquals(listOf(null, "b", null), port.memberships.map { it.after })
        assertNull(browser.state.value.memberships.items)
        port.memberships[2].response.complete(CommunityResult.Ready(emptyList())); runCurrent()
        port.memberships[1].response.complete(CommunityResult.Ready(listOf(summary("c")))); runCurrent()
        assertTrue(port.memberships[1].returned)
        assertEquals(emptyList(), browser.state.value.memberships.items)
        browser.close()
    }
    @Test fun closingOwnerClearsAllDataAndRejectsLateCompletionsAndCommands() = runTest {
        val port = Fake(); val browser = CommunityBrowser(this, port)
        browser.search("private query"); browser.refreshMemberships(); browser.openCommunity("a"); runCurrent()
        browser.close()
        port.catalogs.single().response.complete(CommunityResult.Ready(listOf(summary("a"))))
        port.memberships.single().response.complete(CommunityResult.Ready(listOf(summary("b"))))
        port.details.single().response.complete(CommunityResult.Ready(details("a"))); runCurrent()
        assertTrue(port.catalogs.single().returned); assertTrue(port.memberships.single().returned); assertTrue(port.details.single().returned)
        assertEquals(CommunityBrowserState(), browser.state.value)
        browser.search("new"); browser.refreshMemberships(); browser.openCommunity("b"); runCurrent()
        assertEquals(1, port.catalogs.size); assertEquals(1, port.memberships.size); assertEquals(1, port.details.size)
    }
    @Test fun independentPagesAndWrongDetailDoNotFabricateMembership() = runTest {
        val port = Fake(); val browser = CommunityBrowser(this, port)
        browser.search("a"); browser.refreshMemberships(); browser.openCommunity("a"); runCurrent()
        port.catalogs.single().response.complete(CommunityResult.Ready(listOf(summary("a"))))
        port.memberships.single().response.complete(CommunityResult.Ready(emptyList()))
        port.details.single().response.complete(CommunityResult.Ready(details("foreign"))); runCurrent()
        assertEquals(listOf("a"), browser.state.value.catalog.items?.map { it.id })
        assertEquals(emptyList(), browser.state.value.memberships.items)
        assertNull(browser.state.value.detail); assertEquals(CommunityFailure.INVALID_RESPONSE, browser.state.value.detailError)
        browser.close()
    }
    @Test fun failedInitialReadStaysUnknownAndExplicitRefreshCanRetry() = runTest {
        val port = Fake(); val browser = CommunityBrowser(this, port)
        browser.refreshMemberships(); runCurrent()
        port.memberships.single().response.completeExceptionally(IllegalStateException("bearer-secret")); runCurrent()
        assertNull(browser.state.value.memberships.items)
        assertEquals(CommunityFailure.UNAVAILABLE, browser.state.value.memberships.error)
        assertFalse(browser.state.value.memberships.loading)
        browser.refreshMemberships(); runCurrent(); assertEquals(2, port.memberships.size)
        port.memberships.last().response.complete(CommunityResult.Ready(emptyList())); runCurrent()
        assertEquals(emptyList(), browser.state.value.memberships.items); assertNull(browser.state.value.memberships.error)
        browser.close()
    }
}
