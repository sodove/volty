package ru.sodovaya.volty.domain.navigation.region

import kotlin.math.roundToLong

enum class OfflineByteUnit { B, KB, MB, GB }

/** A size ready to print: [value] already scaled to [unit]. */
data class OfflineByteSize(val value: Double, val unit: OfflineByteUnit)

/**
 * Decimal units (1 MB = 1,000,000 B), the same arithmetic as Android's own
 * storage screen.  Retraction: the Settings screen used to divide by 1024 and
 * print "MB", so 103,993,989 B read as "99.2 MB" while the system reported
 * 104 MB for the same bytes.  Label and arithmetic now agree.
 */
object OfflineByteFormat {
    fun size(bytes: Long): OfflineByteSize {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b >= 1_000_000_000L -> OfflineByteSize(tenth(b / 1e9), OfflineByteUnit.GB)
            b >= 1_000_000L -> OfflineByteSize(tenth(b / 1e6), OfflineByteUnit.MB)
            b >= 1_000L -> OfflineByteSize(tenth(b / 1e3), OfflineByteUnit.KB)
            else -> OfflineByteSize(b.toDouble(), OfflineByteUnit.B)
        }
    }

    private fun tenth(value: Double): Double = (value * 10.0).roundToLong() / 10.0
}

/** Bytes measured on disk (file lengths), not figures read from a manifest. */
data class OfflineStorageUsage(
    /** Routing and search of installed regions. */
    val packagesBytes: Long = 0L,
    /** MapLibre offline packs plus its shared tile cache (mbgl-offline.db). */
    val mapBytes: Long = 0L,
    /** Resumable download staging. */
    val downloadsBytes: Long = 0L,
    /** Leftovers of retired storage layouts. */
    val legacyBytes: Long = 0L,
) {
    val totalBytes: Long get() = packagesBytes + mapBytes + downloadsBytes + legacyBytes
}

interface OfflineStorageMeter {
    suspend fun measure(): OfflineStorageUsage
}

/** Decides which `downloads/<region>-<release>` staging directories are dead weight. */
object OfflineDownloadStagingPolicy {
    const val ABANDONED_AFTER_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    fun isStale(
        directoryName: String,
        ageMillis: Long,
        installedReleases: Map<String, String>,
        activeRegionIds: Set<String>,
    ): Boolean {
        if (activeRegionIds.any { directoryName.startsWith("$it-") }) return false
        if (installedReleases.any { (region, release) -> directoryName == "$region-$release" }) return true
        return ageMillis > ABANDONED_AFTER_MILLIS
    }
}

/**
 * MapLibre's shared "ambient" tile cache is capped explicitly instead of
 * inheriting the SDK default.  Offline packs are exempt from the cap, so it only
 * bounds what browsing outside a downloaded cell leaves behind (and the history
 * preview renderer feeds it too).  32 MiB holds a few hundred vector tiles - enough
 * for a ride's worth of re-panning - and is below the 50 MiB default.
 */
object OfflineMapCachePolicy {
    const val AMBIENT_CACHE_BYTES: Long = 32L * 1024 * 1024
}
