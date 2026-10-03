package ru.sodovaya.volty.data.nearby

import java.security.SecureRandom
import java.util.Base64
import ru.sodovaya.volty.domain.nearby.community.InvitationSecret
import ru.sodovaya.volty.domain.nearby.community.InvitationSecretGenerator

/** New32-byte CSPRNG secret per explicit invitation creation, never per retry. */
class AndroidInvitationSecretGenerator(private val random: SecureRandom = SecureRandom()) : InvitationSecretGenerator {
    override fun generate(): InvitationSecret {
        val bytes = ByteArray(32)
        try {
            random.nextBytes(bytes)
            return InvitationSecret.fromToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        } finally { bytes.fill(0) }
    }
}
