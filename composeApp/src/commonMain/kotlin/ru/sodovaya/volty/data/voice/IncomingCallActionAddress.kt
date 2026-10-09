package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

internal enum class IncomingCallActionKind { OPEN, ACCEPT, DECLINE }
internal data class IncomingCallActionAddress(val bindingId: String, val ticketId: String, val kind: IncomingCallActionKind) {
    init {invitationDraftId(bindingId); invitationDraftId(ticketId)}
    val uri: String get() = "volty://incoming-call/$bindingId/$ticketId/${kind.name.lowercase()}"
    companion object {
        fun parse(raw: String): IncomingCallActionAddress? {
            val prefix = "volty://incoming-call/"
            if (raw.length > 256 || !raw.startsWith(prefix)) return null
            val parts = raw.removePrefix(prefix).split('/')
            if (parts.size != 3) return null
            val kind = when (parts[2]) {"open" -> IncomingCallActionKind.OPEN; "accept" -> IncomingCallActionKind.ACCEPT; "decline" -> IncomingCallActionKind.DECLINE; else -> return null}
            return try {IncomingCallActionAddress(parts[0], parts[1], kind)} catch (_: IllegalArgumentException) {null}
        }
    }
}
