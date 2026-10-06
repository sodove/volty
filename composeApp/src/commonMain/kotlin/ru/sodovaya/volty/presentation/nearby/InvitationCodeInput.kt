package ru.sodovaya.volty.presentation.nearby

/** Plaintext is available only to the input renderer and explicit command dispatch. */
class InvitationCodeInput private constructor(private val value:String) {
    fun reveal():String=value
    override fun toString():String="InvitationCodeInput([redacted])"
    companion object {
        val EMPTY=InvitationCodeInput("")
        fun fromInput(value:String):InvitationCodeInput {
            require(value.length<=80)
            return if(value.isEmpty()) EMPTY else InvitationCodeInput(value)
        }
    }
}
data class InvitationAcceptanceForm(val visible:Boolean=false,val originCommunity:String?=null,
    val code:InvitationCodeInput=InvitationCodeInput.EMPTY,val submitted:Boolean=false)
