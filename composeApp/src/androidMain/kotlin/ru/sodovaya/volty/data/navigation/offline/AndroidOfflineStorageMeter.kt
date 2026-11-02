package ru.sodovaya.volty.data.navigation.offline

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.navigation.region.OfflineStorageMeter
import ru.sodovaya.volty.domain.navigation.region.OfflineStorageUsage

/**
 * Measures what the offline feature really occupies by summing file lengths,
 * including MapLibre's database, which the signed manifests know nothing about.
 *
 * The map figure is the whole MapLibre database: offline packs PLUS its ambient tile
 * cache, which MapLibre 13.0.2 budgets together (packs count against the ambient cap).
 * The file is a SQLite database that is never vacuumed, so it does not shrink when a
 * pack is deleted and no cap setting would reclaim it. Retraction: an earlier revision
 * of this change set a 32 MiB ambient cap and presented it as a storage fix; it was not.
 *
 * [legacyRoutingUnused] says the retired BRouter store (`offline-routing/`) can no
 * longer be read: release builds always run the Valhalla path
 * (VOLTY_OFFLINE_RUNTIME_ENABLED is hard-wired true there), so the store is dead
 * weight and [reclaimUnusedLegacy] removes it.  A debug build with the BRouter
 * fallback keeps it.
 */
class AndroidOfflineStorageMeter(
    context: Context,
    private val legacyRoutingUnused: Boolean,
) : OfflineStorageMeter {
    private val app = context.applicationContext

    override suspend fun measure(): OfflineStorageUsage = withContext(Dispatchers.IO) {
        val files = app.filesDir
        val root = File(files, "offline-regions")
        OfflineStorageUsage(
            packagesBytes = size(File(root, "packages")),
            downloadsBytes = size(File(root, "downloads")),
            mapBytes = mapLibreFiles().sumOf(File::length),
            legacyBytes = size(File(files, LEGACY_ROUTING_DIRECTORY)),
        )
    }

    /** Deletes the dead BRouter store; returns the bytes freed. */
    suspend fun reclaimUnusedLegacy(): Long = withContext(Dispatchers.IO) {
        if (!legacyRoutingUnused) return@withContext 0L
        val directory = File(app.filesDir, LEGACY_ROUTING_DIRECTORY)
        val bytes = size(directory)
        if (directory.deleteRecursively()) bytes else 0L
    }

    /** `mbgl-offline.db` and its journal/wal siblings; the SDK picks the directory, so look in all app-private roots. */
    private fun mapLibreFiles(): List<File> = listOfNotNull(
        app.filesDir, app.cacheDir, app.noBackupFilesDir, app.getExternalFilesDir(null), app.externalCacheDir,
    ).flatMap { directory ->
        directory.listFiles { file -> file.isFile && file.name.startsWith(MAPLIBRE_DATABASE) }?.toList().orEmpty()
    }

    private fun size(file: File): Long =
        if (!file.exists()) 0L else file.walkTopDown().filter(File::isFile).sumOf(File::length)

    private companion object {
        const val LEGACY_ROUTING_DIRECTORY = "offline-routing"
        const val MAPLIBRE_DATABASE = "mbgl-offline.db"
    }
}
