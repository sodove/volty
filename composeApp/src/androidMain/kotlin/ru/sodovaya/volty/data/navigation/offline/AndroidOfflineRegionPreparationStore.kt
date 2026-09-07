package ru.sodovaya.volty.data.navigation.offline

import android.content.Context
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapStyleVariant
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPreparationStore

/** Small durable marker; MapLibre itself remains the source of tile bytes and pack identity. */
class AndroidOfflineRegionPreparationStore(context: Context) : OfflineRegionPreparationStore {
    private val preferences = context.getSharedPreferences("offline-region-preparation", Context.MODE_PRIVATE)
    override fun completed(regionId: String, style: OfflineMapStyleVariant, revision: String?): Boolean =
        preferences.getBoolean(key(regionId, style, revision), false)

    override fun markCompleted(regionId: String, style: OfflineMapStyleVariant, revision: String?) {
        preferences.edit()
            .putBoolean(key(regionId, style, revision), true)
            .putString("$regionId|style", style.name)
            .apply()
    }

    override fun lastCompletedStyle(regionId: String): OfflineMapStyleVariant? =
        preferences.getString("$regionId|style", null)?.let { runCatching { OfflineMapStyleVariant.valueOf(it) }.getOrNull() }

    private fun key(regionId: String, style: OfflineMapStyleVariant, revision: String?) =
        "$regionId|${style.name}|${revision.orEmpty()}"
}
