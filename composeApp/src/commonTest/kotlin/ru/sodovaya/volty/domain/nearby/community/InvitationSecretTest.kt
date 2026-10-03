package ru.sodovaya.volty.domain.nearby.community

import kotlin.test.*

class InvitationSecretTest {
    @Test fun canonicalSecretHasExplicitAccessAndRedactedDiagnosticRendering() {
        val token = "A".repeat(43)
        val secret = InvitationSecret.fromToken(token)
        assertEquals(token, secret.reveal())
        assertEquals("InvitationSecret([redacted])", secret.toString())
        assertFalse(listOf(secret).toString().contains(token))
    }

    @Test fun nonCanonicalUnusedBitsPaddingLengthAndAlphabetAreRejectedWithoutEcho() {
        for (token in listOf("A".repeat(42) + "B", "A".repeat(42), "A".repeat(44),
            "A".repeat(42) + "=", "A".repeat(42) + "+", "A".repeat(42) + "é", " " + "A".repeat(42))) {
            val failure = assertFailsWith<IllegalArgumentException> { InvitationSecret.fromToken(token) }
            assertFalse(failure.toString().contains(token))
        }
    }
}
