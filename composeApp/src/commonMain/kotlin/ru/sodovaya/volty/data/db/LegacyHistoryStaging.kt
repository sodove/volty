package ru.sodovaya.volty.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

internal fun hasLegacyHistory(driver: SqlDriver): Boolean = driver.executeQuery(
    null,
    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'HistorySampleV2'",
    { it.next() },
    0,
).value

internal fun nextLegacyRide(driver: SqlDriver): String? = driver.executeQuery(
    null,
    """
        SELECT r.id FROM HistoryRideV2 r
        WHERE NOT EXISTS (SELECT 1 FROM HistoryRideKeyV3 k WHERE k.rideId = r.id)
        ORDER BY r.startedEpochMillis DESC, r.id DESC LIMIT 1
    """.trimIndent(),
    { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
    0,
).value

internal fun pendingLegacyRideCount(driver: SqlDriver): Long {
    if (!hasLegacyHistory(driver)) return 0
    return driver.executeQuery(
        null,
        """
            SELECT COUNT(*) FROM HistoryRideV2 r
            WHERE NOT EXISTS (SELECT 1 FROM HistoryRideKeyV3 k WHERE k.rideId = r.id)
        """.trimIndent(),
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0 else 0) },
        0,
    ).value
}
