package ru.sodovaya.volty.data.navigation.offline

import android.content.Context
import android.content.pm.PackageManager
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalog
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogVerifier
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifestVerifier
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageManifest

/**
 * Release configuration for the regional catalog.
 *
 * The catalog URL and verification public key are build inputs, not source
 * constants. A build without them keeps the feature inert and falls back to
 * online navigation; it never accepts the unsigned development manifest.
 */
data class AndroidOfflineNavigationConfig(
    val catalogUrl: String,
    val keyId: String,
    val publicKeyBase64: String,
) {
    val enabled: Boolean
        get() = catalogUrl.startsWith("https://") && keyId.isNotBlank() && publicKeyBase64.isNotBlank()

    fun verifier(): OfflineRegionManifestVerifier = if (enabled) {
        AndroidEd25519ManifestVerifier(keyId, publicKeyBase64)
    } else {
        DisabledOfflineManifestVerifier
    }

    fun catalogVerifier(): OfflineRegionCatalogVerifier = if (enabled) {
        AndroidEd25519ManifestVerifier(keyId, publicKeyBase64)
    } else {
        DisabledOfflineCatalogVerifier
    }

    companion object {
        private const val CATALOG_URL = "ru.sodovaya.volty.offline.catalog_url"
        private const val KEY_ID = "ru.sodovaya.volty.offline.manifest_key_id"
        private const val PUBLIC_KEY = "ru.sodovaya.volty.offline.manifest_public_key"

        fun from(context: Context): AndroidOfflineNavigationConfig {
            val metadata = context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
            return AndroidOfflineNavigationConfig(
                catalogUrl = metadata?.getString(CATALOG_URL).orEmpty().trim(),
                keyId = metadata?.getString(KEY_ID).orEmpty().trim(),
                publicKeyBase64 = metadata?.getString(PUBLIC_KEY).orEmpty().trim(),
            )
        }
    }
}

private object DisabledOfflineManifestVerifier : OfflineRegionManifestVerifier {
    override fun verify(manifest: OfflineRegionPackageManifest): Boolean = false
}

private object DisabledOfflineCatalogVerifier : OfflineRegionCatalogVerifier {
    override fun verify(catalog: OfflineRegionCatalog): Boolean = false
}
