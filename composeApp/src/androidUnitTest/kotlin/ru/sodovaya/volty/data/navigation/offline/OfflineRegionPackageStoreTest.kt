package ru.sodovaya.volty.data.navigation.offline

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import ru.sodovaya.volty.domain.navigation.region.*

class OfflineRegionPackageStoreTest {
    @Test
    fun legacy_navigation_migrates_without_map_and_preserves_original_across_restart() = withFiles { files ->
        val original = installedLegacy(files)
        val before = original.walkTopDown().filter(File::isFile).associate { it.relativeTo(original).path to it.readBytes().toList() }
        val store = store(files)

        val migrated = assertNotNull(store.active("ru-sve-ekb"))
        assertTrue(migrated.directory != original)
        assertTrue(migrated.routingTileExtract.isFile)
        assertEquals(45L, migrated.searchDatabase.length())
        assertFalse(migrated.mapFile.exists())
        assertFalse(File(migrated.directory, "map").exists())
        assertTrue(migrated.routingConfig.readText().contains(migrated.directory.canonicalPath.replace('\\', '/')))
        assertEquals(before, original.walkTopDown().filter(File::isFile).associate { it.relativeTo(original).path to it.readBytes().toList() })
        assertEquals(migrated.directory, assertNotNull(store(files).active("ru-sve-ekb")).directory)
        assertTrue(original.isDirectory)
    }

    @Test
    fun absent_legacy_map_does_not_hide_valid_navigation() = withFiles { files ->
        val original = installedLegacy(files)
        File(original, "map/map.pmtiles").delete()
        assertNotNull(store(files).active("ru-sve-ekb"))
        assertTrue(original.isDirectory)
    }

    @Test
    fun invalid_navigation_or_signature_is_not_activated_or_erased() = withFiles { files ->
        val original = installedLegacy(files)
        File(original, "search/places.sqlite").writeBytes(byteArrayOf(1))
        assertNull(store(files).active("ru-sve-ekb"))
        assertTrue(original.isDirectory)
        File(original, "search/places.sqlite").writeBytes(ByteArray(45))
        val manifestFile = File(original, "manifest.json")
        manifestFile.writeText(manifestFile.readText().replace("\"downloadBytes\":35", "\"downloadBytes\":36"))
        assertNull(store(files).active("ru-sve-ekb"))
        assertTrue(original.isDirectory)
    }

    @Test
    fun v3_installs_only_navigation_and_keeps_the_legacy_rollback_directory() = withFiles { files ->
        val original = installedLegacy(files)
        val store = store(files)
        val (manifest, artifacts) = download(files)
        val plan = assertIs<OfflineRegionDownloadPlanResult.Ready>(OfflineRegionDownloadPlanFactory.create(manifest, 31)).plan
        val installed = store.install(manifest, plan, artifacts)
        assertTrue(installed.routingConfig.isFile)
        assertEquals(45L, installed.searchDatabase.length())
        assertFalse(installed.mapFile.exists())
        assertEquals(3, installed.directory.listFiles()!!.size)
        assertEquals(installed.directory, assertNotNull(store(files).active("ru-sve-ekb")).directory)
        assertTrue(File(original, "map/map.pmtiles").isFile)
    }

    @Test
    fun altered_plan_cannot_replace_the_signed_artifact_checksum() = withFiles { files ->
        val store = store(files)
        val (manifest, artifacts) = download(files)
        val plan = assertIs<OfflineRegionDownloadPlanResult.Ready>(OfflineRegionDownloadPlanFactory.create(manifest, 31)).plan
        val forged = plan.copy(artifacts = plan.artifacts.map { it.copy(sha256 = "c".repeat(64)) })
        assertEquals(OfflineRegionPackageFailure.INCOMPATIBLE,
            assertFailsWith<OfflineRegionPackageFailureException> { store.install(manifest, forged, artifacts) }.category)
        assertNull(store.active("ru-sve-ekb"))
    }

    @Test
    fun staged_progress_ignores_old_map_bytes() = withFiles { files ->
        val store = store(files)
        val (manifest, _) = download(files)
        val plan = assertIs<OfflineRegionDownloadPlanResult.Ready>(OfflineRegionDownloadPlanFactory.create(manifest, 31)).plan
        val staging = store.createDownloadStaging(manifest.regionId, manifest.releaseVersion)
        File(staging, "routing/valhalla-routing.tar.gz.part").also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(12)) }
        File(staging, "search/places.sqlite.gz.part").also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(8)) }
        File(staging, "map/map.pmtiles").also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(8000)) }
        assertEquals(20L, store.stagedDownloadBytes(staging, plan))
    }

    @Test
    fun real_ed25519_signatures_bind_legacy_map_and_v3_source() {
        val legacyText = signedLegacyJson()
        val legacy = requireNotNull(OfflineRegionLegacyManifestCodec.parse(legacyText))
        assertTrue(verifier().verifyLegacy(legacy))
        assertFalse(verifier().verifyLegacy(legacy.copy(components = legacy.components.copy(map = legacy.components.map.copy(sha256 = "c".repeat(64))))))
        val manifest = v3()
        val signed = manifest.copy(signature = manifest.signature.copy(value = sign(OfflineRegionPackageManifestCodec.signingPayload(manifest))))
        assertTrue(verifier().verify(signed))
        assertFalse(verifier().verify(signed.copy(source = signed.source.copy(sourceSha256 = "c".repeat(64)))))
        assertFalse(OfflineRegionManifestVerifier { true }.verifyLegacy(legacy))
    }

    @Test
    fun legacy_signature_generated_by_the_python_release_signer_still_verifies() {
        // tools/offline-navigation/sign-manifest.py canonical_payload, fixed public test seed 00..1f.
        val text = legacyManifestJson().replace("\"value\":\"signature\"",
            "\"value\":\"jr4M+1IBzXHNSaTuPsuPl/5V0td39L22vT3uvw9ap6e8ZfZndi/alIBRpvQA4T+WmT2hwwgV+ogiDjjdoheGAA==\"")
        val verifier = AndroidEd25519ManifestVerifier("release", "A6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg=")
        assertTrue(verifier.verifyLegacy(requireNotNull(OfflineRegionLegacyManifestCodec.parse(text))))
    }

    private fun store(files: File) = AndroidOfflineRegionPackageStore(files, 31, verifier())

    private fun installedLegacy(files: File): File {
        val original = File(files, "offline-regions/packages/ru-sve-ekb/2026.09.1-original").apply { mkdirs() }
        File(original, "routing").mkdirs()
        File(original, "search").mkdirs()
        File(original, "map").mkdirs()
        File(original, "routing/valhalla.json").writeText("{\"tile_extract\":\"${original.canonicalPath.replace('\\', '/')}/routing/tiles.tar\"}")
        listOf("tiles.tar", "admins.sqlite", "timezones.sqlite").forEach { File(original, "routing/$it").writeText("valid") }
        File(original, "search/places.sqlite").writeBytes(ByteArray(45))
        // Deliberately invalid PMTiles, with a byte count inconsistent with its signed declaration.
        File(original, "map/map.pmtiles").writeText("bad map")
        File(original, "manifest.json").writeText(signedLegacyJson())
        File(files, "offline-regions/active/ru-sve-ekb.pointer").also { it.parentFile.mkdirs(); it.writeText(original.name) }
        return original
    }

    private fun download(files: File): Pair<OfflineRegionPackageManifest, Map<OfflineRegionComponent, File>> {
        val routing = File(files, "routing.tar.gz").apply { writeBytes(gzip(tar(mapOf(
            "valhalla.json" to "{\"tile_extract\":\"/work/tiles.tar\"}".encodeToByteArray(),
            "tiles.tar" to byteArrayOf(1, 2), "admins.sqlite" to byteArrayOf(3), "timezones.sqlite" to byteArrayOf(4),
        )))) }
        val search = File(files, "search.gz").apply { writeBytes(gzip(ByteArray(45))) }
        val original = v3()
        val manifest = original.copy(releaseVersion = "2026.09.2", components = original.components.copy(
            routing = original.components.routing.copy(downloadBytes = routing.length(), sha256 = sha(routing)),
            search = original.components.search.copy(downloadBytes = search.length(), sha256 = sha(search)),
        ))
        return manifest.copy(signature = manifest.signature.copy(value = sign(OfflineRegionPackageManifestCodec.signingPayload(manifest)))) to
            mapOf(OfflineRegionComponent.ROUTING to routing, OfflineRegionComponent.SEARCH to search)
    }

    private fun v3() = assertIs<OfflineRegionManifestParseResult.Success>(OfflineRegionPackageManifestCodec.parse(v3ManifestJson())).manifest
    private fun signedLegacyJson(): String {
        val text = legacyManifestJson()
        val manifest = requireNotNull(OfflineRegionLegacyManifestCodec.parse(text))
        return text.replace("\"value\":\"signature\"", "\"value\":\"${sign(OfflineRegionLegacyManifestCodec.signingPayload(manifest))}\"")
    }
    private fun verifier() = AndroidEd25519ManifestVerifier("release", Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded))
    private fun sign(payload: String): String {
        val signer = Ed25519Signer().apply { init(true, privateKey) }
        val bytes = payload.encodeToByteArray()
        signer.update(bytes, 0, bytes.size)
        return Base64.getEncoder().encodeToString(signer.generateSignature())
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
    private fun tar(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        entries.forEach { (name, bytes) ->
            val header = ByteArray(512)
            name.encodeToByteArray().copyInto(header)
            bytes.size.toString(8).padStart(11, '0').encodeToByteArray().copyInto(header, 124)
            header[156] = '0'.code.toByte()
            write(header); write(bytes); write(ByteArray((512 - bytes.size % 512) % 512))
        }
        write(ByteArray(1024))
    }.toByteArray()
    private fun withFiles(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("volty-store-test-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
    private companion object {
        val privateKey = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)
    }
}
