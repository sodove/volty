package ru.sodovaya.volty.data.radio

import kotlin.test.*
import ru.sodovaya.volty.domain.radio.RadioKey

class RadioCredentialsTest {
    private fun key(epoch: Long = 1, fill: Int = 7) = RadioKey("c", epoch, ByteArray(32) { (it + fill).toByte() })

    @Test fun derivationIsDeterministic() {
        assertEquals(RadioCredentialDerivation.derive(key(), 20_000), RadioCredentialDerivation.derive(key(), 20_000))
    }

    @Test fun networkNameHasDirectPrefixAndFitsLength() {
        val name = RadioCredentialDerivation.derive(key(), 20_000).networkName
        assertTrue(name.startsWith("DIRECT-Vy-"), name)
        assertTrue(name.length <= 32, name)
        assertTrue(name.drop(10).all { it in 'a'..'z' || it in '2'..'7' }, name)
    }

    @Test fun passphraseLengthWithinWpaRange() {
        assertTrue(RadioCredentialDerivation.derive(key(), 20_000).passphrase.length in 8..63)
    }

    @Test fun dayChangesNameButNotAudioKey() {
        val a = RadioCredentialDerivation.derive(key(), 20_000)
        val b = RadioCredentialDerivation.derive(key(), 20_001)
        assertNotEquals(a.networkName, b.networkName)
        assertNotEquals(a.passphrase, b.passphrase)
        assertContentEquals(a.audioKey, b.audioKey)
        assertEquals(16, a.audioKey.size)
    }

    @Test fun epochChangesEverything() {
        val a = RadioCredentialDerivation.derive(key(epoch = 1), 20_000)
        val b = RadioCredentialDerivation.derive(key(epoch = 2), 20_000)
        assertNotEquals(a.networkName, b.networkName)
        assertNotEquals(a.passphrase, b.passphrase)
        assertFalse(a.audioKey.contentEquals(b.audioKey))
    }

    @Test fun differentKeysDeriveDifferentCredentials() {
        assertNotEquals(RadioCredentialDerivation.derive(key(fill = 1), 20_000), RadioCredentialDerivation.derive(key(fill = 2), 20_000))
    }

    @Test fun joinCandidatesCoverTodayAndYesterday() {
        val candidates = RadioCredentialDerivation.joinCandidates(key(), 86_400_000L * 20_000 + 1_000)
        assertEquals(listOf(RadioCredentialDerivation.derive(key(), 20_000), RadioCredentialDerivation.derive(key(), 19_999)), candidates)
    }

    // Independent oracle: recompute the HMAC here and encode its first 50 bits as lowercase RFC 4648 base32.
    @Test fun networkNameMatchesIndependentHmacVector() {
        val k = RadioKey("c", 1, ByteArray(32))
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(ByteArray(32), "HmacSHA256")) }
            .doFinal("net|1|20000".toByteArray())
        val bits = java.math.BigInteger(1, mac).toString(2).padStart(256, '0')
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val expected = bits.take(50).chunked(5).joinToString("") { alphabet[it.toInt(2)].toString() }
        assertEquals("DIRECT-Vy-$expected", RadioCredentialDerivation.derive(k, 20_000).networkName)
    }
}
