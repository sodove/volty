package ru.sodovaya.volty.data.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IncomingCallPushHintTest {
    private val callId = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    private val bindingId = "87ac10b0-58cc-4372-a567-0e02b2c3d479"

    private fun payload() = mapOf(
        "type" to "nearby_call",
        "callId" to callId,
        "revision" to "0",
        "bindingId" to bindingId,
        "generation" to "1",
    )

    @Test
    fun accepts_canonical_payload_and_preserves_every_hint_field() {
        val hint = assertNotNull(IncomingCallPushHint.decode(payload()))
        assertEquals(callId, hint.callId)
        assertEquals(0L, hint.revision)
        assertEquals(bindingId, hint.bindingId)
        assertEquals(1L, hint.generation)

        val maximum = assertNotNull(
            IncomingCallPushHint.decode(
                payload() + mapOf(
                    "revision" to "9223372036854775807",
                    "generation" to "9223372036854775807",
                ),
            ),
        )
        assertEquals(Long.MAX_VALUE, maximum.revision)
        assertEquals(Long.MAX_VALUE, maximum.generation)
    }

    @Test
    fun rejects_wrong_message_type_and_noncanonical_uuids() {
        assertNull(IncomingCallPushHint.decode(payload() + ("type" to "direct_call")))
        assertNull(IncomingCallPushHint.decode(payload() + ("callId" to callId.uppercase())))
        assertNull(IncomingCallPushHint.decode(payload() + ("bindingId" to "not-a-uuid")))
    }

    @Test
    fun rejects_revisions_outside_canonical_nonnegative_long_decimal() {
        for (revision in listOf("-1", "+1", "01", "9223372036854775808", "")) {
            assertNull(IncomingCallPushHint.decode(payload() + ("revision" to revision)), revision)
        }
    }

    @Test
    fun rejects_generations_outside_canonical_positive_long_decimal() {
        for (generation in listOf("0", "-1", "+1", "01", "9223372036854775808", "")) {
            assertNull(IncomingCallPushHint.decode(payload() + ("generation" to generation)), generation)
        }
    }

    @Test
    fun rejects_missing_and_unknown_fields() {
        assertNull(IncomingCallPushHint.decode(payload() - "bindingId"), "bindingId is required")
        assertNull(
            IncomingCallPushHint.decode(payload() - "bindingId" + ("jwt" to "secret")),
            "unknown fields are forbidden even at the expected key count",
        )
        assertNull(IncomingCallPushHint.decode(payload() + ("jwt" to "secret")), "the key count must be exact")
    }

    @Test
    fun rejects_oversized_keys_and_values() {
        val oversizedKey = payload() - "type" + ("k".repeat(33) to "nearby_call")
        assertNull(IncomingCallPushHint.decode(oversizedKey))

        assertNull(IncomingCallPushHint.decode(payload() + ("revision" to "1".repeat(129))))
    }

    @Test
    fun rejects_non_ascii_values_and_payloads_over_1024_utf8_bytes() {
        assertNull(IncomingCallPushHint.decode(payload() + ("callId" to "\u0416".repeat(36))))

        val oversizedUtf8Payload = payload().keys.associateWith { "\uD83D\uDE80".repeat(64) }
        assertEquals(5, oversizedUtf8Payload.size)
        assertNull(IncomingCallPushHint.decode(oversizedUtf8Payload))
    }

    @Test
    fun string_representation_does_not_expose_payload_values() {
        val hint = assertNotNull(IncomingCallPushHint.decode(payload()))
        val text = hint.toString()

        assertFalse(callId in text)
        assertFalse(bindingId in text)
        assertFalse("nearby_call" in text)
    }
}