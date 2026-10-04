package ru.sodovaya.volty.data.nearby

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ru.sodovaya.volty.domain.nearby.community.InvitationCreationDraft
import ru.sodovaya.volty.domain.nearby.community.InvitationSecret
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.nearby.community.invitationDraftInput

internal object InvitationDraftPayloadCodec {
    private const val VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 1024
    private const val INVALID_DRAFT = "Invalid invitation draft"

    fun encode(draft: InvitationCreationDraft): ByteArray {
        invitationDraftId(draft.actorId)
        invitationDraftInput(draft.invitationId, draft.communityId, draft.ttlMillis, draft.maxUses)
        val token = InvitationSecret.fromToken(draft.secret.reveal()).reveal()

        val json = buildJsonObject {
            put("version", JsonPrimitive(VERSION))
            put("actorId", JsonPrimitive(draft.actorId))
            put("invitationId", JsonPrimitive(draft.invitationId))
            put("communityId", JsonPrimitive(draft.communityId))
            put("ttlMillis", JsonPrimitive(draft.ttlMillis))
            put("maxUses", JsonPrimitive(draft.maxUses))
            put("token", JsonPrimitive(token))
        }
        val bytes = json.toString().encodeToByteArray()
        require(bytes.size <= MAX_PAYLOAD_BYTES) { INVALID_DRAFT }
        return bytes
    }

    fun decode(
        expectedActor: String,
        expectedInvitation: String,
        bytes: ByteArray,
    ): InvitationCreationDraft = try {
        require(bytes.size <= MAX_PAYLOAD_BYTES)
        invitationDraftId(expectedActor)
        invitationDraftId(expectedInvitation)

        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        val root = Json.decodeFromString(ExactDraftPayloadObjectSerializer, text)

        val version = root.integer("version")
        require(version == VERSION)

        val actorId = root.string("actorId")
        val invitationId = root.string("invitationId")
        require(actorId == expectedActor && invitationId == expectedInvitation)

        val communityId = root.string("communityId")
        val ttlMillis = root.long("ttlMillis")
        val maxUses = root.integer("maxUses")
        val token = root.string("token")

        InvitationCreationDraft(
            actorId = actorId,
            invitationId = invitationId,
            communityId = communityId,
            ttlMillis = ttlMillis,
            maxUses = maxUses,
            secret = InvitationSecret.fromToken(token),
        )
    } catch (_: Exception) {
        invalidDraft()
    }

    private fun JsonObject.string(name: String): String {
        val primitive = getValue(name) as? JsonPrimitive ?: invalidDraft()
        require(primitive.isString)
        return primitive.content
    }

    private fun JsonObject.integer(name: String): Int {
        val primitive = getValue(name) as? JsonPrimitive ?: invalidDraft()
        require(!primitive.isString)
        return primitive.intOrNull ?: invalidDraft()
    }

    private fun JsonObject.long(name: String): Long {
        val primitive = getValue(name) as? JsonPrimitive ?: invalidDraft()
        require(!primitive.isString)
        return primitive.longOrNull ?: invalidDraft()
    }

    private fun invalidDraft(): Nothing = throw IllegalArgumentException(INVALID_DRAFT)

    private object ExactDraftPayloadObjectSerializer : KSerializer<JsonObject> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("InvitationDraftPayload") {
            element("version", JsonElement.serializer().descriptor)
            element("actorId", JsonElement.serializer().descriptor)
            element("invitationId", JsonElement.serializer().descriptor)
            element("communityId", JsonElement.serializer().descriptor)
            element("ttlMillis", JsonElement.serializer().descriptor)
            element("maxUses", JsonElement.serializer().descriptor)
            element("token", JsonElement.serializer().descriptor)
        }

        override fun deserialize(decoder: Decoder): JsonObject {
            val composite = decoder.beginStructure(descriptor)
            val seen = BooleanArray(descriptor.elementsCount)
            val values = linkedMapOf<String, JsonElement>()
            while (true) {
                val index = composite.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                if (index !in seen.indices || seen[index]) throw SerializationException("Invalid invitation draft")
                seen[index] = true
                values[descriptor.getElementName(index)] = composite.decodeSerializableElement(
                    descriptor,
                    index,
                    JsonElement.serializer(),
                )
            }
            composite.endStructure(descriptor)
            if (seen.any { !it }) throw SerializationException("Invalid invitation draft")
            return JsonObject(values)
        }

        override fun serialize(encoder: Encoder, value: JsonObject) {
            encoder.encodeSerializableValue(JsonObject.serializer(), value)
        }
    }
}
