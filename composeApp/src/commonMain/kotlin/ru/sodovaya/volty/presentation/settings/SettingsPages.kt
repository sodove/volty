package ru.sodovaya.volty.presentation.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.audio.MicProcessingSettings
import ru.sodovaya.volty.domain.audio.VoiceListenSettings
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageState
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageStatus

/** One settings page opened from the hub. Each existing setting lives on exactly one of these. */
enum class SettingsPage {
    DASHBOARD, UNITS, CONNECTION, ALERT_SOUND, VOICE, MICROPHONE, OFFLINE_MAPS, THEME, DEVELOPER
}

/**
 * Which page of the settings hub is open. Back closes an open page before it leaves the screen,
 * so the title-bar arrow and the system back gesture behave alike.
 */
class SettingsPageNavigator(private val onExit: () -> Unit) {
    private val _page = MutableStateFlow<SettingsPage?>(null)
    val page: StateFlow<SettingsPage?> = _page.asStateFlow()

    fun open(page: SettingsPage) { _page.value = page }

    fun back() {
        if (_page.value != null) _page.value = null else onExit()
    }
}

data class OfflineMapsSummary(val installedRegions: Int, val installedBytes: Long, val downloading: Int)

/** A region counts as installed once a release is on disk, including one with an update waiting. */
fun offlineMapsSummary(regions: List<OfflineRegionPackageState>): OfflineMapsSummary {
    val installed = regions.filter {
        it.status == OfflineRegionPackageStatus.READY || it.status == OfflineRegionPackageStatus.UPDATE_AVAILABLE
    }
    return OfflineMapsSummary(
        installedRegions = installed.size,
        installedBytes = installed.sumOf { it.totalInstalledBytes },
        downloading = regions.count {
            when (it.status) {
                OfflineRegionPackageStatus.DOWNLOADING,
                OfflineRegionPackageStatus.PREPARING,
                OfflineRegionPackageStatus.VERIFYING,
                OfflineRegionPackageStatus.INSTALLING -> true
                else -> false
            }
        },
    )
}

enum class MicSummaryPart { AUTO_GAIN, PHONE_NS, SPEECH_GATE }

/** The enabled processing stages in signal order; empty means the microphone is passed through. */
fun micSummaryParts(mic: MicProcessingSettings): List<MicSummaryPart> = buildList {
    if (mic.autoGain) add(MicSummaryPart.AUTO_GAIN)
    if (mic.phoneNoiseSuppressor) add(MicSummaryPart.PHONE_NS)
    if (mic.speechGate) add(MicSummaryPart.SPEECH_GATE)
}

/** Master listen gain in dB and whether music is ducked while someone speaks. */
fun voiceSummary(listen: VoiceListenSettings): Pair<Float, Boolean> =
    listen.masterGainDb to listen.duckMusicWhileSpeaking
