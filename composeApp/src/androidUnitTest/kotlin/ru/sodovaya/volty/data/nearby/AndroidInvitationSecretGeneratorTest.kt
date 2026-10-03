package ru.sodovaya.volty.data.nearby

import java.security.SecureRandom
import java.util.Base64
import kotlin.test.*

class AndroidInvitationSecretGeneratorTest {
    @Test fun eachGenerationRequestsExactly32NewBytesAndEncodesTheirExactContents() {
        val issued = mutableListOf<Int>()
        val arrays = mutableListOf<ByteArray>()
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                issued += bytes.size
                arrays += bytes
                bytes.indices.forEach { bytes[it] = (it + issued.size).toByte() }
            }
        }
        val generator = AndroidInvitationSecretGenerator(random)
        val first = generator.generate()
        val second = generator.generate()
        assertEquals(listOf(32, 32), issued)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() }), first.reveal())
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 2).toByte() }), second.reveal())
        assertTrue(arrays.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test fun entropyFailurePropagatesWithoutFallbackAndTemporaryBytesAreCleared() {
        val failure = IllegalStateException("entropy unavailable")
        var issued = 0
        var buffer: ByteArray? = null
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                issued++
                buffer = bytes
                bytes.fill(7)
                throw failure
            }
        }
        val generator = AndroidInvitationSecretGenerator(random)
        assertSame(failure, assertFailsWith<IllegalStateException> { generator.generate() })
        assertEquals(1, issued)
        assertTrue(buffer!!.all { it == 0.toByte() })
    }
}
