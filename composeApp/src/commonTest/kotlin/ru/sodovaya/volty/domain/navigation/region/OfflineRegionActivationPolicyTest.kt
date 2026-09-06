package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfflineRegionActivationPolicyTest {
    @Test
    fun only_fully_valid_release_can_be_activated() {
        val release = validRelease()
        val decision = OfflineRegionActivationPolicy.decide(
            previous = null,
            candidate = release,
            manifestErrors = emptyList(),
            artifacts = OfflineRegionArtifactValidation.Valid(release),
            failure = null,
        )

        assertEquals(OfflineRegionActivationDecision.Activate(release), decision)
    }

    @Test
    fun checksum_failure_keeps_previous_release_and_discards_staging() {
        val previous = validRelease("2026.09.0")
        val candidate = validRelease("2026.09.1")
        val decision = OfflineRegionActivationPolicy.decide(
            previous = previous,
            candidate = candidate,
            manifestErrors = emptyList(),
            artifacts = OfflineRegionArtifactValidation.Invalid(
                listOf(
                    OfflineRegionArtifactValidationError(
                        OfflineRegionArtifactErrorCode.CHECKSUM_MISMATCH,
                        OfflineRegionComponent.SEARCH,
                    ),
                ),
            ),
            failure = OfflineRegionInstallFailure.ARTIFACT_INVALID,
        )

        val retained = assertIs<OfflineRegionActivationDecision.RetainPrevious>(decision)
        assertEquals(previous, retained.previous)
        assertTrue(retained.deleteStaging)
    }

    @Test
    fun malformed_manifest_cannot_activate_even_if_artifacts_match() {
        val candidate = validRelease()
        val error = OfflineRegionManifestValidationError(
            code = OfflineRegionManifestErrorCode.INVALID_SIGNATURE,
            detail = "signature",
        )

        val decision = OfflineRegionActivationPolicy.decide(
            previous = candidate,
            candidate = candidate,
            manifestErrors = listOf(error),
            artifacts = OfflineRegionArtifactValidation.Valid(candidate),
            failure = null,
        )

        val retained = assertIs<OfflineRegionActivationDecision.RetainPrevious>(decision)
        assertEquals(
            listOf(OfflineRegionActivationError.Manifest(error)),
            retained.errors,
        )
    }

    private fun validRelease(version: String = "2026.09.1") = OfflineRegionPackageManifest(
        schemaVersion = 2,
        regionId = "ru-sve-yekaterinburg-agglomeration",
        releaseVersion = version,
        createdAt = "2026-09-03T00:00:00Z",
        source = OfflineRegionSource(1L, "2026-09-02T00:00:00Z"),
        compatibility = OfflineRegionCompatibility(28, "valhalla", "tiles-1", 1, 1),
        coverage = OfflineRegionCoverage(listOf(59.55, 56.30, 61.45, 57.25), 30),
        components = OfflineRegionComponents(
            routing = OfflineRegionRoutingArtifact("https://cdn.test/routing", 35L, 90L, checksum, "zstd"),
            search = OfflineRegionSearchArtifact("https://cdn.test/search", 20L, 45L, checksum, 1),
            map = OfflineRegionMapArtifact("https://cdn.test/map", 80L, 95L, checksum, "pmtiles", 7, 16, 1),
        ),
        signature = OfflineRegionManifestSignature("key", "ed25519", "signature"),
    )

    private companion object {
        val checksum = "a".repeat(64)
    }
}
