package ru.sodovaya.volty.backend.nearby

import com.google.auth.oauth2.ServiceAccountCredentials
import ru.sodovaya.volty.backend.*
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.security.KeyPairGenerator
import javax.sql.DataSource
import kotlin.test.*

class FcmProductionAssemblyTest {
    private val source = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
        error("Unexpected assembly database I/O: ${method.name}")
    } as DataSource
    private fun config() = AppConfig.fromEnvironment(mapOf(
        "VOLTY_JWT_SECRET" to "test-secret", "VOLTY_NEARBY_MODE" to "voice_spaces", "VOLTY_VOICE_PROVIDER" to "livekit",
        "LIVEKIT_URL" to "wss://media.invalid", "LIVEKIT_API_KEY" to "test-key", "LIVEKIT_API_SECRET" to "test-secret",
        "VOLTY_PUBLIC_IP" to "192.0.2.1", "VOLTY_NEARBY_SFU_CONTROL_ORIGIN" to "http://127.0.0.1:1",
        "VOLTY_FCM_PROJECT_ID" to "test-project", "VOLTY_FCM_SERVICE_ACCOUNT_FILE" to
            Path.of(System.getProperty("java.io.tmpdir"), "nonexistent-key-for-assembly.json").toAbsolutePath().toString()))
    @Test fun preparedGraphWiresPushFromPreloadedKeyWithoutReadingFileDatabaseOrSending() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val account = FcmServiceAccount("test-project", ServiceAccountCredentials.newBuilder().setProjectId("test-project")
            .setClientEmail("sender@test-project.iam.gserviceaccount.com").setPrivateKey(key).build())
        val dependencies = AppDependencies.fromPreparedDatabase(config(), source, account)
        try { assertNotNull(dependencies.nearbyPushDelivery); assertNotNull(dependencies.nearbyPushTransport) }
        finally { dependencies.nearbyPushTransport?.close() }
    }
    @Test fun configuredGraphCannotSilentlyRunWithoutPreloadedCredential() {
        val failure = assertFailsWith<IllegalArgumentException> { AppDependencies.fromPreparedDatabase(config(), source) }
        assertEquals("Preloaded FCM credential does not match configuration", failure.message)
    }
}
