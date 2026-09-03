package ru.sodovaya.volty.data.navigation.offline

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.region.OfflineGeocoder
import ru.sodovaya.volty.domain.navigation.region.OfflineGeocoderRequest
import ru.sodovaya.volty.domain.navigation.region.OfflineAutocompleteRankingPolicy
import ru.sodovaya.volty.domain.navigation.region.OfflineAutocompleteRankedRow

/**
 * Reads the regional FTS4 database produced by tools/offline-navigation.
 *
 * The database is opened read-only for each query: package replacement can then
 * happen between two searches without a long-lived SQLite handle keeping the
 * old release alive. The FTS expression comes from OfflineAutocompleteQuery,
 * never from an untrusted SQL fragment.
 */
class AndroidOfflineFtsGeocoder(
    private val databaseFile: File,
    private val regionId: String,
) : OfflineGeocoder {
    override suspend fun search(
        request: OfflineGeocoderRequest,
    ): NavigationResult<List<PlaceCandidate>> = withContext(Dispatchers.IO) {
        try {
            if (!databaseFile.isFile || databaseFile.length() <= 0L) {
                return@withContext NavigationResult.Failure(NavigationFailure.Offline)
            }
            val database = SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            try {
                validateMetadata(database)
                NavigationResult.Success(query(database, request))
            } finally {
                database.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteException) {
            NavigationResult.Failure(NavigationFailure.MalformedResponse)
        } catch (_: IllegalArgumentException) {
            NavigationResult.Failure(NavigationFailure.MalformedResponse)
        }
    }

    private fun validateMetadata(database: SQLiteDatabase) {
        database.rawQuery(
            "SELECT value FROM metadata WHERE key = ? LIMIT 1",
            arrayOf(METADATA_SCHEMA_KEY),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != SEARCH_SCHEMA_VERSION.toString()) {
                throw SQLiteException("Unsupported offline search schema")
            }
        }
        database.rawQuery(
            "SELECT value FROM metadata WHERE key = ? LIMIT 1",
            arrayOf(METADATA_REGION_KEY),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != regionId) {
                throw SQLiteException("Offline search database belongs to another region")
            }
        }
    }

    private fun query(
        database: SQLiteDatabase,
        request: OfflineGeocoderRequest,
    ): List<PlaceCandidate> {
        val rows = mutableListOf<RankedPlace>()
        val sql = """
            SELECT rowid, display_name, search_text, latitude, longitude, kind, osm_id
            FROM places
            WHERE places MATCH ?
            LIMIT ${request.query.limit * SEARCH_CANDIDATE_MULTIPLIER}
        """.trimIndent()
        database.rawQuery(sql, arrayOf(request.query.ftsMatchExpression)).use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(1)?.trim().orEmpty()
                val searchableText = cursor.getString(2)?.trim().orEmpty()
                val latitude = cursor.getDouble(3)
                val longitude = cursor.getDouble(4)
                if (title.isBlank() || !latitude.isFinite() || !longitude.isFinite()) continue
                val coordinate = runCatching { GeoCoordinate(latitude, longitude) }.getOrNull()
                    ?: continue
                val rowId = cursor.getLong(0)
                val osmId = cursor.getString(6)?.trim().orEmpty()
                val kind = cursor.getString(5)?.trim().takeIf { !it.isNullOrBlank() }
                rows += RankedPlace(
                    candidate = PlaceCandidate(
                        id = if (osmId.isNotBlank()) "$regionId:$osmId" else "$regionId:$rowId",
                        title = title,
                        subtitle = kind,
                        coordinate = coordinate,
                    ),
                    distanceSquared = request.near?.let { near ->
                        val latitudeDelta = latitude - near.latitude
                        val longitudeDelta = (longitude - near.longitude) * longitudeScale(latitude)
                        latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta
                    },
                    relevanceScore = OfflineAutocompleteRankingPolicy.score(
                        query = request.query,
                        displayName = title,
                        searchableText = searchableText,
                    ),
                    rowId = rowId,
                )
            }
        }
        return OfflineAutocompleteRankingPolicy.order(
            rows = rows.map { row ->
                OfflineAutocompleteRankedRow(
                    value = row.candidate,
                    relevanceScore = row.relevanceScore,
                    distanceSquared = row.distanceSquared,
                    stableId = row.rowId,
                )
            },
            preferProximity = request.near != null,
        ).take(request.query.limit)
    }

    private fun longitudeScale(latitude: Double): Double =
        kotlin.math.cos(Math.toRadians(latitude)).coerceAtLeast(0.1)

    private data class RankedPlace(
        val candidate: PlaceCandidate,
        val distanceSquared: Double?,
        val relevanceScore: Int,
        val rowId: Long,
    )

    private companion object {
        const val SEARCH_SCHEMA_VERSION = 1
        const val METADATA_SCHEMA_KEY = "schema"
        const val METADATA_REGION_KEY = "region_id"
        const val SEARCH_CANDIDATE_MULTIPLIER = 8
    }
}
