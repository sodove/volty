package ru.sodovaya.volty.backend.nearby

import java.nio.file.Path

data class FcmConfiguration(val project: String? = null, val serviceAccountFile: String? = null) {
    init {
        require((project == null) == (serviceAccountFile == null)) { "FCM requires both project ID and service-account file" }
        if (project != null) {
            val absolute = try { Path.of(checkNotNull(serviceAccountFile)).isAbsolute } catch (_: Exception) { false }
            require(project.matches(Regex("[a-z][a-z0-9-]{0,127}")) && absolute) { "Invalid FCM configuration" }
        }
    }
    val configured get() = project != null
    override fun toString() = "FcmConfiguration(configured=$configured)"
    internal fun load(): FcmServiceAccount? = project?.let { FcmServiceAccount.load(Path.of(checkNotNull(serviceAccountFile)), it) }
    fun validate(mode: NearbyCompatibility) {
        require(!configured || mode == NearbyCompatibility.VOICE_SPACES) { "FCM requires Nearby voice_spaces mode" }
    }
    companion object {
        fun fromEnvironment(env: Map<String, String>) = FcmConfiguration(
            env["VOLTY_FCM_PROJECT_ID"]?.trim()?.takeIf(String::isNotEmpty),
            env["VOLTY_FCM_SERVICE_ACCOUNT_FILE"]?.trim()?.takeIf(String::isNotEmpty))
    }
}
