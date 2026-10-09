package ru.sodovaya.volty.data.nearby

import kotlinx.serialization.json.*
import kotlin.test.*

class PushInstallationCodecTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private fun state(token: String?, acknowledged: Boolean) = PushInstallationSnapshot(actor,
        PushRegistrationIntent("20000000-0000-0000-0000-000000000002", "a".repeat(64), Long.MAX_VALUE,
            "30000000-0000-0000-0000-000000000003", token), acknowledged)
    @Test fun roundTripPreservesExactPrivateBindingAndExplicitLogoutAcrossAcknowledgement() {
        for (token in listOf("token", null)) for (ack in listOf(false, true)) {
            val original = state(token, ack)
            val restored = assertNotNull(PushInstallationCodec.decode(PushInstallationCodec.encode(original)))
            assertEquals(actor, restored.actorId); assertEquals(ack, restored.acknowledged)
            assertEquals(original.intent.installationId, restored.intent.installationId)
            assertEquals(original.intent.installationKey, restored.intent.installationKey)
            assertEquals(original.intent.bindingId, restored.intent.bindingId)
            assertEquals(Long.MAX_VALUE, restored.intent.generation); assertEquals(token, restored.intent.token)
        }
    }
    @Test fun malformedVersionOrPrivateFieldsCannotPartiallyRestoreOldIdentity() {
        val encoded = PushInstallationCodec.encode(state("token", true))
        assertNotNull(PushInstallationCodec.decode(encoded), "Positive control must restore all fields")
        val valid = Json.parseToJsonElement(encoded.decodeToString()).jsonObject
        for ((name, value) in listOf(
            "version" to JsonPrimitive(2), "generation" to JsonPrimitive("7"), "generation" to JsonPrimitive(0),
            "installationId" to JsonPrimitive("invalid"), "installationKey" to JsonPrimitive("short"),
            "acknowledged" to JsonPrimitive("true"), "token" to JsonPrimitive("bad\nsecret"),
        )) {
            val altered = JsonObject(valid + (name to value)).toString().encodeToByteArray()
            assertNull(PushInstallationCodec.decode(altered), name)
        }
        for (name in valid.keys) assertNull(PushInstallationCodec.decode(JsonObject(valid - name).toString().encodeToByteArray()), name)
    }
    @Test fun oversizedOtherwiseValidJsonAndUnknownSchemaReturnNoPartialState() {
        val valid = PushInstallationCodec.encode(state("token", false))
        assertNotNull(PushInstallationCodec.decode(valid), "Positive control must restore before negative fixtures")
        val root = Json.parseToJsonElement(valid.decodeToString()).jsonObject
        val padded = valid + ByteArray(16 * 1024 + 1 - valid.size) {32}
        assertEquals(root, Json.parseToJsonElement(padded.decodeToString()), "Oversize fixture must remain valid JSON")
        assertNull(PushInstallationCodec.decode(padded))
        assertNull(PushInstallationCodec.decode(JsonObject(root + ("extra" to JsonPrimitive(true))).toString().encodeToByteArray()))
        assertNull(PushInstallationCodec.decode("[]".encodeToByteArray()))
    }
    @Test fun malformedUtf8CannotHideInAnOverwrittenButOtherwiseAcceptedField() {
        val valid = PushInstallationCodec.encode(state("token", false))
        val invalidUtf8 = "{\"token\":\"".encodeToByteArray() + byteArrayOf(0x80.toByte()) +
            "\",".encodeToByteArray() + valid.copyOfRange(1, valid.size)
        // Replacement decoding and the parser's last-field-wins rule would yield a valid snapshot.
        assertEquals(Json.parseToJsonElement(valid.decodeToString()), Json.parseToJsonElement(invalidUtf8.decodeToString()))
        assertNotNull(PushInstallationCodec.decode(valid))
        assertNull(PushInstallationCodec.decode(invalidUtf8))
    }
}
