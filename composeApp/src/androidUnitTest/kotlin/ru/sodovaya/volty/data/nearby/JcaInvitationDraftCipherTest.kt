package ru.sodovaya.volty.data.nearby

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

class JcaInvitationDraftCipherTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val invitation = "20000000-0000-0000-0000-000000000002"
    private val community = "30000000-0000-0000-0000-000000000003"
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val draft = InvitationCreationDraft(actor, invitation, community, 900_000, 4, InvitationSecret.fromToken("A".repeat(43)))
    private fun enc(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun dec(text: String) = Base64.getUrlDecoder().decode(text)

    @Test fun emittedBodyRequiresExactly128BitTag() {
        val body = ByteArray(21) { it.toByte() }
        assertEquals(enc(body), checkedInvitationDraftGcmBody(5, body))
        for (size in listOf(0, 20, 22)) {
            assertFailsWith<IllegalArgumentException> { checkedInvitationDraftGcmBody(5, ByteArray(size)) }
        }
    }

    @Test fun realGcmRoundtripPreservesExactPayloadAndUsesFreshProviderIv() {
        val cipher = JcaInvitationDraftCipher { key }
        val first = cipher.encrypt(draft)
        val second = cipher.encrypt(draft)
        assertNotEquals(first, second)
        for (value in listOf(first, second)) {
            assertEquals(InvitationDraftPayloadCodec.encode(draft).size + 16, dec(value.split('.')[2]).size)
            assertFalse(value.contains(draft.secret.reveal()))
            val restored = cipher.decrypt(actor, invitation, value)
            assertEquals(actor, restored.actorId)
            assertEquals(invitation, restored.invitationId)
            assertEquals(community, restored.communityId)
            assertEquals(900_000L, restored.ttlMillis)
            assertEquals(4, restored.maxUses)
            assertEquals(draft.secret.reveal(), restored.secret.reveal())
        }
    }

    @Test fun swappedActorSlotAndKeyFailActualAuthenticationWithoutEchoingSecret() {
        val cipher = JcaInvitationDraftCipher { key }
        val value = cipher.encrypt(draft)
        for (attempt in listOf<() -> Unit>(
            { cipher.decrypt(community, invitation, value) },
            { cipher.decrypt(actor, community, value) },
            { JcaInvitationDraftCipher { SecretKeySpec(ByteArray(32) { 42 }, "AES") }.decrypt(actor, invitation, value) },
        )) {
            val failure = assertFailsWith<IllegalStateException> { attempt() }
            assertNull(failure.cause)
            assertFalse(failure.toString().contains(draft.secret.reveal()))
        }
    }

    @Test fun modifiedIvCiphertextAndTagReachRealGcmAndCannotDecrypt() {
        var keys = 0
        val cipher = JcaInvitationDraftCipher { keys++; key }
        val parts = cipher.encrypt(draft).split('.'); keys = 0
        for ((part, position) in listOf(1 to 0, 2 to 0, 2 to (dec(parts[2]).size - 1))) {
            val changed = parts.toMutableList()
            val bytes = dec(changed[part])
            bytes[position] = (bytes[position].toInt() xor 1).toByte()
            changed[part] = enc(bytes)
            assertFailsWith<IllegalStateException> { cipher.decrypt(actor, invitation, changed.joinToString(".")) }
        }
        assertEquals(3, keys)
    }

    @Test fun malformedBoundedEnvelopeIsRejectedBeforeKeyAccess() {
        var keys = 0
        val cipher = JcaInvitationDraftCipher { keys++; key }
        val parts = cipher.encrypt(draft).split('.'); keys = 0
        for (value in listOf("x".repeat(4_097), "2.${parts[1]}.${parts[2]}", "1.${parts[1]}.${parts[2]}.extra",
            "1.${parts[1]}=.${parts[2]}", "1.${enc(ByteArray(11))}.${parts[2]}", "1.${parts[1]}.", "1.@.${parts[2]}")) {
            assertFailsWith<IllegalStateException> { cipher.decrypt(actor, invitation, value) }
        }
        assertEquals(0, keys)
    }

    @Test fun authenticatedButMalformedPlaintextStillFailsPayloadValidation() {
        val plain = "{\"token\":\"${draft.secret.reveal()}\"".encodeToByteArray()
        val raw = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD("volty.nearby.invitation-draft.v1/$actor/$invitation".encodeToByteArray())
        }
        val envelope = "1.${enc(raw.iv)}.${enc(raw.doFinal(plain))}"
        val failure = assertFailsWith<IllegalStateException> { JcaInvitationDraftCipher { key }.decrypt(actor, invitation, envelope) }
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(draft.secret.reveal()))
    }

    @Test fun decryptionUsesExistingKeyPermissionAndCannotGenerateReplacementForLostKey() {
        var encryptKeys = 0
        var decryptKeys = 0
        var lost = false
        val cipher = JcaInvitationDraftCipher(
            { encryptKeys++; key },
            { decryptKeys++; if (lost) error("lost key") else key },
        )
        val envelope = cipher.encrypt(draft)
        assertEquals(1, encryptKeys)
        assertEquals(0, decryptKeys)
        assertEquals(draft.secret.reveal(), cipher.decrypt(actor, invitation, envelope).secret.reveal())
        lost = true
        assertFailsWith<IllegalStateException> { cipher.decrypt(actor, invitation, envelope) }
        assertEquals(1, encryptKeys)
        assertEquals(2, decryptKeys)
    }
}
