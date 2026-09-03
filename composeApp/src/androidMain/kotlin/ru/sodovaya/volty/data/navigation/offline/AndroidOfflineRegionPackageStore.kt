package ru.sodovaya.volty.data.navigation.offline

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionComponent
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadPlan
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageManifest
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageManifestCodec
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageManifestPolicy
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifestVerifier

/**
 * Owns the installed routing/search/map files for regional releases.
 *
 * A release is extracted into a private staging directory first. The active
 * pointer is the only mutable reference consumed by the app, so an interrupted
 * extraction or process death cannot expose half a Valhalla database to the
 * router or half a SQLite file to autocomplete.
 */
class AndroidOfflineRegionPackageStore(
    context: android.content.Context,
    private val currentAppVersionCode: Int,
    private val manifestVerifier: OfflineRegionManifestVerifier,
) {
    private val root = File(context.applicationContext.filesDir, STORAGE_DIRECTORY)
    private val packages = File(root, PACKAGES_DIRECTORY)
    private val active = File(root, ACTIVE_DIRECTORY)
    private val lock = Any()

    init {
        synchronized(lock) {
            ensureDirectories()
            cleanupStaging()
            cleanupOrphanedPackages()
        }
    }

    fun install(
        manifest: OfflineRegionPackageManifest,
        plan: OfflineRegionDownloadPlan,
        downloadedArtifacts: Map<OfflineRegionComponent, File>,
    ): InstalledOfflineRegion = synchronized(lock) {
        require(manifest.regionId == plan.regionId) { "manifest and plan region differ" }
        require(manifest.releaseVersion == plan.releaseVersion) { "manifest and plan release differ" }
        require(
            OfflineRegionPackageManifestPolicy.validate(manifest, currentAppVersionCode).isEmpty(),
        ) { "regional release manifest is incompatible with this app" }
        require(manifestVerifier.verify(manifest)) { "regional release signature is invalid" }
        require(downloadedArtifacts.keys == OfflineRegionComponent.entries.toSet()) {
            "a regional package must provide exactly three downloaded artifacts"
        }
        downloadedArtifacts.forEach { (component, file) ->
            val expected = plan.artifacts.single { it.component == component }
            validateDownloadedArtifact(file, expected.sha256, expected.downloadBytes, component)
        }

        val staging = File(root, "staging-${manifest.regionId}-${UUID.randomUUID()}")
        val publishedName = "${manifest.releaseVersion}-${UUID.randomUUID()}"
        val published = File(File(packages, manifest.regionId), publishedName)
        var publishedPackage = false
        try {
            if (!staging.mkdirs()) throw IOException("Could not create regional package staging directory")
            extractPackage(downloadedArtifacts, staging)
            writeSyncedText(
                File(staging, MANIFEST_FILE),
                OfflineRegionPackageManifestCodec.encode(manifest),
            )
            verifyInstalledPackage(staging, manifest)

            if (!published.parentFile.mkdirs() && !published.parentFile.isDirectory) {
                throw IOException("Could not create regional package directory")
            }
            moveAtomically(staging, published)
            publishedPackage = true
            // The runtime path is only known after the directory is published.
            // Rewrite it before the active pointer changes, so readers still see
            // the previous package if this step fails.
            rewriteValhallaPaths(File(published, ROUTING_DIRECTORY), published)
            verifyInstalledPackage(published, manifest)
            publishPointer(manifest.regionId, published.name)
            cleanupOrphanedPackages()
            InstalledOfflineRegion.fromDirectory(published, manifest)
        } finally {
            staging.deleteRecursively()
            val activeName = File(active, "${manifest.regionId}.pointer")
                .takeIf(File::isFile)
                ?.let { runCatching { it.readText(Charsets.UTF_8).trim() }.getOrNull() }
            if (!publishedPackage || activeName != published.name) published.deleteRecursively()
        }
    }

    fun active(regionId: String): InstalledOfflineRegion? = synchronized(lock) {
        require(REGION_ID_PATTERN.matches(regionId)) { "invalid region id" }
        val pointer = File(active, "$regionId.pointer")
        if (!pointer.isFile) return@synchronized null
        val packageName = runCatching { pointer.readText(Charsets.UTF_8).trim() }.getOrNull()
            ?: return@synchronized null
        if (!PACKAGE_NAME_PATTERN.matches(packageName)) return@synchronized null
        val directory = File(File(packages, regionId), packageName)
        if (!isChildOf(directory, File(packages, regionId))) return@synchronized null
        val manifest = readManifest(directory) ?: return@synchronized null
        if (manifest.regionId != regionId ||
            manifest.compatibility.minAppVersionCode > currentAppVersionCode
        ) return@synchronized null
        runCatching { verifyInstalledPackage(directory, manifest) }
            .getOrNull()
            ?.let { InstalledOfflineRegion.fromDirectory(directory, manifest) }
    }

    fun installedRegionIds(): List<String> = synchronized(lock) {
        active.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".pointer") }
            ?.map { it.name.removeSuffix(".pointer") }
            ?.filter { it.isNotBlank() }
            ?.sorted()
            .orEmpty()
    }

    /**
     * Returns verified active packages so offline mode can recover before the
     * network catalog has been refreshed after a process restart.
     */
    fun installedRegions(): List<InstalledOfflineRegion> =
        installedRegionIds().mapNotNull(::active)

    fun delete(regionId: String) = synchronized(lock) {
        require(REGION_ID_PATTERN.matches(regionId)) { "invalid region id" }
        File(active, "$regionId.pointer").delete()
        File(packages, regionId).deleteRecursively()
    }

    /** Creates a private, resumable download directory outside the active package tree. */
    fun createDownloadStaging(regionId: String, releaseVersion: String): File = synchronized(lock) {
        require(REGION_ID_PATTERN.matches(regionId)) { "invalid region id" }
        require(RELEASE_VERSION_PATTERN.matches(releaseVersion)) { "invalid release version" }
        val directory = File(File(root, DOWNLOADS_DIRECTORY), "$regionId-$releaseVersion")
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create regional download staging directory")
        }
        directory
    }

    /** Returns existing resumable state without creating a directory on a read/refresh path. */
    fun existingDownloadStaging(regionId: String, releaseVersion: String): File? = synchronized(lock) {
        if (!REGION_ID_PATTERN.matches(regionId) || !RELEASE_VERSION_PATTERN.matches(releaseVersion)) {
            return@synchronized null
        }
        File(File(root, DOWNLOADS_DIRECTORY), "$regionId-$releaseVersion")
            .takeIf { it.isDirectory && isChildOf(it, File(root, DOWNLOADS_DIRECTORY)) }
    }

    fun discardDownloadStaging(directory: File) = synchronized(lock) {
        if (isChildOf(directory, File(root, DOWNLOADS_DIRECTORY))) {
            directory.deleteRecursively()
        }
    }

    fun stagedDownloadBytes(
        directory: File,
        plan: OfflineRegionDownloadPlan,
    ): Long = synchronized(lock) {
        if (!isChildOf(directory, File(root, DOWNLOADS_DIRECTORY))) return@synchronized 0L
        plan.artifacts.sumOf { artifact ->
            val target = File(directory, artifact.relativePath)
            val part = File(target.parentFile, "${target.name}.part")
            val bytes = when {
                target.isFile -> target.length()
                part.isFile -> part.length()
                else -> 0L
            }
            bytes.coerceIn(0L, artifact.downloadBytes)
        }
    }

    private fun extractPackage(
        artifacts: Map<OfflineRegionComponent, File>,
        staging: File,
    ) {
        val routingDirectory = File(staging, ROUTING_DIRECTORY)
        val searchDirectory = File(staging, SEARCH_DIRECTORY)
        val mapDirectory = File(staging, MAP_DIRECTORY)
        if (!routingDirectory.mkdirs() || !searchDirectory.mkdirs() || !mapDirectory.mkdirs()) {
            throw IOException("Could not create regional component directories")
        }
        extractGzipTar(
            source = requireNotNull(artifacts[OfflineRegionComponent.ROUTING]),
            destination = routingDirectory,
        )
        gunzip(
            source = requireNotNull(artifacts[OfflineRegionComponent.SEARCH]),
            destination = File(searchDirectory, SEARCH_DATABASE_FILE),
        )
        copyFile(
            source = requireNotNull(artifacts[OfflineRegionComponent.MAP]),
            destination = File(mapDirectory, MAP_FILE),
        )
    }

    private fun rewriteValhallaPaths(routingDirectory: File, packageDirectory: File) {
        val config = File(routingDirectory, VALHALLA_CONFIG_FILE)
        if (!config.isFile) throw IOException("Regional routing archive has no valhalla config")
        val rootPath = packageDirectory.canonicalPath.replace(File.separatorChar, '/')
        val rewritten = config.readText(Charsets.UTF_8)
            .replace("/work/tiles.tar", "$rootPath/$ROUTING_DIRECTORY/tiles.tar")
            .replace("/work/tiles", "$rootPath/$ROUTING_DIRECTORY/tiles")
            .replace("/work/admins.sqlite", "$rootPath/$ROUTING_DIRECTORY/admins.sqlite")
            .replace("/work/timezones.sqlite", "$rootPath/$ROUTING_DIRECTORY/timezones.sqlite")
        writeSyncedText(config, rewritten)
    }

    private fun verifyInstalledPackage(
        directory: File,
        manifest: OfflineRegionPackageManifest,
    ) {
        val routing = File(directory, ROUTING_DIRECTORY)
        val search = File(directory, "$SEARCH_DIRECTORY/$SEARCH_DATABASE_FILE")
        val map = File(directory, "$MAP_DIRECTORY/$MAP_FILE")
        if (!File(routing, VALHALLA_CONFIG_FILE).isFile ||
            !File(routing, ROUTING_TILE_EXTRACT_FILE).isFile ||
            !File(routing, ROUTING_ADMINS_DATABASE_FILE).isFile ||
            !File(routing, ROUTING_TIMEZONES_DATABASE_FILE).isFile
        ) {
            throw IOException("Regional routing component is incomplete")
        }
        if (!search.isFile || search.length() == 0L) throw IOException("Regional search component is incomplete")
        if (!map.isFile || map.length() == 0L) throw IOException("Regional map component is incomplete")

        val expected = manifest.components
        val installedSearchBytes = search.length()
        if (installedSearchBytes != expected.search.installedBytes) {
            throw IOException("Regional search installed size does not match the manifest")
        }
        if (map.length() != expected.map.installedBytes) {
            throw IOException("Regional map installed size does not match the manifest")
        }
        // The routing config contains absolute paths after installation. Its
        // byte length is platform-dependent, so only the required files are
        // checked here; the compressed artifact checksum remains exact.
    }

    private fun publishPointer(regionId: String, packageName: String) {
        val pointer = File(active, "$regionId.pointer")
        val temporary = File(active, "$regionId.pointer.tmp-${UUID.randomUUID()}")
        try {
            writeSyncedText(temporary, packageName)
            moveAtomically(temporary, pointer)
        } finally {
            temporary.delete()
        }
    }

    private fun readManifest(directory: File): OfflineRegionPackageManifest? {
        val file = File(directory, MANIFEST_FILE)
        if (!file.isFile) return null
        val parsed = runCatching {
            OfflineRegionPackageManifestCodec.parse(file.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val manifest = (parsed as? ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifestParseResult.Success)
            ?.manifest
            ?: return null
        return manifest.takeIf {
            ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageManifestPolicy
                .validate(it, currentAppVersionCode).isEmpty() && manifestVerifier.verify(it)
        }
    }

    private fun validateDownloadedArtifact(
        file: File,
        expectedSha256: String,
        expectedBytes: Long,
        component: OfflineRegionComponent,
    ) {
        if (!file.isFile || file.length() != expectedBytes) {
            throw IOException("Downloaded $component artifact has an unexpected size")
        }
        if (!sha256(file).equals(expectedSha256, ignoreCase = true)) {
            throw IOException("Downloaded $component artifact has an unexpected checksum")
        }
    }

    private fun extractGzipTar(source: File, destination: File) {
        GZIPInputStream(source.inputStream(), COPY_BUFFER_SIZE).use { input ->
            val header = ByteArray(TAR_BLOCK_SIZE)
            while (true) {
                readFully(input, header)
                if (header.all { it == 0.toByte() }) break
                val name = tarString(header, 0, 100)
                val size = tarOctal(header, 124, 12)
                val type = header[156].toInt().and(0xff).toChar()
                val target = safeResolve(destination, name)
                when (type) {
                    '\u0000', '0' -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            copyExactly(input, output, size)
                            output.fd.sync()
                        }
                    }
                    '5' -> if (!target.exists() && !target.mkdirs()) {
                        throw IOException("Could not create routing directory")
                    }
                    else -> throw IOException("Unsupported routing archive entry type")
                }
                val padding = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE
                skipExactly(input, padding)
            }
        }
    }

    private fun gunzip(source: File, destination: File) {
        GZIPInputStream(source.inputStream(), COPY_BUFFER_SIZE).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
                output.fd.sync()
            }
        }
    }

    private fun copyFile(source: File, destination: File) {
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
                output.fd.sync()
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw IOException("Truncated routing archive")
            offset += count
        }
    }

    private fun copyExactly(input: InputStream, output: FileOutputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Truncated routing archive entry")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun skipExactly(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) {
                if (input.read() < 0) throw IOException("Truncated routing archive padding")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun tarString(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, StandardCharsets.US_ASCII)
            .substringBefore('\u0000')
            .trim()

    private fun tarOctal(header: ByteArray, offset: Int, length: Int): Long {
        val value = tarString(header, offset, length).trimStart('0').ifBlank { "0" }
        return value.toLongOrNull(8) ?: throw IOException("Invalid routing archive size")
    }

    private fun safeResolve(root: File, relativePath: String): File {
        if (relativePath.isBlank() || relativePath.startsWith('/') || relativePath.contains('\\')) {
            throw IOException("Unsafe routing archive path")
        }
        val rootCanonical = root.canonicalFile
        val resolved = File(rootCanonical, relativePath).canonicalFile
        if (!isChildOf(resolved, rootCanonical)) throw IOException("Routing archive path escapes package")
        return resolved
    }

    private fun isChildOf(file: File, parent: File): Boolean {
        val parentPath = parent.canonicalPath + File.separator
        return file.canonicalPath.startsWith(parentPath)
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!source.renameTo(target)) throw IOException("Could not atomically publish regional package")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun writeSyncedText(file: File, value: String) {
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun ensureDirectories() {
        if (!root.exists() && !root.mkdirs()) throw IOException("Could not create offline storage")
        if (!packages.exists() && !packages.mkdirs()) throw IOException("Could not create regional package storage")
        if (!active.exists() && !active.mkdirs()) throw IOException("Could not create regional pointer storage")
    }

    private fun cleanupStaging() {
        root.listFiles()
            ?.filter { it.name.startsWith("staging-") }
            ?.forEach(File::deleteRecursively)
    }

    /**
     * Keeps only verified packages named by a valid active pointer. A process
     * can die after publishing a package directory but before publishing its
     * pointer, and normal updates otherwise leave the previous release behind.
     * Both cases are safe to reclaim because the pointer is the sole active
     * ownership record.
     */
    private fun cleanupOrphanedPackages() {
        val referenced = active.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".pointer") }
            ?.mapNotNull { pointer ->
                val regionId = pointer.name.removeSuffix(".pointer")
                if (!REGION_ID_PATTERN.matches(regionId)) return@mapNotNull null
                val packageName = runCatching { pointer.readText(Charsets.UTF_8).trim() }
                    .getOrNull()
                    ?: return@mapNotNull null
                if (!PACKAGE_NAME_PATTERN.matches(packageName)) return@mapNotNull null
                val directory = File(File(packages, regionId), packageName)
                if (!directory.isDirectory ||
                    directory.canonicalFile.parentFile != File(packages, regionId).canonicalFile
                ) {
                    return@mapNotNull null
                }
                val manifest = readManifest(directory) ?: return@mapNotNull null
                if (runCatching { verifyInstalledPackage(directory, manifest) }.isFailure) {
                    return@mapNotNull null
                }
                "$regionId/$packageName"
            }
            ?.toSet()
            .orEmpty()

        packages.listFiles()
            ?.filter { regionDirectory ->
                regionDirectory.isDirectory &&
                    REGION_ID_PATTERN.matches(regionDirectory.name) &&
                    regionDirectory.canonicalFile.parentFile == packages.canonicalFile
            }
            ?.forEach { regionDirectory ->
                regionDirectory.listFiles()
                    ?.filter { packageDirectory ->
                        packageDirectory.isDirectory &&
                            PACKAGE_NAME_PATTERN.matches(packageDirectory.name) &&
                            packageDirectory.canonicalFile.parentFile == regionDirectory.canonicalFile
                    }
                    ?.filterNot { packageDirectory ->
                        "${regionDirectory.name}/${packageDirectory.name}" in referenced
                    }
                    ?.forEach(File::deleteRecursively)
                if (regionDirectory.listFiles().isNullOrEmpty()) regionDirectory.delete()
            }
    }

    data class InstalledOfflineRegion(
        val manifest: OfflineRegionPackageManifest,
        val directory: File,
        val routingConfig: File,
        val routingTileExtract: File,
        val searchDatabase: File,
        val mapFile: File,
    ) {
        companion object {
            fun fromDirectory(directory: File, manifest: OfflineRegionPackageManifest) =
                InstalledOfflineRegion(
                    manifest = manifest,
                    directory = directory,
                    routingConfig = File(directory, "routing/valhalla.json"),
                    routingTileExtract = File(directory, "routing/tiles.tar"),
                    searchDatabase = File(directory, "search/places.sqlite"),
                    mapFile = File(directory, "map/map.pmtiles"),
                )
        }
    }

    private companion object {
        const val STORAGE_DIRECTORY = "offline-regions"
        const val PACKAGES_DIRECTORY = "packages"
        const val DOWNLOADS_DIRECTORY = "downloads"
        const val ACTIVE_DIRECTORY = "active"
        const val MANIFEST_FILE = "manifest.json"
        const val ROUTING_DIRECTORY = "routing"
        const val SEARCH_DIRECTORY = "search"
        const val MAP_DIRECTORY = "map"
        const val VALHALLA_CONFIG_FILE = "valhalla.json"
        const val ROUTING_TILE_EXTRACT_FILE = "tiles.tar"
        const val ROUTING_ADMINS_DATABASE_FILE = "admins.sqlite"
        const val ROUTING_TIMEZONES_DATABASE_FILE = "timezones.sqlite"
        const val SEARCH_DATABASE_FILE = "places.sqlite"
        const val MAP_FILE = "map.pmtiles"
        const val TAR_BLOCK_SIZE = 512
        const val COPY_BUFFER_SIZE = 64 * 1024
        val REGION_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        val RELEASE_VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
