package ru.sodovaya.volty.backend.nearby

import java.nio.file.Path
import kotlin.test.*

class FcmConfigurationTest {
    private val file = Path.of(System.getProperty("java.io.tmpdir"), "private-test-firebase-key.json").toAbsolutePath().toString()
    @Test fun absentConfigurationDisablesTransportWithoutLoadingCredentials() {
        val config = FcmConfiguration.fromEnvironment(emptyMap())
        assertFalse(config.configured); assertNull(config.load()); config.validate(NearbyCompatibility.LEGACY)
    }
    @Test fun explicitPairParsesAndRedactsPath() {
        val config = FcmConfiguration.fromEnvironment(mapOf("VOLTY_FCM_PROJECT_ID" to "test-project", "VOLTY_FCM_SERVICE_ACCOUNT_FILE" to file))
        assertTrue(config.configured); assertEquals("test-project", config.project); assertEquals(file, config.serviceAccountFile)
        assertEquals("FcmConfiguration(configured=true)", config.toString()); config.validate(NearbyCompatibility.VOICE_SPACES)
    }
    @Test fun partialPairFailsWithoutEchoingPrivateInput() {
        for (env in listOf(mapOf("VOLTY_FCM_PROJECT_ID" to "private-project"), mapOf("VOLTY_FCM_SERVICE_ACCOUNT_FILE" to file))) {
            val failure = assertFailsWith<IllegalArgumentException> { FcmConfiguration.fromEnvironment(env) }
            assertEquals("FCM requires both project ID and service-account file", failure.message); assertNull(failure.cause)
        }
    }
    @Test fun invalidProjectAndRelativePathFailPrivatelyEvenInManualConfig() {
        for (args in listOf("private project" to file, "test-project" to "private-relative.json")) {
            val failure = assertFailsWith<IllegalArgumentException> { FcmConfiguration(args.first, args.second) }
            assertEquals("Invalid FCM configuration", failure.message); assertNull(failure.cause)
        }
    }
    @Test fun configuredPushRequiresVoiceSpacesMode() {
        assertFailsWith<IllegalArgumentException> { FcmConfiguration("test-project", file).validate(NearbyCompatibility.LEGACY) }
    }
}
