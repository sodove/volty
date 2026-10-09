package ru.sodovaya.volty.backend.nearby

import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import ru.sodovaya.volty.backend.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PushDeliveryApplicationTest {
    @Test fun blockedPushDoesNotStarveSfuMaintenanceAndApplicationStopInterruptsIt() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val interrupted = CompletableDeferred<Unit>()
        val maintenance = Channel<Unit>(4); val calls = AtomicInteger(); val closes = AtomicInteger()
        val dependencies = AppDependencies(AppConfig.forTests(), object : BackendStore {},
            nearbyMaintenance = object : NearbyMaintenancePort {
                override fun reconcileSession(): Boolean { maintenance.trySend(Unit); return false }
                override fun reconcileWebhook() = false
            }, nearbyPushDelivery = PushDeliveryTick {
                calls.incrementAndGet(); entered.complete(Unit)
                try { CountDownLatch(1).await(5, TimeUnit.SECONDS) }
                catch (failure: InterruptedException) { interrupted.complete(Unit); throw failure }
                false
            }, nearbyPushTransport = AutoCloseable { closes.incrementAndGet() })
        try {
            testApplication {
                application { module(dependencies) }; startApplication()
                withTimeout(2000) {
                    entered.await()
                    repeat(2) { maintenance.receive() }
                }
                assertEquals(1, calls.get())
            }
            withTimeout(2000) { interrupted.await() }
            assertEquals(1, calls.get())
            assertEquals(1, closes.get())
        } finally { maintenance.close() }
    }
}
