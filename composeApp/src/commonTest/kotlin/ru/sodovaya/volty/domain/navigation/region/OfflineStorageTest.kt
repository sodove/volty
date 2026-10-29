package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineStorageTest {
    @Test
    fun manifest_bytes_are_printed_in_decimal_megabytes_like_the_system_does() {
        // The live g1-146-240 download and installed sizes.
        assertEquals(OfflineByteSize(104.0, OfflineByteUnit.MB), OfflineByteFormat.size(103_993_989L))
        assertEquals(OfflineByteSize(215.2, OfflineByteUnit.MB), OfflineByteFormat.size(215_236_242L))
    }

    @Test
    fun unit_boundaries() {
        assertEquals(OfflineByteSize(999.0, OfflineByteUnit.B), OfflineByteFormat.size(999))
        assertEquals(OfflineByteSize(1.0, OfflineByteUnit.KB), OfflineByteFormat.size(1_000))
        assertEquals(OfflineByteSize(1.5, OfflineByteUnit.GB), OfflineByteFormat.size(1_500_000_000))
        assertEquals(OfflineByteSize(0.0, OfflineByteUnit.B), OfflineByteFormat.size(-5))
    }

    @Test
    fun total_is_the_sum_of_what_was_measured() {
        assertEquals(10L, OfflineStorageUsage(1, 2, 3, 4).totalBytes)
    }

    @Test
    fun staging_of_an_already_installed_release_is_stale() {
        assertTrue(OfflineDownloadStagingPolicy.isStale("g1-146-240-r1", 1, mapOf("g1-146-240" to "r1"), emptySet()))
        assertFalse(OfflineDownloadStagingPolicy.isStale("g1-146-240-r2", 1, mapOf("g1-146-240" to "r1"), emptySet()))
    }

    @Test
    fun an_abandoned_download_is_stale_but_a_recent_resumable_one_is_not() {
        val week = OfflineDownloadStagingPolicy.ABANDONED_AFTER_MILLIS
        assertTrue(OfflineDownloadStagingPolicy.isStale("g1-1-1-r1", week + 1, emptyMap(), emptySet()))
        assertFalse(OfflineDownloadStagingPolicy.isStale("g1-1-1-r1", week, emptyMap(), emptySet()))
    }

    @Test
    fun an_active_download_is_never_reaped_even_when_old_or_installed() {
        // Incoherent on purpose: installed AND ancient AND active.
        assertFalse(
            OfflineDownloadStagingPolicy.isStale(
                "g1-1-1-r1", Long.MAX_VALUE, mapOf("g1-1-1" to "r1"), setOf("g1-1-1"),
            ),
        )
    }

    @Test
    fun ambient_cache_cap_is_explicit_and_below_the_sdk_default() {
        assertTrue(OfflineMapCachePolicy.AMBIENT_CACHE_BYTES in 1..(50L * 1024 * 1024))
    }
}
