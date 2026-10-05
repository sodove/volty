package ru.sodovaya.volty.backend.nearby

import java.net.URI
import ru.sodovaya.volty.backend.AppConfig

/** Operator intent; readiness metadata cannot establish ownership of a single origin. */
data class NearbyBackendConfiguration(
    val mode: NearbyCompatibility = NearbyCompatibility.LEGACY,
    val controlOrigin: URI? = null,
    val singletonOrigin: Boolean = false,
) {
    fun validate(config: AppConfig) {
        if (mode == NearbyCompatibility.LEGACY) {
            require(controlOrigin == null && !singletonOrigin) { "Nearby control settings require voice_spaces mode" }
            return
        }
        require(config.voiceProvider == "livekit" &&
            !config.liveKitApiKey.isNullOrBlank() && !config.liveKitApiSecret.isNullOrBlank() &&
            !config.liveKitUrl.isNullOrBlank() && !config.publicIp.isNullOrBlank()) {
            "voice_spaces requires configured LiveKit credentials and public IP"
        }
        val signal = parseUri(config.liveKitUrl!!)
        require(signal.scheme in setOf("ws", "wss") && signal.host != null &&
            signal.userInfo == null && signal.query == null && signal.fragment == null && validPort(signal)) {
            "LIVEKIT_URL must be a WebSocket service URL"
        }
        require(config.voiceTokenTtlSeconds in AppConfig.MIN_VOICE_TOKEN_TTL_SECONDS..AppConfig.MAX_VOICE_TOKEN_TTL_SECONDS) {
            "Voice token lifetime is out of range"
        }
        require(controlOrigin != null && controlOrigin.scheme in setOf("http", "https") &&
            controlOrigin.host != null && controlOrigin.userInfo == null && controlOrigin.query == null &&
            controlOrigin.fragment == null && controlOrigin.path in setOf("", "/") && validPort(controlOrigin)) {
            "VOLTY_NEARBY_SFU_CONTROL_ORIGIN must be an HTTP(S) service origin"
        }
    }

    // URI syntax failures must not echo potentially credential-bearing input.
    override fun toString() = "NearbyBackendConfiguration(mode=$mode, controlConfigured=${controlOrigin != null}, singletonOrigin=$singletonOrigin)"

    companion object {
        fun fromEnvironment(env: Map<String, String>): NearbyBackendConfiguration {
            val mode = when (env["VOLTY_NEARBY_MODE"]?.trim()?.lowercase().orEmpty()) {
                "", "legacy" -> NearbyCompatibility.LEGACY
                "voice_spaces" -> NearbyCompatibility.VOICE_SPACES
                else -> throw IllegalArgumentException("VOLTY_NEARBY_MODE must be legacy or voice_spaces")
            }
            val singleton = when (env["VOLTY_NEARBY_SINGLETON_ORIGIN"]?.trim()?.lowercase()) {
                null, "false" -> false
                "true" -> true
                else -> throw IllegalArgumentException("VOLTY_NEARBY_SINGLETON_ORIGIN must be true or false")
            }
            return NearbyBackendConfiguration(mode,
                env["VOLTY_NEARBY_SFU_CONTROL_ORIGIN"]?.trim()?.takeIf(String::isNotEmpty)?.let(::parseUri), singleton)
        }

        private fun validPort(uri: URI) = uri.port == -1 || uri.port in 1..65535

        private fun parseUri(value: String): URI = try { URI(value) } catch (_: Exception) {
            throw IllegalArgumentException("Nearby service URI has invalid syntax")
        }
    }
}
