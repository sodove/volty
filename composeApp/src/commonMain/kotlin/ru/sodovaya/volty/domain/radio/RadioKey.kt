package ru.sodovaya.volty.domain.radio

/** Per-server secret the offline radio derives its network name, passphrase and audio key from. */
data class RadioKey(val communityId: String, val epoch: Long, val key: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is RadioKey && communityId == other.communityId && epoch == other.epoch && key.contentEquals(other.key)

    override fun hashCode(): Int = 31 * (31 * communityId.hashCode() + epoch.hashCode()) + key.contentHashCode()

    // The key is a secret: never let it reach a log through toString.
    override fun toString(): String = "RadioKey(communityId=$communityId, epoch=$epoch, key=<redacted>)"
}

/** A cached key and when this device fetched it; whether it is still fresh is decided elsewhere. */
data class StoredRadioKey(val key: RadioKey, val fetchedAtMillis: Long)

interface RadioKeyStore {
    suspend fun get(actor: String, community: String): StoredRadioKey?
    suspend fun put(actor: String, key: RadioKey, fetchedAtMillis: Long)
}
