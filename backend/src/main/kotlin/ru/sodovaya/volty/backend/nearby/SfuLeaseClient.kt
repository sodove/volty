package ru.sodovaya.volty.backend.nearby

enum class SfuFailure { UNSUPPORTED, INVALID_RESPONSE, FORBIDDEN, REJECTED, UNAVAILABLE, TIMEOUT, INTERRUPTED }
sealed interface SfuReply<out T> {
    data class Accepted<T>(val value: T) : SfuReply<T>
    data class Rejected(val reason: SfuFailure) : SfuReply<Nothing>
}
data class SfuNodeStatus(val nodeEpoch: String, val ready: Boolean, val canRegisterNew: Boolean, val singleNode: Boolean = false)
data class LeaseReceipt(val conservativeDeadlineEpochMillis: Long)

interface SfuLeaseClient {
    fun inspect(room: String): SfuReply<SfuNodeStatus>
    fun register(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt>
    fun renew(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt>
    fun revoke(room: String, session: String, expectedNodeEpoch: String): SfuReply<Unit>
}
