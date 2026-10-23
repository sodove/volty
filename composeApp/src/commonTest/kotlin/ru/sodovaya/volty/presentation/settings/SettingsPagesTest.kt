package ru.sodovaya.volty.presentation.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import ru.sodovaya.volty.domain.audio.MicProcessingSettings
import ru.sodovaya.volty.domain.audio.VoiceListenSettings
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifest
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifestParseResult
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageManifestCodec
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageState
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageStatus
import ru.sodovaya.volty.domain.navigation.region.v3ManifestJson

class SettingsPagesTest {
    @Test
    fun backClosesTheOpenPageFirst() {
        var exits = 0
        val nav = SettingsPageNavigator { exits += 1 }
        assertNull(nav.page.value)

        nav.open(SettingsPage.VOICE)
        assertEquals(SettingsPage.VOICE, nav.page.value)

        nav.back()
        assertNull(nav.page.value)
        assertEquals(0, exits, "closing a page must not leave the settings screen")

        nav.back()
        assertEquals(1, exits)
    }

    @Test
    fun offlineSummaryCountsOnlyInstalledRegions() {
        val manifest = (OfflineRegionPackageManifestCodec.parse(v3ManifestJson()) as OfflineRegionManifestParseResult.Success).manifest
        val states = listOf(
            region("a", OfflineRegionPackageStatus.READY, manifest),
            region("b", OfflineRegionPackageStatus.UPDATE_AVAILABLE, manifest),
            region("c", OfflineRegionPackageStatus.NOT_INSTALLED, manifest),
            region("d", OfflineRegionPackageStatus.FAILED, manifest),
            region("e", OfflineRegionPackageStatus.DOWNLOADING, manifest),
            region("f", OfflineRegionPackageStatus.INSTALLING, null),
        )

        val summary = offlineMapsSummary(states)

        assertEquals(2, summary.installedRegions)
        assertEquals(2 * 135L, summary.installedBytes)
        assertEquals(2, summary.downloading)
        assertEquals(OfflineMapsSummary(0, 0L, 0), offlineMapsSummary(emptyList()))
    }

    @Test
    fun micSummaryListsEnabledStagesInOrder() {
        val allOff = MicProcessingSettings(autoGain = false, phoneNoiseSuppressor = false, speechGate = false)
        assertEquals(emptyList(), micSummaryParts(allOff))
        assertEquals(
            listOf(MicSummaryPart.AUTO_GAIN, MicSummaryPart.SPEECH_GATE),
            micSummaryParts(allOff.copy(autoGain = true, speechGate = true)),
        )
        assertEquals(
            listOf(MicSummaryPart.AUTO_GAIN, MicSummaryPart.PHONE_NS, MicSummaryPart.SPEECH_GATE),
            micSummaryParts(allOff.copy(autoGain = true, phoneNoiseSuppressor = true, speechGate = true)),
        )
    }

    @Test
    fun voiceSummaryReportsGainAndDuck() {
        assertEquals(-4f to false, voiceSummary(VoiceListenSettings(duckMusicWhileSpeaking = false, masterGainDb = -4f)))
        assertEquals(3f to true, voiceSummary(VoiceListenSettings(duckMusicWhileSpeaking = true, masterGainDb = 3f)))
    }

    @Test
    fun everyPageIsListedOnce() {
        assertEquals(5, SettingsPage.entries.size)
        assertEquals(
            setOf("ALERT_SOUND", "VOICE", "MICROPHONE", "OFFLINE_MAPS", "DEVELOPER"),
            SettingsPage.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun smallSectionsAreInlineNotPages() {
        val pages = SettingsPage.entries.map { it.name }.toSet()
        val inline = SettingsInlineSection.entries.map { it.name }.toSet()
        assertEquals(setOf("DASHBOARD", "UNITS", "CONNECTION", "THEME"), inline)
        // Reachable exactly once: no name is both a page and an inline section.
        assertEquals(emptySet(), pages intersect inline)
    }

    private fun region(
        id: String,
        status: OfflineRegionPackageStatus,
        release: ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageManifest?,
    ) = OfflineRegionPackageState(
        region = OfflineRegionManifest(id, id, OfflineRegionBounds(56.0, 59.0, 57.5, 62.0)),
        latestRelease = release?.copy(regionId = id),
        status = status,
    )
}
