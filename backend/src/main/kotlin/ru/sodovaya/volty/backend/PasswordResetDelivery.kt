package ru.sodovaya.volty.backend

interface PasswordResetDelivery {
    val available: Boolean
    suspend fun send(email: String, token: String)
}

object UnavailablePasswordResetDelivery : PasswordResetDelivery {
    override val available = false
    override suspend fun send(email: String, token: String) = error("Email recovery is unavailable")
}
