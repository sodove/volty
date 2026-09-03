package ru.sodovaya.volty.domain.navigation.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.navigation.GeoCoordinate

class OfflineRoutingPackagePolicyTest {
    @Test
    fun `route is covered only when both endpoints are inside the regional bounds`() {
        val manifest = validManifest()

        assertIs<OfflineCoverageResult.Covered>(
            OfflineRoutingPolicy.coverage(
                manifest = manifest,
                origin = GeoCoordinate(latitude = 55.5, longitude = 60.5),
                destination = GeoCoordinate(latitude = 56.5, longitude = 61.5),
            ),
        )
        assertEquals(
            OfflineCoverageEndpoint.ORIGIN,
            assertIs<OfflineCoverageResult.Uncovered>(
                OfflineRoutingPolicy.coverage(
                    manifest,
                    origin = GeoCoordinate(latitude = 54.9, longitude = 60.5),
                    destination = GeoCoordinate(latitude = 56.5, longitude = 61.5),
                ),
            ).endpoint,
        )
        assertEquals(
            OfflineCoverageEndpoint.DESTINATION,
            assertIs<OfflineCoverageResult.Uncovered>(
                OfflineRoutingPolicy.coverage(
                    manifest,
                    origin = GeoCoordinate(latitude = 55.5, longitude = 60.5),
                    destination = GeoCoordinate(latitude = 56.5, longitude = 62.1),
                ),
            ).endpoint,
        )
    }

    @Test
    fun `malformed manifest is rejected with a typed error`() {
        val result = OfflinePackageManifestCodec.parse("{ not-json }")

        val failure = assertIs<OfflineManifestParseResult.Failure>(result)
        assertEquals(OfflineManifestErrorCode.MALFORMED_MANIFEST, failure.error.code)
    }

    @Test
    fun `manifest validation rejects unsafe names invalid bounds and unsupported versions`() {
        val manifest = validManifest(
            formatVersion = 2,
            bounds = OfflineRegionBounds(
                south = 57.0,
                west = 61.0,
                north = 56.0,
                east = 60.0,
            ),
            files = listOf(
                OfflinePackageFile(name = "../segments.dat", sha256 = checksum),
                OfflinePackageFile(name = "../segments.dat", sha256 = checksum),
            ),
        )

        val result = OfflineRoutingPolicy.validatePackage(
            manifest = manifest,
            actualSha256ByFileName = emptyMap(),
        )

        val invalid = assertIs<OfflinePackageValidation.Invalid>(result)
        assertTrue(invalid.errors.any { it.code == OfflineManifestErrorCode.UNSUPPORTED_FORMAT_VERSION })
        assertTrue(invalid.errors.any { it.code == OfflineManifestErrorCode.INVALID_BOUNDS })
        assertTrue(invalid.errors.any { it.code == OfflineManifestErrorCode.INVALID_FILE_NAME })
        assertTrue(invalid.errors.any { it.code == OfflineManifestErrorCode.DUPLICATE_FILE_NAME })
    }

    @Test
    fun `checksum mismatch is rejected by package validation`() {
        val result = OfflineRoutingPolicy.validatePackage(
            manifest = validManifest(),
            actualSha256ByFileName = mapOf(
                "segments.dat" to "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            ),
        )

        val invalid = assertIs<OfflinePackageValidation.Invalid>(result)
        assertEquals(OfflineManifestErrorCode.CHECKSUM_MISMATCH, invalid.errors.single().code)
    }

    @Test
    fun `valid manifest json and payload checksum produce a valid package`() {
        val parsed = OfflinePackageManifestCodec.parse(
            """
            {
              "formatVersion": 1,
              "packageVersion": 3,
              "packageId": "ural",
              "bounds": {"south": 55.0, "west": 60.0, "north": 57.0, "east": 62.0},
              "files": [{"name": "segments.dat", "sha256": "$checksum"}]
            }
            """.trimIndent(),
        )
        val manifest = assertIs<OfflineManifestParseResult.Success>(parsed).manifest

        assertEquals(
            OfflinePackageValidation.Valid(manifest),
            OfflineRoutingPolicy.validatePackage(
                manifest = manifest,
                actualSha256ByFileName = mapOf("segments.dat" to checksum),
            ),
        )
    }

    @Test
    fun `checksum failure retains the previous valid package`() {
        val previous = validManifest(packageVersion = 4)
        val invalid = OfflinePackageValidation.Invalid(
            errors = listOf(
                OfflineManifestValidationError(
                    code = OfflineManifestErrorCode.CHECKSUM_MISMATCH,
                    detail = "segments.dat",
                ),
            ),
        )

        val decision = OfflinePackageInstallPolicy.decide(
            previous = previous,
            validation = invalid,
            failure = OfflinePackageInstallFailure.VALIDATION_FAILED,
        )

        val retained = assertIs<OfflinePackageActivationDecision.RetainPrevious>(decision)
        assertEquals(previous, retained.previous)
        assertTrue(retained.deleteStaging)
    }

    @Test
    fun `interrupted install retains the previous package and cleans staging`() {
        val previous = validManifest(packageVersion = 4)

        val decision = OfflinePackageInstallPolicy.decide(
            previous = previous,
            validation = OfflinePackageValidation.Valid(previous),
            failure = OfflinePackageInstallFailure.INTERRUPTED,
        )

        val retained = assertIs<OfflinePackageActivationDecision.RetainPrevious>(decision)
        assertEquals(previous, retained.previous)
        assertTrue(retained.deleteStaging)
    }

    @Test
    fun `valid package is the only package eligible for activation`() {
        val manifest = validManifest(packageVersion = 8)

        val decision = OfflinePackageInstallPolicy.decide(
            previous = validManifest(packageVersion = 7),
            validation = OfflinePackageValidation.Valid(manifest),
            failure = null,
        )

        assertEquals(
            OfflinePackageActivationDecision.Activate(manifest),
            decision,
        )
    }

    @Test
    fun `coverage policy is device agnostic and has no network fallback`() {
        val result = OfflineRoutingPolicy.coverage(
            manifest = validManifest(),
            origin = GeoCoordinate(latitude = 55.5, longitude = 60.5),
            destination = GeoCoordinate(latitude = 56.5, longitude = 61.5),
        )

        assertIs<OfflineCoverageResult.Covered>(result)
        assertFalse(result.requiresNetwork)
    }

    private fun validManifest(
        formatVersion: Int = 1,
        packageVersion: Int = 1,
        bounds: OfflineRegionBounds = OfflineRegionBounds(
            south = 55.0,
            west = 60.0,
            north = 57.0,
            east = 62.0,
        ),
        files: List<OfflinePackageFile> = listOf(
            OfflinePackageFile(name = "segments.dat", sha256 = checksum),
        ),
    ) = OfflineRoutingPackageManifest(
        formatVersion = formatVersion,
        packageVersion = packageVersion,
        packageId = "ural",
        bounds = bounds,
        files = files,
    )

    private companion object {
        const val checksum = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
