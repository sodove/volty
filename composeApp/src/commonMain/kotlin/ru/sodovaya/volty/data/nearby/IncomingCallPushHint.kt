package ru.sodovaya.volty.data.nearby

internal class IncomingCallPushHint private constructor(
    val callId: String,
    val revision: Long,
    val bindingId: String,
    val generation: Long,
) {
    override fun toString(): String = "IncomingCallPushHint(<redacted>)"

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val REQUIRED_KEYS = setOf("type", "callId", "revision", "bindingId", "generation")

        fun decode(data: Map<String, String>): IncomingCallPushHint? {
            if (data.size != REQUIRED_KEYS.size) return null

            var utf8Bytes = 0
            for ((key, value) in data) {
                if (key.length > MAX_KEY_LENGTH || value.length > MAX_VALUE_LENGTH) return null
                utf8Bytes += key.encodeToByteArray().size
                utf8Bytes += value.encodeToByteArray().size
                if (utf8Bytes > MAX_PAYLOAD_UTF8_BYTES) return null
            }

            if (data.keys != REQUIRED_KEYS) return null
            if (data.keys.any { !it.isAscii() } || data.values.any { !it.isAscii() }) return null
            if (data["type"] != MESSAGE_TYPE) return null

            val callId = data.getValue("callId")
            if (!UUID_PATTERN.matches(callId)) return null
            val bindingId = data.getValue("bindingId")
            if (!UUID_PATTERN.matches(bindingId)) return null

            val revision = data.getValue("revision").canonicalLong(allowZero = true) ?: return null
            val generation = data.getValue("generation").canonicalLong(allowZero = false) ?: return null

            return IncomingCallPushHint(callId, revision, bindingId, generation)
        }

        private fun String.isAscii(): Boolean = all { it.code <= 0x7f }

        private fun String.canonicalLong(allowZero: Boolean): Long? {
            if (isEmpty() || length > MAX_LONG_DECIMAL_LENGTH) return null
            if (length > 1 && this[0] == '0') return null
            if (any { it !in '0'..'9' }) return null
            val number = toLongOrNull() ?: return null
            return number.takeIf { it > 0 || allowZero }
        }

        private const val MESSAGE_TYPE = "nearby_call"
        private const val MAX_KEY_LENGTH = 32
        private const val MAX_VALUE_LENGTH = 128
        private const val MAX_PAYLOAD_UTF8_BYTES = 1024
        private const val MAX_LONG_DECIMAL_LENGTH = 19
    }
}