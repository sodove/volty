package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class LegacyRecordedRangeTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    @AfterTest fun close() { drivers.forEach { it.close() } }
    private fun fixture(): Pair<VoltyDatabaseProvider, LegacyHistoryReader> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY); drivers += driver
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        provider.database.rideRowQueries.insert("old","bike","2026-10-13T00:00:00Z",null)
        return provider to LegacyHistoryReader(provider)
    }
    private fun epoch(text: String) = Instant.parse(text).toEpochMilliseconds()

    @Test fun boundsUseAllRecordedFactsAndExactOffsetFractionsWithoutClosingTheRide() = runTest {
        val (provider,reader) = fixture(); val db = provider.database
        db.ridePointRowQueries.insert("old","SPEED","2026-10-12T23:59:59.001Z",-1,1.0,1)
        db.ridePointRowQueries.insert("old","POWER","2026-10-13T00:00:00.999999999Z",-1,0.0,0)
        db.ridePointRowQueries.insert("old","FUTURE_FIELD","2026-10-13T02:00:00.987654321+01:00",-1,42.0,0)
        assertEquals(HistoryRange(epoch("2026-10-12T23:59:59.001Z"),epoch("2026-10-13T01:00:00.987Z")),reader.legacyRecordedRange("old"))
        assertNull(reader.ride("old")!!.ended)
        assertNull(db.rideRowQueries.selectById("old").executeAsOne().endedAt)
        assertEquals(3,db.ridePointRowQueries.selectByRide("old").executeAsList().size)
    }

    @Test fun emptyLegacyKeepsOnlyItsRecordedStartAndMissingRideStaysMissing() = runTest {
        val (_,reader) = fixture(); val start = epoch("2026-10-13T00:00:00Z")
        assertEquals(HistoryRange(start,start),reader.legacyRecordedRange("old"))
        assertNull(reader.legacyRecordedRange("missing"))
    }

    @Test fun unifiedFacadeUsesLegacyWindowButNeverBorrowsAShadowForV2() = runTest {
        val (provider,_) = fixture(); val db = provider.database
        db.ridePointRowQueries.insert("old","SPEED","2026-10-13T00:00:02Z",-1,7.0,1)
        val facade = SqlDelightHistoryReadRepository(provider)
        assertEquals(HistoryRange(epoch("2026-10-13T00:00:00Z"),epoch("2026-10-13T00:00:02Z")),facade.legacyRecordedRange("old"))
        SqlDelightHistoryStore(provider).begin(HistoryRide("old","bike",HistoryTick(1000,10,1),null,HistoryRideStatus.RECORDING),
            listOf(HistorySource("pack",HistorySourceKind.BATTERY,"Pack")))
        assertNull(facade.legacyRecordedRange("old"))
        assertNull(facade.ride("old")!!.ended)
    }

    @Test fun malformedStoredTimeFailsRatherThanSubstitutingToday() = runTest {
        val (provider,reader) = fixture()
        provider.database.ridePointRowQueries.insert("old","SPEED","not-a-time",-1,2.0,1)
        assertFailsWith<IllegalStateException> { reader.legacyRecordedRange("old") }
        assertNull(reader.ride("old")!!.ended)
    }

    @Test fun unknownOnlyTailStillEarnsAnAvailableTimeBoundary() = runTest {
        val (provider,reader) = fixture()
        provider.database.ridePointRowQueries.insert("old","CURRENT","2026-10-13T00:00:05.0001Z",-1,999.0,0)
        assertEquals(HistoryRange(epoch("2026-10-13T00:00:00Z"),epoch("2026-10-13T00:00:05Z")),reader.legacyRecordedRange("old"))
    }
}
