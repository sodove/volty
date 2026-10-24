package ru.sodovaya.volty.data.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.nearby.community.CommunityChannel
import ru.sodovaya.volty.domain.nearby.community.CommunityDetails
import ru.sodovaya.volty.domain.nearby.community.CommunityFailure
import ru.sodovaya.volty.domain.nearby.community.CommunityResult
import ru.sodovaya.volty.domain.nearby.community.CommunitySummary
import ru.sodovaya.volty.domain.nearby.community.CommunityVisibility

/**
 * Field report 2026-10-24: with no internet the server list was empty, and the radio card lives on
 * a server's page, so the radio could not be reached at all.
 */
class CommunityOfflineCacheTest {
    private class MapStorage : CommunityCacheStorage {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    private val summary = CommunitySummary("c1", "Покатушки", "по средам", CommunityVisibility.PRIVATE, 1_700_000_000_000)
    private val detail = CommunityDetails(7, summary, "owner", listOf(CommunityChannel("ch1", "Общий", true)))

    @Test fun theServerListSurvivesTheNetwork() = runTest {
        val storage = MapStorage()
        val online = CommunityOfflineCache(storage, "u1")
        assertEquals(CommunityResult.Ready(listOf(summary)), online.memberships(null) { CommunityResult.Ready(listOf(summary)) })

        val offline = CommunityOfflineCache(storage, "u1")
        assertEquals(CommunityResult.Ready(listOf(summary)),
            offline.memberships(null) { CommunityResult.Failed(CommunityFailure.UNAVAILABLE) })
    }

    @Test fun aServerPageSurvivesTheNetwork() = runTest {
        val storage = MapStorage()
        CommunityOfflineCache(storage, "u1").detail("c1") { CommunityResult.Ready(detail) }
        assertEquals(CommunityResult.Ready(detail),
            CommunityOfflineCache(storage, "u1").detail("c1") { CommunityResult.Failed(CommunityFailure.UNAVAILABLE) })
    }

    @Test fun anExpiredTokenOfflineStillShowsTheRememberedList() = runTest {
        // Offline the token cannot be refreshed, so the port reports UNAUTHORIZED without asking anyone.
        val storage = MapStorage()
        val cache = CommunityOfflineCache(storage, "u1")
        cache.memberships(null) { CommunityResult.Ready(listOf(summary)) }
        assertEquals(CommunityResult.Ready(listOf(summary)),
            cache.memberships(null) { CommunityResult.Failed(CommunityFailure.UNAUTHORIZED) })
    }

    @Test fun nothingRememberedMeansTheFailureStands() = runTest {
        val cache = CommunityOfflineCache(MapStorage(), "u1")
        assertEquals(CommunityResult.Failed(CommunityFailure.UNAVAILABLE),
            cache.memberships(null) { CommunityResult.Failed(CommunityFailure.UNAVAILABLE) })
    }

    @Test fun aServerThatRefusesUsIsForgotten() = runTest {
        val storage = MapStorage()
        val cache = CommunityOfflineCache(storage, "u1")
        cache.detail("c1") { CommunityResult.Ready(detail) }
        assertEquals(CommunityResult.Failed(CommunityFailure.ACCESS_DENIED),
            cache.detail("c1") { CommunityResult.Failed(CommunityFailure.ACCESS_DENIED) })
        assertEquals(CommunityResult.Failed(CommunityFailure.UNAVAILABLE),
            cache.detail("c1") { CommunityResult.Failed(CommunityFailure.UNAVAILABLE) })
    }

    @Test fun onlyTheFirstPageIsRememberedAndOnlyItIsServed() = runTest {
        val storage = MapStorage()
        val cache = CommunityOfflineCache(storage, "u1")
        cache.memberships("c1") { CommunityResult.Ready(listOf(summary.copy(id = "c2"))) }
        assertEquals(CommunityResult.Failed(CommunityFailure.UNAVAILABLE),
            cache.memberships(null) { CommunityResult.Failed(CommunityFailure.UNAVAILABLE) })
        assertEquals(CommunityResult.Failed(CommunityFailure.UNAVAILABLE),
            cache.memberships("c1") { CommunityResult.Failed(CommunityFailure.UNAVAILABLE) })
    }

    @Test fun anotherAccountSeesNothing() = runTest {
        val storage = MapStorage()
        CommunityOfflineCache(storage, "u1").memberships(null) { CommunityResult.Ready(listOf(summary)) }
        assertEquals(CommunityResult.Failed(CommunityFailure.UNAVAILABLE),
            CommunityOfflineCache(storage, "u2").memberships(null) { CommunityResult.Failed(CommunityFailure.UNAVAILABLE) })
    }

    @Test fun aDamagedEntryIsAMissNotACrash() = runTest {
        val storage = MapStorage()
        val cache = CommunityOfflineCache(storage, "u1")
        cache.memberships(null) { CommunityResult.Ready(listOf(summary)) }
        storage.values.keys.forEach { storage.values[it] = "{broken" }
        assertEquals(CommunityResult.Failed(CommunityFailure.UNAVAILABLE),
            cache.memberships(null) { CommunityResult.Failed(CommunityFailure.UNAVAILABLE) })
        assertNull(storage.values.values.firstOrNull { it != "{broken" })
    }
}
