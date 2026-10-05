package ru.sodovaya.volty.backend

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import ru.sodovaya.volty.backend.nearby.NearbyMaintenancePort
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NearbyMaintenanceModuleTest {
    @Test fun explicitProductionPortRunsBothMaintenanceLanesAfterApplicationStartup() = testApplication {
        val session=CompletableDeferred<Unit>();val webhook=CompletableDeferred<Unit>()
        val port=object : NearbyMaintenancePort {
            override fun reconcileSession(): Boolean {session.complete(Unit);return false}
            override fun reconcileWebhook(): Boolean {webhook.complete(Unit);return false}
        }
        application {module(AppDependencies(AppConfig.forTests(),object : BackendStore {},nearbyMaintenance=port))}
        assertEquals(HttpStatusCode.OK,client.get("/health").status)
        withTimeout(5_000) {session.await();webhook.await()}
        assertTrue(session.isCompleted);assertTrue(webhook.isCompleted)
    }

    @Test fun testModeNeverStartsInjectedMaintenanceOrTimer() = testApplication {
        val calls=AtomicInteger()
        val port=object : NearbyMaintenancePort {
            override fun reconcileSession(): Boolean {calls.incrementAndGet();return false}
            override fun reconcileWebhook(): Boolean {calls.incrementAndGet();return false}
        }
        application {module(AppDependencies(AppConfig.forTests(),object : BackendStore {},testMode=true,nearbyMaintenance=port))}
        assertEquals(HttpStatusCode.OK,client.get("/health").status)
        assertEquals(0,calls.get())
    }
}
