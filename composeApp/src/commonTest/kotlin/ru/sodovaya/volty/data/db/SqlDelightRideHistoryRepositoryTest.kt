package ru.sodovaya.volty.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.repository.RidePoint
import ru.sodovaya.volty.domain.repository.RideSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTime::class)
class SqlDelightRideHistoryRepositoryTest {
    private val start = Instant.parse("2026-08-09T01:00:00Z")

    private fun repo(): SqlDelightRideHistoryRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        return SqlDelightRideHistoryRepository(VoltyDatabaseProvider(driver))
    }

    private fun ride(id: String, startedAt: Instant = start) = RideSummary(
        id = id,
        vehicleId = "vehicle",
        startedAt = startedAt,
        endedAt = null
    )

    @Test
    fun `ride and ordered points round trip with known bit`() = runTest {
        val repository = repo()
        repository.startRide(ride("ride-1"))
        repository.appendPoint("ride-1", RidePoint("SPEED", start + 2.seconds, 32f))
        repository.appendPoint("ride-1", RidePoint("SPEED", start, 30f))
        repository.appendPoint("ride-1", RidePoint("POWER", start, 99f, isKnown = false))
        repository.finishRide("ride-1", start + 10.seconds)

        val stored = assertNotNull(repository.loadRide("ride-1"))
        assertEquals(start + 10.seconds, stored.summary.endedAt)
        assertEquals(listOf("POWER", "SPEED", "SPEED"), stored.points.map { it.metricKey })
        assertFalse(stored.points.first().isKnown)
    }

    @Test
    fun `delete removes ride and points`() = runTest {
        val repository = repo()
        repository.startRide(ride("ride-1"))
        repository.appendPoint("ride-1", RidePoint("SOC", start, 80f))
        repository.deleteRide("ride-1")
        assertNull(repository.loadRide("ride-1"))
        assertEquals(emptyList(), repository.listRides())
    }

    @Test
    fun `prune keeps newest completed rides`() = runTest {
        val repository = repo()
        repository.startRide(ride("old", start))
        repository.startRide(ride("new", start + 1.hours))
        repository.startRide(ride("newest", start + 2.hours))
        repository.pruneOldest(keep = 2)
        assertEquals(listOf("newest", "new"), repository.listRides().map { it.id })
    }
}
