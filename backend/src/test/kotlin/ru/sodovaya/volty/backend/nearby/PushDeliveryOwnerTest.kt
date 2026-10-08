package ru.sodovaya.volty.backend.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PushDeliveryOwnerTest {
    @Test fun failureRetriesOnNextPulseWithoutCancellingBorrowedRoot() = runBlocking {
        val root = SupervisorJob(); val pulses = Channel<Unit>(1); val issued = Channel<Int>(2)
        val calls = AtomicInteger()
        val owner = PushDeliveryOwner.start(CoroutineScope(root + Dispatchers.Default), pulses.receiveAsFlow(), PushDeliveryTick {
            val count = calls.incrementAndGet(); issued.trySend(count)
            if (count == 1) error("private transport error")
            false
        })
        try {
            pulses.send(Unit); assertEquals(1, withTimeout(2000) { issued.receive() })
            pulses.send(Unit); assertEquals(2, withTimeout(2000) { issued.receive() })
            assertEquals(1L, owner.state.value.failures); assertTrue(root.isActive)
        } finally { owner.close(); pulses.close(); root.cancel() }
    }

    @Test fun closeInterruptsActualBlockingProducerAndKeepsBorrowedRoot() = runBlocking {
        val root = SupervisorJob(); val pulses = Channel<Unit>(1)
        val entered = CompletableDeferred<Unit>(); val interrupted = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val owner = PushDeliveryOwner.start(CoroutineScope(root + Dispatchers.Default), pulses.receiveAsFlow(), PushDeliveryTick {
            calls.incrementAndGet(); entered.complete(Unit)
            try { CountDownLatch(1).await(5, TimeUnit.SECONDS) }
            catch (failure: InterruptedException) { interrupted.complete(Unit); throw failure }
            false
        })
        try {
            pulses.send(Unit); withTimeout(2000) { entered.await() }
            owner.close(); withTimeout(2000) { interrupted.await(); owner.stopped.first { it } }
            assertTrue(owner.close()); assertFalse(owner.state.value.producerPending)
            assertEquals(0, owner.state.value.inFlight); assertTrue(root.isActive); assertEquals(1, calls.get())
        } finally { owner.close(); pulses.close(); root.cancel() }
    }

    @Test fun cancelledRootCannotPretendInterruptIgnoringProducerHasFinished() = runBlocking {
        val root = SupervisorJob(); val pulses = Channel<Unit>(1); val release = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>(); val interrupted = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val owner = PushDeliveryOwner.start(CoroutineScope(root + Dispatchers.Default), pulses.receiveAsFlow(), PushDeliveryTick {
            calls.incrementAndGet(); entered.complete(Unit)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (release.count > 0 && System.nanoTime() < deadline) {
                try { release.await(100, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { interrupted.complete(Unit) }
            }
            true
        })
        try {
            pulses.send(Unit); withTimeout(2000) { entered.await() }; pulses.send(Unit); root.cancel()
            withTimeout(2000) { interrupted.await() }
            assertFalse(owner.close()); assertTrue(owner.state.value.producerPending); assertFalse(owner.stopped.value)
            release.countDown(); withTimeout(2000) { owner.stopped.first { it } }
            assertTrue(owner.close()); assertEquals(1, calls.get()); assertFalse(owner.state.value.wanted)
        } finally { release.countDown(); owner.close(); pulses.close(); root.cancel() }
    }

    @Test fun refusesMissingOrCancelledApplicationRoot() {
        val pulses = Channel<Unit>(1)
        try {
            assertFailsWith<IllegalStateException> { PushDeliveryOwner.start(object : CoroutineScope { override val coroutineContext = Dispatchers.Default }, pulses.receiveAsFlow(), PushDeliveryTick { false }) }
            val root = Job().also { it.cancel() }
            assertFailsWith<IllegalStateException> { PushDeliveryOwner.start(CoroutineScope(root), pulses.receiveAsFlow(), PushDeliveryTick { false }) }
        } finally { pulses.close() }
    }
}
