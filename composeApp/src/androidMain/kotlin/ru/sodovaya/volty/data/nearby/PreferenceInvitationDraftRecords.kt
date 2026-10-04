package ru.sodovaya.volty.data.nearby

import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

/** Thin native implementation exposes file existence, cached strings and acknowledged commit. */
interface InvitationPreferenceAccess {
    fun hasStoredFile(): Boolean
    fun value(key: String): String?
    fun commit(values: Map<String, String>): Boolean
}

/** Single-process policy; every instance uses the same lock, including other account profiles. */
class PreferenceInvitationDraftRecords(
    override val actorId: String,
    private val access: InvitationPreferenceAccess,
) : InvitationDraftRecords {
    init { invitationDraftId(actorId) }

    override fun <T> locked(block: (InvitationDraftRecordTransaction) -> T): T = synchronized(lock) {
        val schema = access.value(SCHEMA)
        check(schema == null || schema == VERSION)
        // A corrupt existing preferences file may be exposed as an empty in-memory map.
        if (access.hasStoredFile()) { check(schema == VERSION); fence() }
        block(object : InvitationDraftRecordTransaction {
            override fun peek(invitation: String): String? {
                invitationDraftId(invitation)
                return access.value("draft.$invitation")?.also { check(access.value(SCHEMA) == VERSION) }
            }
            override fun write(invitation: String, encrypted: String): Boolean {
                check(peek(invitation) == null)
                require(encrypted.isNotEmpty() && encrypted.length <= 4_096)
                return commit(mapOf("draft.$invitation" to encrypted))
            }
            override fun confirm(): Boolean = commit(emptyMap())
        })
    }

    private fun commit(values: Map<String, String>): Boolean {
        val current = access.value(FENCE)
        val next = if (current == null) {
            check(access.value(SCHEMA) == null)
            "1"
        } else if (fence() == "1") "2" else "1"
        // Changing this value forces a new memory generation even if the ciphertext is equal.
        return access.commit(values + mapOf(SCHEMA to VERSION, FENCE to next))
    }

    private fun fence(): String = access.value(FENCE)?.also {
        check(it == "1" || it == "2")
    } ?: throw IllegalStateException("Invitation draft unavailable")

    private companion object {
        val lock = Any()
        const val SCHEMA = "schema"
        const val FENCE = "fence"
        const val VERSION = "1"
    }
}
