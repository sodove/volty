package ru.sodovaya.volty.presentation.settings

import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifest
import kotlin.math.abs

/** Keeps server-provided names and renders legacy grid labels without user input. */
internal object OfflineRegionDisplayNamePolicy {
    private val genericGridLabel = Regex("^(?:регион|region)\\s+\\d+[–-]\\d+$", RegexOption.IGNORE_CASE)

    fun displayName(region: OfflineRegionManifest): String {
        val raw = region.displayName.trim()
        if (!genericGridLabel.matches(raw)) return raw
        val bounds = region.bounds
        return "${coordinate(bounds.south)}–${coordinate(bounds.north)}° с.ш. · " +
            "${coordinate(bounds.west)}–${coordinate(bounds.east)}° в.д."
    }

    private fun coordinate(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        return if (abs(rounded - rounded.toLong()) < 0.0001) rounded.toLong().toString()
        else rounded.toString()
    }
}
