package ru.sodovaya.volty.data.nearby

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import ru.sodovaya.volty.domain.nearby.community.InvitationCreationDraft
import ru.sodovaya.volty.domain.nearby.community.InvitationSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvitationDraftPayloadCodecTest {
    private val actor = "a0000000-0000-0000-0000-000000000001"
    private val invitation = "30000000-0000-0000-0000-000000000003"
    private val community = "20000000-0000-0000-0000-000000000002"
    private val token = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
    private val ttlMillis = 3_600_000L
    private val maxUses = 37

    private fun draft() = InvitationCreationDraft(
        actorId = actor,
        invitationId = invitation,
        communityId = community,
        ttlMillis = ttlMillis,
        maxUses = maxUses,
        secret = InvitationSecret.fromToken(token),
    )

    private fun validJson() =
        """{"version":1,"actorId":"$actor","invitationId":"$invitation","communityId":"$community","ttlMillis":$ttlMillis,"maxUses":$maxUses,"token":"$token"}"""

    private fun assertSafeDecodeFailure(
        bytes: ByteArray,
        expectedActor: String = actor,
        expectedInvitation: String = invitation,
    ) {
        val failure = assertFailsWith<IllegalArgumentException> {
            InvitationDraftPayloadCodec.decode(expectedActor, expectedInvitation, bytes)
        }
        assertEquals("Invalid invitation draft", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(token))
    }

    @Test
    fun roundTripPreservesActualDraftAndEmitsExactVersionedJsonFields() {
        val encoded = InvitationDraftPayloadCodec.encode(draft())
        assertTrue(encoded.size <= 1024)

        val json = Json.parseToJsonElement(encoded.decodeToString()).jsonObject
        assertEquals(setOf("version", "actorId", "invitationId", "communityId", "ttlMillis", "maxUses", "token"), json.keys)
        assertEquals(1, json.getValue("version").jsonPrimitive.int)
        assertFalse(json.getValue("version").jsonPrimitive.isString)
        assertEquals(actor, json.getValue("actorId").jsonPrimitive.content)
        assertEquals(invitation, json.getValue("invitationId").jsonPrimitive.content)
        assertEquals(community, json.getValue("communityId").jsonPrimitive.content)
        assertEquals(ttlMillis, json.getValue("ttlMillis").jsonPrimitive.long)
        assertFalse(json.getValue("ttlMillis").jsonPrimitive.isString)
        assertEquals(maxUses, json.getValue("maxUses").jsonPrimitive.int)
        assertFalse(json.getValue("maxUses").jsonPrimitive.isString)
        assertEquals(token, json.getValue("token").jsonPrimitive.content)

        val decoded = InvitationDraftPayloadCodec.decode(actor, invitation, encoded)
        assertEquals(actor, decoded.actorId)
        assertEquals(invitation, decoded.invitationId)
        assertEquals(community, decoded.communityId)
        assertEquals(ttlMillis, decoded.ttlMillis)
        assertEquals(maxUses, decoded.maxUses)
        assertEquals(token, decoded.secret.reveal())
    }

    @Test
    fun decodeRejectsForeignOrNonCanonicalExpectedActorAndInvitationBindings() {
        val bytes = validJson().encodeToByteArray()
        val foreign = "40000000-0000-0000-0000-000000000004"
        assertSafeDecodeFailure(bytes, expectedActor = foreign)
        assertSafeDecodeFailure(bytes, expectedInvitation = foreign)
        assertSafeDecodeFailure(bytes, expectedActor = "not-an-id")
        assertSafeDecodeFailure(bytes, expectedInvitation = "not-an-id")
    }

    @Test
    fun decodeRejectsWrongFieldTypesNonIntegralNumbersAndOutOfRangeValues() {
        val invalidPayloads = listOf(
            validJson().replace("\"version\":1", "\"version\":\"1\""),
            validJson().replace("\"actorId\":\"$actor\"", "\"actorId\":1"),
            validJson().replace("\"invitationId\":\"$invitation\"", "\"invitationId\":false"),
            validJson().replace("\"communityId\":\"$community\"", "\"communityId\":null"),
            validJson().replace("\"ttlMillis\":$ttlMillis", "\"ttlMillis\":\"$ttlMillis\""),
            validJson().replace("\"ttlMillis\":$ttlMillis", "\"ttlMillis\":900000.5"),
            validJson().replace("\"maxUses\":$maxUses", "\"maxUses\":\"$maxUses\""),
            validJson().replace("\"maxUses\":$maxUses", "\"maxUses\":1.5"),
            validJson().replace("\"maxUses\":$maxUses", "\"maxUses\":2147483648"),
            validJson().replace("\"token\":\"$token\"", "\"token\":17"),
            validJson().replace("\"ttlMillis\":$ttlMillis", "\"ttlMillis\":899999"),
            validJson().replace("\"ttlMillis\":$ttlMillis", "\"ttlMillis\":604800001"),
            validJson().replace("\"maxUses\":$maxUses", "\"maxUses\":0"),
            validJson().replace("\"maxUses\":$maxUses", "\"maxUses\":1001"),
        )
        invalidPayloads.forEach { assertSafeDecodeFailure(it.encodeToByteArray()) }
        assertSafeDecodeFailure(ByteArray(1025) { ' '.code.toByte() })
    }

    @Test
    fun decodeRejectsMalformedUtf8UnsupportedVersionAndNonExactKeySets() {
        assertSafeDecodeFailure(byteArrayOf(0xc3.toByte(), 0x28))
        assertSafeDecodeFailure(validJson().replace("\"version\":1", "\"version\":2").encodeToByteArray())
        assertSafeDecodeFailure((validJson().dropLast(1) + ",\"unexpected\":true}").encodeToByteArray())
        assertSafeDecodeFailure(validJson().replace(",\"communityId\":\"$community\"", "").encodeToByteArray())
    }

    @Test
    fun decodeRejectsInvalidCanonicalIdsAndSecretWithoutRelaxingDraftValidation() {
        assertTrue(actor != actor.uppercase())
        val invalidPayloads = listOf(
            validJson().replace(actor, actor.uppercase()),
            validJson().replace(invitation, "not-an-id"),
            validJson().replace(community, "not-an-id"),
            validJson().replace(token, "A".repeat(42) + "B"),
        )
        invalidPayloads.forEach { assertSafeDecodeFailure(it.encodeToByteArray()) }
    }

    @Test
    fun malformedJsonContainingSecretHasOnlyGenericCauseFreeDiagnostic() {
        val brokenJson = """{"token":"$token","unexpected": """.encodeToByteArray()
        val failure = assertFailsWith<IllegalArgumentException> {
            InvitationDraftPayloadCodec.decode(actor, invitation, brokenJson)
        }
        assertEquals("Invalid invitation draft", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(token))
        assertFalse(failure.toString().contains("unexpected"))
    }

    @Test
    fun duplicateCanonicalAndEscapedMemberNamesAreNotAnExactSchema() {
        for (member in listOf("\"token\":\"$token\"", "\"version\":1", "\"actorId\":\"$actor\"",
            "\"\\u0074oken\":\"$token\"")) {
            val duplicate = validJson().dropLast(1) + ",$member}"
            assertSafeDecodeFailure(duplicate.encodeToByteArray())
        }
    }
}
