package ru.sodovaya.volty.data.navigation.offline

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionArtifactDownload
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionComponent
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionDownloadPlan
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionResumeDecision
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionResumePolicy

/**
 * Downloads one release into an unreferenced staging directory.
 *
 * Every artifact has a bounded expected length and a manifest checksum. A partial
 * file is resumed only with HTTP 206; a server that ignores Range starts that
 * artifact from zero. Nothing becomes visible to routing/search/map until the
 * caller atomically publishes the complete staging directory.
 */
class AndroidOfflineRegionArtifactDownloader(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 30_000,
) {
    suspend fun download(
        plan: OfflineRegionDownloadPlan,
        stagingDirectory: File,
        onProgress: (downloadedBytes: Long) -> Unit = {},
    ): Map<OfflineRegionComponent, File> = withContext(Dispatchers.IO) {
        require(userAgent.isNotBlank()) { "userAgent must not be blank" }
        prepareStagingDirectory(stagingDirectory)
        onProgress(stagedDownloadBytes(plan, stagingDirectory))
        val files = linkedMapOf<OfflineRegionComponent, File>()
        try {
            plan.artifacts.forEach { artifact ->
                checkInterrupted()
                val target = safeResolve(stagingDirectory, artifact.relativePath)
                target.parentFile?.mkdirs()
                val part = File(target.parentFile, "${target.name}.part")
                if (target.isFile && target.length() == artifact.downloadBytes &&
                    sha256(target).equals(artifact.sha256, ignoreCase = true)
                ) {
                    onProgress(stagedDownloadBytes(plan, stagingDirectory))
                } else {
                    downloadArtifact(
                        artifact = artifact,
                        target = target,
                        part = part,
                        plan = plan,
                        stagingDirectory = stagingDirectory,
                        onProgress = onProgress,
                    )
                }
                onProgress(stagedDownloadBytes(plan, stagingDirectory))
                files[artifact.component] = target
            }
            files
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is InterruptedIOException) throw error
            throw IOException("Regional artifact download failed", error)
        }
    }

    private fun downloadArtifact(
        artifact: OfflineRegionArtifactDownload,
        target: File,
        part: File,
        plan: OfflineRegionDownloadPlan,
        stagingDirectory: File,
        onProgress: (downloadedBytes: Long) -> Unit,
    ) {
        val partialBytes = part.length().takeIf { part.isFile } ?: 0L
        val resumeDecision = OfflineRegionResumePolicy.decide(partialBytes, artifact.downloadBytes)
        val offset = when (resumeDecision) {
            is OfflineRegionResumeDecision.Resume -> resumeDecision.offsetBytes
            OfflineRegionResumeDecision.Restart -> {
                part.delete()
                0L
            }
        }
        val connection = (URL(artifact.url).openConnection() as? HttpURLConnection)
            ?: throw IOException("Unsupported artifact URL")
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", userAgent)
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")

            val responseCode = connection.responseCode
            if (connection.url.protocol != "https") {
                throw IOException("Artifact redirect did not remain on HTTPS")
            }
            when {
                responseCode == HttpURLConnection.HTTP_PARTIAL && offset > 0L -> Unit
                responseCode == HttpURLConnection.HTTP_OK -> {
                    if (offset > 0L) {
                        part.delete()
                    }
                }
                responseCode == HttpURLConnection.HTTP_REQUESTED_RANGE_NOT_SATISFIABLE -> {
                    throw IOException("Server rejected resume for ${artifact.component}")
                }
                responseCode == HttpURLConnection.HTTP_TOO_MANY_REQUESTS -> {
                    throw IOException("Artifact server rate-limited the download")
                }
                responseCode >= 500 -> throw IOException("Artifact server unavailable ($responseCode)")
                responseCode !in 200..299 -> throw IOException("Artifact download failed ($responseCode)")
            }

            val append = offset > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            val startingBytes = if (append) offset else 0L
            val contentLength = connection.contentLengthLong
            if (contentLength >= 0L && contentLength != artifact.downloadBytes - startingBytes) {
                throw IOException(
                    "Unexpected content length for ${artifact.component}: " +
                        "$contentLength, expected ${artifact.downloadBytes - startingBytes}",
                )
            }
            FileOutputStream(part, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var written = startingBytes
                    onProgress(stagedDownloadBytes(plan, stagingDirectory))
                    while (true) {
                        checkInterrupted()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        written += count
                        if (written > artifact.downloadBytes) {
                            throw IOException("Artifact exceeds its manifest size")
                        }
                        output.write(buffer, 0, count)
                        onProgress(stagedDownloadBytes(plan, stagingDirectory))
                    }
                    output.fd.sync()
                    if (written != artifact.downloadBytes) {
                        throw IOException(
                            "Incomplete ${artifact.component} artifact: $written / ${artifact.downloadBytes}",
                        )
                    }
                }
            }
            val actualChecksum = sha256(part)
            if (!actualChecksum.equals(artifact.sha256, ignoreCase = true)) {
                throw IOException("Checksum mismatch for ${artifact.component}")
            }
            moveAtomically(part, target)
        } finally {
            connection.disconnect()
        }
    }

    private fun prepareStagingDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create artifact staging directory")
        }
        if (!directory.isDirectory) throw IOException("Artifact staging path is not a directory")
    }

    private fun stagedDownloadBytes(
        plan: OfflineRegionDownloadPlan,
        directory: File,
    ): Long = plan.artifacts.sumOf { artifact ->
        val target = safeResolve(directory, artifact.relativePath)
        val part = File(target.parentFile, "${target.name}.part")
        val bytes = when {
            target.isFile -> target.length()
            part.isFile -> part.length()
            else -> 0L
        }
        bytes.coerceIn(0L, artifact.downloadBytes)
    }

    private fun safeResolve(root: File, relativePath: String): File {
        val rootCanonical = root.canonicalFile
        val resolved = File(rootCanonical, relativePath).canonicalFile
        val prefix = rootCanonical.path + File.separator
        if (!resolved.path.startsWith(prefix)) {
            throw IOException("Artifact path escapes staging directory")
        }
        return resolved
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!source.renameTo(target)) throw IOException("Could not publish downloaded artifact")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                checkInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0').lowercase(Locale.US)
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Regional artifact download interrupted")
        }
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val DEFAULT_USER_AGENT = "Volty/0.7 offline-region-downloader"
    }
}
