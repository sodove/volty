package ru.sodovaya.volty.data.navigation.offline

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifestVerifier
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalog
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogCodec
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogVerifier
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
        val encoded = ByteArray(ED25519_SPKI_PREFIX.size + rawKey.size)
        ED25519_SPKI_PREFIX.copyInto(encoded)
        rawKey.copyInto(encoded, destinationOffset = ED25519_SPKI_PREFIX.size)
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded))
    }.getOrNull()

    override fun verify(manifest: OfflineRegionPackageManifest): Boolean {
        val key = publicKey ?: return false
        if (manifest.signature.keyId != expectedKeyId ||
            manifest.signature.algorithm.lowercase() != "ed25519"
        ) return false
        return runCatching {
            val signature = Base64.decode(manifest.signature.value, Base64.DEFAULT)
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(OfflineRegionPackageManifestCodec.signingPayload(manifest).toByteArray(Charsets.UTF_8))
                verify(signature)
            }
        }.getOrDefault(false)
    }

    override fun verify(catalog: OfflineRegionCatalog): Boolean {
        val signature = catalog.signature
        if (signature.keyId != expectedKeyId ||
            signature.algorithm.lowercase() != "ed25519"
        ) return false
        return runCatching {
            val encodedSignature = Base64.decode(signature.value, Base64.DEFAULT)
            Signature.getInstance("Ed25519").run {
                initVerify(publicKey ?: return@run false)
                update(OfflineRegionCatalogCodec.signingPayload(catalog).toByteArray(Charsets.UTF_8))
                verify(encodedSignature)
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val RAW_ED25519_PUBLIC_KEY_BYTES = 32
        val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
    }
}
