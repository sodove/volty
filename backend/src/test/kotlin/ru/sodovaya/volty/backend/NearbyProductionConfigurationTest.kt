package ru.sodovaya.volty.backend

import java.lang.reflect.Proxy
import javax.sql.DataSource
import ru.sodovaya.volty.backend.nearby.NearbyCompatibility
import kotlin.test.*

class NearbyProductionConfigurationTest {
    private val base=mapOf("VOLTY_JWT_SECRET" to "test-secret-that-is-long-enough-for-hmac")
    private val voice=base+mapOf(
        "VOLTY_NEARBY_MODE" to "voice_spaces", "VOLTY_VOICE_PROVIDER" to "livekit",
        "LIVEKIT_URL" to "wss://media.example.invalid", "LIVEKIT_API_KEY" to "private-key",
        "LIVEKIT_API_SECRET" to "private-secret", "VOLTY_PUBLIC_IP" to "192.0.2.1",
        "VOLTY_NEARBY_SFU_CONTROL_ORIGIN" to "https://control.example.invalid",
    )
    private fun untouchedSource()=Proxy.newProxyInstance(DataSource::class.java.classLoader,arrayOf(DataSource::class.java)) {_,_,_ ->
        error("Assembly must not access the borrowed database")
    } as DataSource

    @Test fun legacyIsExplicitDefaultEvenWhenLivekitIsConfigured() {
        assertEquals(NearbyCompatibility.LEGACY,AppConfig.fromEnvironment(base).nearby.mode)
        assertEquals(NearbyCompatibility.LEGACY,AppConfig.fromEnvironment(base+mapOf(
            "VOLTY_NEARBY_MODE" to "legacy", "VOLTY_NEARBY_SFU_CONTROL_ORIGIN" to "",
            "VOLTY_NEARBY_SINGLETON_ORIGIN" to "false")).nearby.mode)
        val config=AppConfig.fromEnvironment(voice-"VOLTY_NEARBY_MODE"-"VOLTY_NEARBY_SFU_CONTROL_ORIGIN")
        val dependencies=AppDependencies.fromPreparedDatabase(config,untouchedSource())
        assertEquals(NearbyCompatibility.LEGACY,dependencies.nearbyCompatibility)
        assertNull(dependencies.nearbySessions);assertNull(dependencies.nearbyMaintenance)
    }
    @Test fun activeFactoryInstallsEveryEndpointAndMaintenanceWithoutDatabaseOrNetworkIo() {
        val dependencies=AppDependencies.fromPreparedDatabase(AppConfig.fromEnvironment(voice),untouchedSource())
        assertEquals(NearbyCompatibility.VOICE_SPACES,dependencies.nearbyCompatibility)
        assertNotNull(dependencies.nearbySessions);assertNotNull(dependencies.nearbyRoster)
        assertNotNull(dependencies.nearbyMediaWebhook);assertNotNull(dependencies.nearbyCommunities)
        assertNotNull(dependencies.nearbyCommunityRemoval);assertNotNull(dependencies.nearbyChannelCommands)
        assertNotNull(dependencies.nearbyInvitations);assertNotNull(dependencies.nearbyCalls)
        assertNotNull(dependencies.nearbyChannelSharing);assertNotNull(dependencies.nearbySharingPackets)
        assertNotNull(dependencies.nearbyMaintenance)
    }
    @Test fun singletonOwnershipRequiresAnExplicitStrictBoolean() {
        assertFalse(AppConfig.fromEnvironment(voice).nearby.singletonOrigin)
        assertTrue(AppConfig.fromEnvironment(voice+mapOf("VOLTY_NEARBY_SINGLETON_ORIGIN" to "true")).nearby.singletonOrigin)
        assertFailsWith<IllegalArgumentException> {AppConfig.fromEnvironment(voice+mapOf("VOLTY_NEARBY_SINGLETON_ORIGIN" to "yes"))}
    }
    @Test fun unknownModesMissingCredentialsAndNonOriginsFailBeforeActivation() {
        assertFailsWith<IllegalArgumentException> {AppConfig.fromEnvironment(base+mapOf("VOLTY_NEARBY_MODE" to "typo"))}
        assertFailsWith<IllegalArgumentException> {AppConfig.fromEnvironment(voice+mapOf("VOLTY_NEARBY_MODE" to "legacy"))}
        for(key in listOf("LIVEKIT_API_KEY","LIVEKIT_API_SECRET","LIVEKIT_URL","VOLTY_NEARBY_SFU_CONTROL_ORIGIN")) {
            assertFailsWith<IllegalArgumentException>(key) {AppConfig.fromEnvironment(voice-key)}
        }
        for(origin in listOf("https://user:secret@control.invalid","https://control.invalid/path","https://control.invalid?secret=1","https://control.invalid:99999","file:///tmp/control","not a uri")) {
            val failure=assertFailsWith<IllegalArgumentException> {AppConfig.fromEnvironment(voice+mapOf("VOLTY_NEARBY_SFU_CONTROL_ORIGIN" to origin))}
            assertFalse(failure.message.orEmpty().contains(origin))
        }
    }
    @Test fun copiedConfigurationIsRevalidatedBeforeOpeningTheProductionPool() {
        val config=AppConfig.fromEnvironment(voice).copy(liveKitApiSecret="")
        assertFailsWith<IllegalArgumentException> {AppDependencies.create(config)}
        assertFailsWith<IllegalArgumentException> {AppDependencies.fromPreparedDatabase(config,untouchedSource())}
        val valid=AppConfig.fromEnvironment(voice)
        val invalidOrigin=valid.copy(nearby=valid.nearby.copy(controlOrigin=java.net.URI("https://control.invalid/path")))
        assertFailsWith<IllegalArgumentException> {AppDependencies.create(invalidOrigin)}
    }
    @Test fun diagnosticsDoNotExposeMediaCredentials() {
        val config=AppConfig.fromEnvironment(voice)
        assertFalse(config.toString().contains("private-key"));assertFalse(config.toString().contains("private-secret"))
    }
}
