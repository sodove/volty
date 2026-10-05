package ru.sodovaya.volty.data.voice

import kotlin.test.*

class IncomingCallActionAddressTest {
    private val binding = "10000000-0000-0000-0000-000000000001"
    private val ticket = "20000000-0000-0000-0000-000000000002"

    @Test fun canonicalRoundTripKeepsBothOriginalIdentitiesAndAction() {
        for (kind in IncomingCallActionKind.entries) {
            val address = IncomingCallActionAddress(binding, ticket, kind)
            assertEquals(address, IncomingCallActionAddress.parse(address.uri))
            assertTrue(address.uri.contains("/$binding/$ticket/"))
        }
        assertNotEquals(IncomingCallActionAddress(binding, ticket, IncomingCallActionKind.ACCEPT).uri,
            IncomingCallActionAddress(binding, ticket, IncomingCallActionKind.DECLINE).uri)
        assertNotEquals(IncomingCallActionAddress(binding, ticket, IncomingCallActionKind.ACCEPT).uri,
            IncomingCallActionAddress(ticket, binding, IncomingCallActionKind.ACCEPT).uri)
    }

    @Test fun malformedNativeAddressDoesNotBecomeAnAction() {
        val original = "volty://incoming-call/$binding/$ticket/accept"
        for (invalid in listOf("", original + "?accept=true", original + "#accept", original + "/",
            original.replace("volty:", "https:"), original.replace("incoming-call", "voice"),
            original.replace("accept", "stop"), original.replace(binding, "invalid"),
            original.replace(binding, "%31" + binding.drop(1)), " " + original, original + "/extra")) {
            assertNull(IncomingCallActionAddress.parse(invalid), invalid)
        }
    }

    @Test fun issuingActionRequiresCanonicalOpaqueIds() {
        assertFailsWith<IllegalArgumentException> {IncomingCallActionAddress("wrong", ticket, IncomingCallActionKind.ACCEPT)}
        assertFailsWith<IllegalArgumentException> {IncomingCallActionAddress(binding, "wrong", IncomingCallActionKind.DECLINE)}
    }
}
