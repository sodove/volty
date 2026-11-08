package ru.sodovaya.volty.data.history

import app.cash.sqldelight.db.SqlDriver

/**
 * Converts one legacy ride atomically with its compact key and legacy-row removal.
 * The caller owns the write transaction and must roll it back on error.
 * This reuses the run encoding from 28.sqm. A single large ride can still
 * take a long time and needs finer checkpoints before scheduling in the background.
 */
internal fun convertLegacyRide(driver: SqlDriver, rideId: String) {
    checkNotNull(driver.currentTransaction()) { "Legacy conversion requires a write transaction" }
    val pending = driver.executeQuery(null, """
        SELECT id FROM HistoryRideV2 WHERE id = ?
          AND NOT EXISTS (SELECT 1 FROM HistoryRideKeyV3 WHERE rideId = HistoryRideV2.id)
    """.trimIndent(), { cursor -> cursor.next() }, 1) { bindString(0, rideId) }.value
    if (!pending) return

    driver.execute(null, "INSERT INTO HistoryRideKeyV3(rideId) VALUES (?)", 1) { bindString(0, rideId) }
    for (statement in legacyConversionStatements) {
        if ('?' in statement) {
            driver.execute(null, statement, 1) { bindString(0, rideId) }
        } else {
            driver.execute(null, statement, 0)
        }
    }
    driver.execute(null, "DELETE FROM HistoryBatchReceiptV2 WHERE rideId = ? AND fingerprint IS NULL", 1) {
        bindString(0, rideId)
    }
    driver.execute(null, "DELETE FROM HistorySampleV2 WHERE rideId = ?", 1) { bindString(0, rideId) }
}

private val legacyConversionStatements = listOf(
    """
    CREATE TEMP TABLE HistoryConversionRide (rideNo INTEGER NOT NULL PRIMARY KEY, rideId TEXT NOT NULL, originSkew INTEGER NOT NULL,
      clockGeneration INTEGER NOT NULL)
    """.trimIndent(),
    """
    INSERT INTO HistoryConversionRide(rideNo, rideId, originSkew, clockGeneration)
    SELECT k.rideNo, r.id, r.startedEpochMillis - r.startedElapsedMillis, r.clockGeneration
    FROM HistoryRideV2 r JOIN HistoryRideKeyV3 k ON k.rideId = r.id WHERE r.id = ?
    """.trimIndent(),
    """
    INSERT INTO HistorySeriesV3(rideNo, sourceId, metricId, cellIndex)
    SELECT DISTINCT h.rideNo, s.sourceId, s.metricId, s.cellIndex
    FROM HistoryConversionRide h CROSS JOIN HistorySampleV2 s ON s.rideId = h.rideId
    ORDER BY h.rideNo, s.sourceId, s.metricId, s.cellIndex
    """.trimIndent(),
    """
    CREATE TEMP TABLE HistoryConversionRow (pos INTEGER NOT NULL PRIMARY KEY, seriesId INTEGER NOT NULL, elapsedMillis INTEGER NOT NULL,
      sequence INTEGER NOT NULL, epochSkew INTEGER NOT NULL, clockDelta INTEGER NOT NULL, reading REAL, unknownCode INTEGER,
      CHECK((reading IS NULL) <> (unknownCode IS NULL)))
    """.trimIndent(),
    """
    INSERT INTO HistoryConversionRow(seriesId, elapsedMillis, sequence, epochSkew, clockDelta, reading, unknownCode)
    SELECT v.seriesId, s.elapsedMillis, s.sequence, s.epochMillis - s.elapsedMillis - h.originSkew, s.clockGeneration - h.clockGeneration,
      s.reading, CASE s.unknownReason WHEN 'UNAVAILABLE' THEN 1 WHEN 'PARTIAL_SOURCE' THEN 2 WHEN 'INVALID_VALUE' THEN 3 WHEN 'GAP' THEN 4
        WHEN 'LEGACY' THEN 5 END
    FROM HistoryConversionRide h CROSS JOIN HistorySeriesV3 v ON v.rideNo = h.rideNo
    CROSS JOIN HistorySampleV2 s ON s.rideId = h.rideId AND s.sourceId = v.sourceId AND s.metricId = v.metricId AND s.cellIndex = v.cellIndex
    ORDER BY h.rideNo, v.sourceId, v.metricId, v.cellIndex, s.elapsedMillis, s.sequence
    """.trimIndent(),
    """
    CREATE TEMP TABLE HistoryConversionBreak (seriesId INTEGER NOT NULL, elapsedMillis INTEGER NOT NULL,
      PRIMARY KEY(seriesId, elapsedMillis)) WITHOUT ROWID
    """.trimIndent(),
    """
    INSERT OR IGNORE INTO HistoryConversionBreak(seriesId, elapsedMillis)
    SELECT v.seriesId, m.elapsedMillis
    FROM HistoryConversionRide h CROSS JOIN HistorySeriesV3 v ON v.rideNo = h.rideNo CROSS JOIN HistoryMarkerV2 m ON m.rideId = h.rideId
    WHERE m.kind IN ('BLE_GAP', 'CLOCK_CHANGE', 'OVERFLOW', 'INTERRUPTED', 'CONNECTED') AND (m.sourceId IS NULL OR m.sourceId = v.sourceId)
    """.trimIndent(),
    """
    -- NOT EXISTS and not NOT(a AND b): a known neighbour makes the comparison NULL, and NOT(NULL) would lose the row.
    CREATE TEMP TABLE HistoryConversionHead (pos INTEGER NOT NULL PRIMARY KEY)
    """.trimIndent(),
    """
    INSERT INTO HistoryConversionHead(pos)
    SELECT n.pos FROM HistoryConversionRow n WHERE NOT EXISTS (SELECT 1 FROM HistoryConversionRow p
      WHERE p.pos = n.pos - 1 AND p.seriesId = n.seriesId AND p.unknownCode = n.unknownCode AND p.clockDelta = n.clockDelta
        AND n.elapsedMillis - p.elapsedMillis <= 2000 AND n.sequence > p.sequence AND ABS(n.epochSkew - p.epochSkew) <= 15
        AND n.elapsedMillis + n.epochSkew >= p.elapsedMillis + p.epochSkew
        AND NOT EXISTS (SELECT 1 FROM HistoryConversionBreak b
          WHERE b.seriesId = n.seriesId AND b.elapsedMillis > p.elapsedMillis AND b.elapsedMillis <= n.elapsedMillis))
    """.trimIndent(),
    """
    INSERT INTO HistorySampleV3(seriesId, elapsedMillis, sequence, epochSkew, clockDelta, reading, unknownCode,
      runEndElapsed, runEndSequence, runEndEpochSkew, runCount)
    SELECT f.seriesId, f.elapsedMillis, f.sequence, f.epochSkew, f.clockDelta, f.reading, f.unknownCode,
      CASE WHEN e.pos > f.pos THEN e.elapsedMillis END, CASE WHEN e.pos > f.pos THEN e.sequence END,
      CASE WHEN e.pos > f.pos THEN e.epochSkew END, CASE WHEN e.pos > f.pos THEN e.pos - f.pos + 1 END
    FROM HistoryConversionHead h CROSS JOIN HistoryConversionRow f ON f.pos = h.pos
    CROSS JOIN HistoryConversionRow e ON e.pos = COALESCE((SELECT MIN(x.pos) FROM HistoryConversionHead x WHERE x.pos > h.pos),
      (SELECT MAX(r.pos) FROM HistoryConversionRow r) + 1) - 1
    """.trimIndent(),
    """
    DROP TABLE temp.HistoryConversionHead
    """.trimIndent(),
    """
    DROP TABLE temp.HistoryConversionBreak
    """.trimIndent(),
    """
    DROP TABLE temp.HistoryConversionRow
    """.trimIndent(),
    """
    DROP TABLE temp.HistoryConversionRide
    """.trimIndent(),
)
