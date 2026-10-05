package ru.sodovaya.volty.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.voice.*
import ru.sodovaya.volty.domain.nearby.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SqlDelightChannelSharingRevokeJournalTest {
    private val actor="10000000-0000-0000-0000-000000000001"
    private val other="10000000-0000-0000-0000-000000000002"
    private fun id(n: Int)="90000000-0000-0000-0000-${n.toString().padStart(12,'0')}"
    private fun intent(n: Int=1,request: Int=1001,who: String=actor)=ChannelSharingRevokeIntent(who,ChannelSharingTag(id(999),id(n)),id(request))

    @Test fun originalRecordSurvivesActualFileCloseReopenAndDuplicateDoesNotReplaceNonce() = runTest {
        val file=Files.createTempFile("nearby-revoke-", ".db").toFile();file.deleteOnExit()
        val dispatcher=StandardTestDispatcher(testScheduler)
        var driver=JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            VoltyDatabase.Schema.create(driver)
            val first=intent();val journal=SqlDelightChannelSharingRevokeJournal(VoltyDatabaseProvider(driver),dispatcher)
            assertEquals(first,journal.record(first));assertEquals(first,journal.record(intent(request=1002)))
            driver.close();driver=JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            val restored=SqlDelightChannelSharingRevokeJournal(VoltyDatabaseProvider(driver),dispatcher)
            assertEquals(listOf(first),restored.pending(actor,64));assertTrue(restored.pending(other,64).isEmpty())
        } finally {driver.close();file.delete()}
    }

    @Test fun onlyExactAuthenticatedActorAndNegativeOriginalTagCanAcknowledge() = runTest {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            VoltyDatabase.Schema.create(driver);val journal=SqlDelightChannelSharingRevokeJournal(VoltyDatabaseProvider(driver),StandardTestDispatcher(testScheduler))
            val original=intent();val next=intent(2);val foreign=intent(who=other)
            listOf(original,next,foreign).forEach {journal.record(it)}
            assertFalse(journal.acknowledge(original,actor,ChannelSharingReply.Failed(ChannelSharingFailure.UNAVAILABLE)))
            assertFalse(journal.acknowledge(original,actor,ChannelSharingReply.Failed(ChannelSharingFailure.ACCESS_DENIED)))
            assertFalse(journal.acknowledge(original,other,ChannelSharingReply.Negative(original.tag,ChannelSharingNegativePhase.REVOKED)))
            assertFalse(journal.acknowledge(original,actor,ChannelSharingReply.Negative(next.tag,ChannelSharingNegativePhase.REVOKED)))
            assertEquals(listOf(original,next),journal.pending(actor,64))
            assertTrue(journal.acknowledge(original,actor,ChannelSharingReply.Negative(original.tag,ChannelSharingNegativePhase.EXPIRED)))
            assertEquals(listOf(next),journal.pending(actor,64));assertEquals(listOf(foreign),journal.pending(other,64))
        } finally {driver.close()}
    }

    @Test fun lateOldAcknowledgementCannotDeleteLaterNonceForSameOriginalTag() = runTest {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            VoltyDatabase.Schema.create(driver);val journal=SqlDelightChannelSharingRevokeJournal(VoltyDatabaseProvider(driver),StandardTestDispatcher(testScheduler))
            val first=intent();val reply=ChannelSharingReply.Negative(first.tag,ChannelSharingNegativePhase.REVOKED)
            journal.record(first);assertTrue(journal.acknowledge(first,actor,reply))
            val next=intent(request=1002);assertEquals(next,journal.record(next))
            assertFalse(journal.acknowledge(first,actor,reply));assertEquals(listOf(next),journal.pending(actor,64))
            assertTrue(journal.acknowledge(next,actor,reply));assertTrue(journal.pending(actor,64).isEmpty())
        } finally {driver.close()}
    }

    @Test fun boundedAdmissionPreservesExistingRecordAtCapacityAndDoesNotAffectAnotherActor() = runTest {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            VoltyDatabase.Schema.create(driver);val journal=SqlDelightChannelSharingRevokeJournal(VoltyDatabaseProvider(driver),StandardTestDispatcher(testScheduler))
            (1..256).forEach {journal.record(intent(it,1000+it))}
            assertFailsWith<IllegalStateException> {journal.record(intent(257,1257))}
            assertEquals(intent(1,1001),journal.record(intent(1,2000)))
            assertEquals(1,journal.pending(actor,1).size);assertEquals(64,journal.pending(actor,64).size)
            assertFailsWith<IllegalArgumentException> {journal.pending(actor,0)}
            assertFailsWith<IllegalArgumentException> {journal.pending(actor,65)}
            val foreign=intent(who=other);assertEquals(foreign,journal.record(foreign));assertEquals(listOf(foreign),journal.pending(other,64))
        } finally {driver.close()}
    }
}
