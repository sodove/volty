package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.sodovaya.volty.domain.nearby.community.CommunityChannel
import ru.sodovaya.volty.domain.nearby.community.CommunityDetails
import ru.sodovaya.volty.domain.nearby.community.CommunityFailure
import ru.sodovaya.volty.domain.nearby.community.CommunityResult
import ru.sodovaya.volty.domain.nearby.community.CommunitySummary
import ru.sodovaya.volty.domain.nearby.community.CommunityVisibility

/** Plain per-key text the platform keeps across starts (SharedPreferences on Android). */
interface CommunityCacheStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
}

/**
 * The account's own server list and server pages, remembered from the last answer the server gave.
 * When the server cannot be reached they are served instead of an empty screen, because the offline
 * radio lives on a server's page (field report 2026-10-24: no internet, no list, no radio).
 *
 * Only "could not ask" falls back: [CommunityFailure.UNAVAILABLE], and [CommunityFailure.UNAUTHORIZED],
 * which is what an offline start reports when its expired token cannot be refreshed (a real rejection
 * signs the account out anyway, retiring this cache's owner). A server that refuses this account a
 * page ([CommunityFailure.ACCESS_DENIED]) is forgotten. Only the first page of the list is kept.
 */
class CommunityOfflineCache(private val storage: CommunityCacheStorage, private val actor: String) {
    suspend fun memberships(
        after: String?,
        fetch: suspend () -> CommunityResult<List<CommunitySummary>>,
    ): CommunityResult<List<CommunitySummary>> {
        val result = fetch()
        if (after != null) return result
        return when (result) {
            is CommunityResult.Ready -> result.also { save(listKey(), json.encodeToString(result.value.map(::summaryDto))) }
            is CommunityResult.Failed -> if (!result.reason.couldNotAsk()) result
                else load(listKey()) { json.decodeFromString<List<SummaryDto>>(it).map(SummaryDto::domain) }
                    ?.let { CommunityResult.Ready(it) } ?: result
        }
    }

    suspend fun detail(
        community: String,
        fetch: suspend () -> CommunityResult<CommunityDetails>,
    ): CommunityResult<CommunityDetails> = when (val result = fetch()) {
        is CommunityResult.Ready -> result.also { save(detailKey(community), json.encodeToString(detailDto(result.value))) }
        is CommunityResult.Failed -> when {
            result.reason == CommunityFailure.ACCESS_DENIED -> result.also { forget(detailKey(community)) }
            !result.reason.couldNotAsk() -> result
            else -> load(detailKey(community)) { json.decodeFromString<DetailDto>(it).domain() }
                ?.takeIf { it.community.id == community }
                ?.let { CommunityResult.Ready(it) } ?: result
        }
    }

    private fun CommunityFailure.couldNotAsk() =
        this == CommunityFailure.UNAVAILABLE || this == CommunityFailure.UNAUTHORIZED

    private fun listKey() = "$actor|memberships"
    private fun detailKey(community: String) = "$actor|detail|$community"

    // The cache is a convenience: a storage failure costs the offline view, never the live answer.
    private suspend fun save(key: String, value: String) = quietly { storage.write(key, value) }
    private suspend fun forget(key: String) = quietly { storage.remove(key) }
    private suspend fun <T> load(key: String, decode: (String) -> T): T? = quietly {
        storage.read(key)?.let { text ->
            try { decode(text) } catch (_: Exception) { storage.remove(key); null }
        }
    }

    private suspend fun <T> quietly(action: suspend () -> T): T? = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    @Serializable
    private data class SummaryDto(val id: String, val name: String, val description: String,
                                  val visibility: String, val createdAtEpochMillis: Long) {
        fun domain() = CommunitySummary(id, name, description, CommunityVisibility.valueOf(visibility), createdAtEpochMillis)
    }

    @Serializable
    private data class ChannelDto(val id: String, val name: String, val isDefault: Boolean)

    @Serializable
    private data class DetailDto(val revision: Long, val community: SummaryDto, val ownerId: String,
                                 val channels: List<ChannelDto>) {
        fun domain() = CommunityDetails(revision, community.domain(), ownerId,
            channels.map { CommunityChannel(it.id, it.name, it.isDefault) })
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        fun summaryDto(s: CommunitySummary) = SummaryDto(s.id, s.name, s.description, s.visibility.name, s.createdAtEpochMillis)
        fun detailDto(d: CommunityDetails) = DetailDto(d.revision, summaryDto(d.community), d.ownerId,
            d.channels.map { ChannelDto(it.id, it.name, it.isDefault) })
    }
}
