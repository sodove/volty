package ru.sodovaya.volty.data.navigation.offline

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalog
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogCodec
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogVerifier
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifestVerifier
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageManifest
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageManifestCodec

/** Verifies a release manifest with the pinned Volty navigation Ed25519 key. */
class AndroidEd25519ManifestVerifier(
    private val expectedKeyId: String,
    publicKeyBase64: String,
) : OfflineRegionManifestVerifier, OfflineRegionCatalogVerifier {
    private val publicKey = runCatching {
        val rawKey = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        require(rawKey.size == RAW_ED25519_PUBLIC_KEY_BYTES) { "Ed25519 public key must be 32 bytes" }
        Ed25519PublicKeyParameters(rawKey, 0)
    }.getOrNull()

    override fun verify(manifest: OfflineRegionPackageManifest): Boolean {
        val key = publicKey ?: return false
        if (manifest.signature.keyId != expectedKeyId ||
            manifest.signature.algorithm.lowercase() != "ed25519"
        ) return false
        return runCatching {
            val signature = Base64.decode(manifest.signature.value, Base64.DEFAULT)
            val payload = OfflineRegionPackageManifestCodec.signingPayload(manifest)
                .toByteArray(Charsets.UTF_8)
            val verifier = Ed25519Signer()
            verifier.init(false, key)
            verifier.update(payload, 0, payload.size)
            verifier.verifySignature(signature)
        }.getOrDefault(false)
    }

    override fun verify(catalog: OfflineRegionCatalog): Boolean {
        val key = publicKey ?: return false
        val signature = catalog.signature
        if (signature.keyId != expectedKeyId ||
            signature.algorithm.lowercase() != "ed25519"
        ) return false
        return runCatching {
            val encodedSignature = Base64.decode(signature.value, Base64.DEFAULT)
            val payload = OfflineRegionCatalogCodec.signingPayload(catalog)
                .toByteArray(Charsets.UTF_8)
            val verifier = Ed25519Signer()
            verifier.init(false, key)
            verifier.update(payload, 0, payload.size)
            verifier.verifySignature(encodedSignature)
        }.getOrDefault(false)
    }

    private companion object {
        const val RAW_ED25519_PUBLIC_KEY_BYTES = 32

    }
}
