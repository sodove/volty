package ru.sodovaya.volty.data.navigation.offline

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackDefinition
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackKey
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapStyleVariant
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds

/**
 * Encodes the durable identity stored by MapLibre beside an offline region.
 * Tile bytes remain exclusively in MapLibre's offline database.
 */
class AndroidOfflineMapPackStore(
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) {
    fun encode(definition: OfflineMapPackDefinition, ofmRevision: String? = null): ByteArray =
        json.encodeToString(
            StoredMetadata(
                regionId = definition.key.regionId,
                styleVariant = definition.key.style.name,
                styleUrl = definition.key.ofmStyleUrl,
                bounds = definition.bounds,
                minZoom = definition.minZoom,
                maxZoom = definition.maxZoom,
                tileLimit = definition.tileLimit,
                ofmRevision = ofmRevision,
            ),
        ).encodeToByteArray()

    fun decode(bytes: ByteArray): OfflineMapPackMetadata? = runCatching {
        val stored = json.decodeFromString<StoredMetadata>(bytes.decodeToString())
        require(stored.schemaVersion in LEGACY_SCHEMA_VERSION..METADATA_SCHEMA_VERSION)
        val style = OfflineMapStyleVariant.valueOf(stored.styleVariant)
        val tileLimit = when (stored.schemaVersion) {
            LEGACY_SCHEMA_VERSION -> stored.tileLimit ?: LEGACY_TILE_LIMIT
            else -> requireNotNull(stored.tileLimit)
        }
        require(stored.regionId.isNotBlank())
        require(stored.styleUrl.isNotBlank())
        require(stored.minZoom >= 0 && stored.maxZoom >= stored.minZoom)
        require(tileLimit > 0)
        OfflineMapPackMetadata(
            key = OfflineMapPackKey(stored.regionId, style, stored.styleUrl),
            bounds = stored.bounds,
            minZoom = stored.minZoom,
            maxZoom = stored.maxZoom,
            tileLimit = tileLimit,
            ofmRevision = stored.ofmRevision,
        )
    }.getOrNull()

    @Serializable
    private data class StoredMetadata(
        val schemaVersion: Int = METADATA_SCHEMA_VERSION,
        val regionId: String,
        val styleVariant: String,
        val styleUrl: String,
        val bounds: OfflineRegionBounds,
        val minZoom: Int,
        val maxZoom: Int,
        val tileLimit: Int? = null,
        val ofmRevision: String?,
    )

    private companion object {
        const val LEGACY_SCHEMA_VERSION = 1
        const val METADATA_SCHEMA_VERSION = 2
        const val LEGACY_TILE_LIMIT = 30_000
    }
}

data class OfflineMapPackMetadata(
    val key: OfflineMapPackKey,
    val bounds: OfflineRegionBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val tileLimit: Int,
    val ofmRevision: String?,
) {
    val regionId: String get() = key.regionId
    val style: OfflineMapStyleVariant get() = key.style
    val styleUrl: String get() = key.ofmStyleUrl
}
