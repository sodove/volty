package ru.sodovaya.volty.domain.nearby.community

fun interface InvitationSecretGenerator { fun generate(): InvitationSecret }

/** Diagnostic rendering is redacted. Plaintext access is explicit for transport/storage/share. */
class InvitationSecret private constructor(private val token: String) {
    fun reveal(): String = token
    override fun toString(): String = "InvitationSecret([redacted])"

    companion object {
        fun fromToken(token: String): InvitationSecret {
            require(token.length == 43 && token.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' } &&
                token.last() in "AEIMQUYcgkosw048")
            return InvitationSecret(token)
        }
    }
}
