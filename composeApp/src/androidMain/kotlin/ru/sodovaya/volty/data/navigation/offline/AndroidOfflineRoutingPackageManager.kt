package ru.sodovaya.volty.data.navigation.offline

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import ru.sodovaya.volty.domain.navigation.offline.OfflineManifestErrorCode
import ru.sodovaya.volty.domain.navigation.offline.OfflineManifestValidationError
import ru.sodovaya.volty.domain.navigation.offline.OfflineManifestParseResult
import ru.sodovaya.volty.domain.navigation.offline.OfflinePackageActivationDecision
import ru.sodovaya.volty.domain.navigation.offline.OfflinePackageInstallFailure
import ru.sodovaya.volty.domain.navigation.offline.OfflinePackageInstallResult
import ru.sodovaya.volty.domain.navigation.offline.OfflinePackageValidation
import ru.sodovaya.volty.domain.navigation.offline.OfflineRoutingPackageManifest
import ru.sodovaya.volty.domain.navigation.offline.OfflineRoutingPolicy
import ru.sodovaya.volty.domain.navigation.offline.OfflinePackageInstallPolicy
import ru.sodovaya.volty.domain.navigation.offline.OfflineRegionBounds

/**
 * Owns downloaded offline routing packages below app-private storage.
 *
 * A package is activated by replacing a small pointer file, never by replacing the
 * active directory in place. This makes an interrupted copy observable as an
 * unreferenced staging directory rather than as a partially readable package.
 */
class AndroidOfflineRoutingPackageManager(context: Context) {
    private val storageRoot = File(context.applicationContext.filesDir, STORAGE_DIRECTORY)
    private val packagesDirectory = File(storageRoot, PACKAGES_DIRECTORY)
    private val activePointer = File(storageRoot, ACTIVE_POINTER_FILE)
    private val lock = Any()

    init {
        synchronized(lock) {
            prepareDirectories()
            cleanupInterruptedInstalls()
        }
    }

    /** The validated bounds of the currently active package, or null when none is usable. */
    val activeCoverage: OfflineRegionBounds?
        get() = synchronized(lock) { readActiveManifest()?.bounds }

    /** The complete validated manifest of the currently active package, or null. */
    val activeManifest: OfflineRoutingPackageManifest?
        get() = synchronized(lock) { readActiveManifest() }

    /** The app-private directory of the currently active, checksum-verified package. */
    val activePackageDirectory: File?
        get() = synchronized(lock) { readActivePackageDirectory() }

    /**
     * Installs the bundled regional package once, leaving an explicitly installed
     * package untouched. The bundled package is copied through the same staged
     * validation path as a downloaded package.
     */
    fun installBundledAssets(
        assets: AssetManager,
        assetDirectory: String = BUNDLED_ASSET_DIRECTORY,
    ): OfflinePackageInstallResult? = synchronized(lock) {
        if (readActiveManifest() != null) return@synchronized null
        val names = assets.list(assetDirectory)?.toList().orEmpty()
        if (names.isEmpty()) {
            Log.w(TAG, "Bundled offline routing assets are missing: $assetDirectory")
            return@synchronized null
        }
        // `install()` removes `staging-*` before validating a downloaded package.
        // Bundled assets are already the source package, so they need a distinct
        // temporary prefix or the cleanup would delete the input we just copied.
        val sourceDirectory = File(storageRoot, "bundled-${UUID.randomUUID()}")
        try {
            if (!sourceDirectory.mkdirs()) throw IOException("Could not create bundled package staging directory")
            names.forEach { name ->
                assets.open("$assetDirectory/$name").use { input ->
                    FileOutputStream(File(sourceDirectory, name)).use { output ->
                        input.copyTo(output, COPY_BUFFER_SIZE)
                        output.fd.sync()
                    }
                }
            }
            install(sourceDirectory).also { result ->
                when (result) {
                    is OfflinePackageInstallResult.Installed ->
                        Log.i(TAG, "Bundled offline routing package activated: ${result.manifest.packageId}")
                    is OfflinePackageInstallResult.Rejected ->
                        Log.w(TAG, "Bundled offline routing package rejected: ${result.failure}; ${result.errors}")
                }
            }
        } catch (_: InterruptedIOException) {
            OfflinePackageInstallResult.Rejected(OfflinePackageInstallFailure.INTERRUPTED)
        } catch (error: Exception) {
            Log.w(TAG, "Bundled offline routing package could not be installed", error)
            OfflinePackageInstallResult.Rejected(OfflinePackageInstallFailure.STORAGE_FAILED)
        } finally {
            sourceDirectory.deleteRecursively()
        }
    }

    /**
     * Installs a local downloaded package directory.
     *
     * The directory must contain exactly `manifest.json` and the payload files
     * named by that manifest. It is deliberately a local-file API: downloading
     * and routing engines are separate integrations and this class never opens
     * a network client.
     */
    fun install(downloadDirectory: File): OfflinePackageInstallResult = synchronized(lock) {
        cleanupInterruptedInstalls()
        val previous = readActiveManifest()
        val sourceValidation = validatePackageDirectory(downloadDirectory)
        if (sourceValidation !is OfflinePackageValidation.Valid) {
            return@synchronized rejected(
                OfflinePackageInstallFailure.VALIDATION_FAILED,
                (sourceValidation as OfflinePackageValidation.Invalid).errors,
            )
        }

        val manifest = sourceValidation.manifest
        val stagingDirectory = File(storageRoot, "staging-${UUID.randomUUID()}")
        val packageDirectory = File(
            packagesDirectory,
            "${manifest.packageId}-v${manifest.packageVersion}-${UUID.randomUUID()}",
        )
        var activated = false
        try {
            if (!stagingDirectory.mkdirs()) {
                throw IOException("Could not create offline package staging directory")
            }
            copyPackage(downloadDirectory, stagingDirectory, manifest)
            val stagedValidation = validatePackageDirectory(stagingDirectory)
            if (stagedValidation !is OfflinePackageValidation.Valid) {
                val invalid = stagedValidation as OfflinePackageValidation.Invalid
                val decision = OfflinePackageInstallPolicy.decide(
                    previous = previous,
                    validation = stagedValidation,
                    failure = OfflinePackageInstallFailure.VALIDATION_FAILED,
                ) as OfflinePackageActivationDecision.RetainPrevious
                return@synchronized rejected(decision.failure, invalid.errors)
            }

            if (!packageDirectory.mkdirs()) {
                throw IOException("Could not create offline package directory")
            }
            copyPackage(stagingDirectory, packageDirectory, manifest)
            val installedValidation = validatePackageDirectory(packageDirectory)
            if (installedValidation !is OfflinePackageValidation.Valid) {
                val invalid = installedValidation as OfflinePackageValidation.Invalid
                val decision = OfflinePackageInstallPolicy.decide(
                    previous = previous,
                    validation = installedValidation,
                    failure = OfflinePackageInstallFailure.VALIDATION_FAILED,
                ) as OfflinePackageActivationDecision.RetainPrevious
                return@synchronized rejected(decision.failure, invalid.errors)
            }

            activatePackage(packageDirectory)
            activated = true
            OfflinePackageInstallResult.Installed(installedValidation.manifest)
        } catch (_: InterruptedIOException) {
            val decision = OfflinePackageInstallPolicy.decide(
                previous = previous,
                validation = OfflinePackageValidation.Valid(manifest),
                failure = OfflinePackageInstallFailure.INTERRUPTED,
            ) as OfflinePackageActivationDecision.RetainPrevious
            rejected(decision.failure)
        } catch (_: Exception) {
            rejected(OfflinePackageInstallFailure.STORAGE_FAILED)
        } finally {
            stagingDirectory.deleteRecursively()
            if (!activated) {
                packageDirectory.deleteRecursively()
            }
        }
    }

    private fun prepareDirectories() {
        if (!storageRoot.exists() && !storageRoot.mkdirs()) {
            throw IOException("Could not create offline routing storage")
        }
        if (!packagesDirectory.exists() && !packagesDirectory.mkdirs()) {
            throw IOException("Could not create offline package storage")
        }
    }

    private fun cleanupInterruptedInstalls() {
        storageRoot.listFiles()
            ?.filter { it.name.startsWith("staging-") || it.name.startsWith("$ACTIVE_POINTER_FILE.tmp-") }
            ?.forEach(File::deleteRecursively)
    }

    private fun validatePackageDirectory(directory: File): OfflinePackageValidation {
        if (!directory.isDirectory) {
            return invalid("Downloaded package is not a directory")
        }
        val canonicalDirectory = directory.canonicalFile
        val manifestFile = File(directory, OfflineRoutingPolicy.MANIFEST_FILE_NAME)
        if (!manifestFile.isFile || manifestFile.canonicalFile.parentFile != canonicalDirectory) {
            return invalid("manifest.json is missing")
        }
        val parseResult = try {
            OfflineRoutingPolicy.parseManifest(manifestFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            OfflineManifestParseResult.Failure(
                OfflineManifestValidationError(
                    code = OfflineManifestErrorCode.MALFORMED_MANIFEST,
                    detail = "manifest.json could not be read",
                ),
            )
        }
        if (parseResult is OfflineManifestParseResult.Failure) {
            return OfflinePackageValidation.Invalid(listOf(parseResult.error))
        }
        val manifest = (parseResult as OfflineManifestParseResult.Success).manifest
        val manifestErrors = OfflineRoutingPolicy.validateManifest(manifest)
        if (manifestErrors.isNotEmpty()) {
            return OfflinePackageValidation.Invalid(manifestErrors)
        }

        val actualChecksums = linkedMapOf<String, String>()
        directory.listFiles()?.forEach { entry ->
            if (entry.name == OfflineRoutingPolicy.MANIFEST_FILE_NAME) {
                if (!entry.isFile || entry.canonicalFile.parentFile != canonicalDirectory) {
                    return invalid("manifest.json is not a regular file")
                }
            } else if (entry.isFile) {
                if (entry.canonicalFile.parentFile != canonicalDirectory) {
                    return invalid("Downloaded package contains a file outside its directory")
                }
                actualChecksums[entry.name] = sha256(entry)
            } else {
                actualChecksums[entry.name] = ""
            }
        } ?: return invalid("Could not list downloaded package")

        return OfflineRoutingPolicy.validatePackage(manifest, actualChecksums)
    }

    private fun copyPackage(
        sourceDirectory: File,
        destinationDirectory: File,
        manifest: OfflineRoutingPackageManifest,
    ) {
        copyFile(
            source = File(sourceDirectory, OfflineRoutingPolicy.MANIFEST_FILE_NAME),
            destination = File(destinationDirectory, OfflineRoutingPolicy.MANIFEST_FILE_NAME),
        )
        manifest.files.forEach { file ->
            copyFile(
                source = File(sourceDirectory, file.name),
                destination = File(destinationDirectory, file.name),
            )
        }
    }

    private fun copyFile(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    checkInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
    }

    private fun activatePackage(packageDirectory: File) {
        val temporaryPointer = File(storageRoot, "$ACTIVE_POINTER_FILE.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporaryPointer).use { output ->
                output.write(packageDirectory.name.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporaryPointer.toPath(),
                    activePointer.toPath(),
                    ATOMIC_MOVE,
                    REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                if (!temporaryPointer.renameTo(activePointer)) {
                    throw IOException("Could not atomically publish offline package")
                }
            }
        } finally {
            temporaryPointer.delete()
        }
    }

    private fun readActiveManifest(): OfflineRoutingPackageManifest? {
        val packageDirectory = readActivePackageDirectory() ?: return null
        val validation = validatePackageDirectory(packageDirectory)
        return (validation as? OfflinePackageValidation.Valid)?.manifest
    }

    private fun readActivePackageDirectory(): File? {
        if (!activePointer.isFile) return null
        val packageName = runCatching { activePointer.readText(Charsets.UTF_8).trim() }.getOrNull()
            ?: return null
        if (!PACKAGE_DIRECTORY_PATTERN.matches(packageName)) return null
        val packageDirectory = File(packagesDirectory, packageName)
        if (packageDirectory.canonicalFile.parentFile != packagesDirectory.canonicalFile) return null
        return (validatePackageDirectory(packageDirectory) as? OfflinePackageValidation.Valid)
            ?.let { packageDirectory }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                checkInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Offline package install interrupted")
        }
    }

    private fun rejected(
        failure: OfflinePackageInstallFailure,
        errors: List<ru.sodovaya.volty.domain.navigation.offline.OfflineManifestValidationError> = emptyList(),
    ) = OfflinePackageInstallResult.Rejected(failure = failure, errors = errors)

    private fun invalid(detail: String): OfflinePackageValidation.Invalid =
        OfflinePackageValidation.Invalid(
            listOf(
                OfflineManifestValidationError(
                    code = OfflineManifestErrorCode.MALFORMED_MANIFEST,
                    detail = detail,
                ),
            ),
        )

    private companion object {
        const val STORAGE_DIRECTORY = "offline-routing"
        const val PACKAGES_DIRECTORY = "packages"
        const val ACTIVE_POINTER_FILE = "active-package"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val BUNDLED_ASSET_DIRECTORY = "offline-routing"
        const val TAG = "VoltyOfflineRouting"
        val PACKAGE_DIRECTORY_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}-v[1-9][0-9]*-[0-9a-fA-F-]+")
    }
}
