package ru.sodovaya.volty.backend.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NearbyMaintenanceOwnerTest {
    @Test fun oneSessionFailureDoesNotStarveWebhookAndNextPulseRetries() = runBlocking {
        val root=SupervisorJob();val scope=CoroutineScope(root+Dispatchers.Default)
        val pulses=Channel<Unit>(Channel.UNLIMITED);val issued=Channel<String>(Channel.UNLIMITED);val attempts=AtomicInteger()
        val port=object : NearbyMaintenancePort {
            override fun reconcileSession(): Boolean {issued.trySend("session");if(attempts.incrementAndGet()==1) error("sensitive transport exception must stay private");return false}
            override fun reconcileWebhook(): Boolean {issued.trySend("webhook");return false}
        }
        val owner=NearbyMaintenanceOwner.start(scope,pulses.receiveAsFlow(),port)
        try {
            repeat(2) {pulses.send(Unit);assertEquals(listOf("session","webhook"),withTimeout(5_000) {listOf(issued.receive(),issued.receive())})}
            assertEquals(2,attempts.get());assertEquals(1L,owner.state.value.failures)
            assertTrue(root.isActive);assertTrue(owner.state.value.wanted)
        } finally {pulses.close();owner.requestStop();withTimeout(5_000) {owner.stopped.first {it}};root.cancel()}
    }

    @Test fun closeCountsActualHeldBlockingCallAndCannotIssueLateWebhookOrStopBorrowedRoot() = runBlocking {
        val root=SupervisorJob();val scope=CoroutineScope(root+Dispatchers.Default);val pulses=Channel<Unit>(Channel.UNLIMITED)
        val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1);val webhooks=AtomicInteger()
        val port=object : NearbyMaintenancePort {
            override fun reconcileSession(): Boolean {entered.complete(Unit);check(release.await(5,TimeUnit.SECONDS));return true}
            override fun reconcileWebhook(): Boolean {webhooks.incrementAndGet();return false}
        }
        val owner=NearbyMaintenanceOwner.start(scope,pulses.receiveAsFlow(),port)
        try {
            pulses.send(Unit);withTimeout(5_000) {entered.await()}
            assertFalse(owner.close());assertFalse(owner.state.value.wanted);assertTrue(owner.state.value.producerPending)
            assertFalse(owner.stopped.value);assertTrue(root.isActive);assertEquals(0,webhooks.get())
            release.countDown();withTimeout(5_000) {owner.stopped.first {it}}
            assertTrue(owner.close());assertFalse(owner.state.value.producerPending);assertEquals(0,webhooks.get());assertTrue(root.isActive)
        } finally {release.countDown();pulses.close();owner.requestStop();withTimeout(5_000) {owner.stopped.first {it}};root.cancel()}
    }

    @Test fun rootCancellationFencesNewLaneButDoesNotInventBlockingProducerCompletion() = runBlocking {
        val root=SupervisorJob();val scope=CoroutineScope(root+Dispatchers.Default);val pulses=Channel<Unit>(Channel.UNLIMITED)
        val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1);val webhooks=AtomicInteger()
        val owner=NearbyMaintenanceOwner.start(scope,pulses.receiveAsFlow(),object : NearbyMaintenancePort {
            override fun reconcileSession(): Boolean {entered.complete(Unit);check(release.await(5,TimeUnit.SECONDS));return true}
            override fun reconcileWebhook(): Boolean {webhooks.incrementAndGet();return false}
        })
        try {
            pulses.send(Unit);withTimeout(5_000) {entered.await()};root.cancel()
            assertFalse(owner.close());assertTrue(owner.state.value.producerPending);assertFalse(owner.stopped.value)
            release.countDown();withTimeout(5_000) {owner.stopped.first {it}}
            assertEquals(0,webhooks.get());assertFalse(owner.state.value.producerPending)
        } finally {release.countDown();pulses.close();owner.requestStop();withTimeout(5_000) {owner.stopped.first {it}};root.cancel()}
    }
}
