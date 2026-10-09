package ru.sodovaya.volty.data.nearby

import kotlinx.serialization.json.*

internal object PushInstallationCodec {
    const val MAX_BYTES = 16 * 1024
    private val fields = setOf("version", "actorId", "installationId", "installationKey", "generation", "bindingId", "token", "acknowledged")
    fun encode(snapshot: PushInstallationSnapshot): ByteArray = buildJsonObject {
        put("version", 1); put("actorId", snapshot.actorId)
        put("installationId", snapshot.intent.installationId); put("installationKey", snapshot.intent.installationKey)
        put("generation", snapshot.intent.generation); put("bindingId", snapshot.intent.bindingId)
        put("token", snapshot.intent.token?.let(::JsonPrimitive) ?: JsonNull); put("acknowledged", snapshot.acknowledged)
    }.toString().encodeToByteArray().also {require(it.size <= MAX_BYTES) {"Push installation snapshot too large"}}

    fun decode(bytes: ByteArray): PushInstallationSnapshot? {
        if (bytes.size !in 1..MAX_BYTES) return null
        return try {
            val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            require(root.keys == fields)
            fun string(name: String) = (root[name] as? JsonPrimitive)?.takeIf {it.isString}?.content ?: error("Invalid field")
            fun number(name: String) = (root[name] as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull ?: error("Invalid field")
            require(number("version") == 1L)
            val token = if (root["token"] == JsonNull) null else string("token")
            val ack = (root["acknowledged"] as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull ?: error("Invalid field")
            PushInstallationSnapshot(string("actorId"), PushRegistrationIntent(string("installationId"),
                string("installationKey"), number("generation"), string("bindingId"), token), ack)
        } catch (_: Exception) {null}
    }
}
