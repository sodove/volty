package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import ru.sodovaya.volty.domain.nearby.*
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

internal class ChannelSharingPacketHttpPort(client: HttpClient, baseUrl: String,
    private val bearer: suspend () -> String?, private val monotonicMillis: () -> Long) {
    private val base = nearbyHttpsApiBaseUrl(baseUrl)
    private val http = client.config {followRedirects=false; expectSuccess=false}
    private val json = Json {ignoreUnknownKeys=true}
    private val closed = MutableStateFlow(false)
    private val requests = Mutex()
    private var ordinal = 0L

    suspend fun publish(tag: ChannelSharingTag, mode: ChannelSharingMode, packet: ChannelSharingPacket): ChannelSharingPacketReply = try {
        val safe = packet.sanitized(mode)
        request("/sharing/${tag.sessionId}/${tag.generation}/packet", json.encodeToString(safe)) {root,id,start,receipt ->
            require(root.keys.none {it in PRIVATE_FIELDS})
            require(root.string("sessionId")==tag.sessionId && root.string("generation")==tag.generation && root.number("sequence")==safe.sequence)
            val deadline = deadline(root.number("remainingMillis"), start)
            if(deadline<=receipt) failed(ChannelSharingFailure.EXPIRED)
            else ChannelSharingPacketReply.Published(tag,safe.sequence,id,start,receipt,deadline)
        }
    } catch(cancelled: CancellationException) {throw cancelled}
      catch(_: IllegalArgumentException) {failed(ChannelSharingFailure.INVALID_RESPONSE)}

    suspend fun audience(channelId: String, sessionId: String): ChannelSharingPacketReply = try {
        invitationDraftId(channelId); invitationDraftId(sessionId)
        request("/channel-sessions/$sessionId/sharing/audience") {root,id,start,receipt ->
            require(root.keys.none {it in PRIVATE_FIELDS}); require(root.string("channelId")==channelId)
            val at=root.number("serverTimeEpochMillis"); require(at>=0)
            val actors=mutableSetOf<String>(); val sessions=mutableSetOf<String>()
            val array=root["peers"] as? JsonArray ?: throw IllegalArgumentException("Missing sharing peers")
            require(array.size<=MAX_PEERS)
            val peers=array.mapNotNull {element ->
                val peer=element as? JsonObject ?: throw IllegalArgumentException("Invalid sharing peer")
                require(peer.keys.none {it in PRIVATE_FIELDS})
                val actor=peer.string("actorId"); invitationDraftId(actor)
                val tag=ChannelSharingTag(peer.string("sessionId"),peer.string("generation"))
                require(actors.add(actor) && sessions.add(tag.sessionId))
                val mode=ChannelSharingMode.valueOf(peer.string("mode"))
                val payload=peer["packet"] as? JsonObject ?: throw IllegalArgumentException("Missing sharing packet")
                require(payload.keys.none {it in PRIVATE_FIELDS})
                payload.number("sequence"); payload.number("capturedAtEpochMillis")
                val packet=json.decodeFromJsonElement<ChannelSharingPacket>(payload).sanitized(mode)
                require(packet.capturedAtEpochMillis<=at && at-packet.capturedAtEpochMillis<CHANNEL_SHARING_SAMPLE_MILLIS)
                val credit=peer.number("remainingMillis")
                require(credit<=packet.capturedAtEpochMillis+CHANNEL_SHARING_SAMPLE_MILLIS-at)
                packet.location?.let {require(it.staleAfterEpochMillis>at && credit<=it.staleAfterEpochMillis-at)}
                val deadline=deadline(credit,start)
                if(deadline<=receipt) null else ObservedChannelSharingPeer(actor,tag,mode,packet,deadline,
                    channelSharingPeerDisplayName((peer["displayName"] as? JsonPrimitive)?.takeIf {it.isString}?.content))
            }
            ChannelSharingPacketReply.Audience(channelId,at,id,start,receipt,peers)
        }
    } catch(cancelled: CancellationException) {throw cancelled}
      catch(_: IllegalArgumentException) {failed(ChannelSharingFailure.INVALID_RESPONSE)}

    fun close() {if(closed.compareAndSet(false,true)) http.close()}

    private suspend fun request(path: String, body: String?=null,
        decode: (JsonObject,Long,Long,Long)->ChannelSharingPacketReply): ChannelSharingPacketReply = try {
        currentCoroutineContext().ensureActive()
        if(closed.value) failed(ChannelSharingFailure.RETIRED)
        else withTimeoutOrNull(10_000) {
            require(body==null || body.encodeToByteArray().size<=MAX_REQUEST_BYTES)
            val token=bearer()?.takeIf {it.isNotBlank()} ?: return@withTimeoutOrNull failed(ChannelSharingFailure.UNAUTHORIZED)
            currentCoroutineContext().ensureActive()
            if(closed.value) return@withTimeoutOrNull failed(ChannelSharingFailure.RETIRED)
            val (id,start)=requests.withLock {check(ordinal<Long.MAX_VALUE); ordinal++ to monotonicMillis().also {require(it>=0)}}
            http.prepareRequest("$base$path") {
                method=if(body==null) HttpMethod.Get else HttpMethod.Post
                bearerAuth(token); header(HttpHeaders.Accept,ContentType.Application.Json.toString())
                if(body!=null) {contentType(ContentType.Application.Json);setBody(body)}
            }.execute {response ->
                currentCoroutineContext().ensureActive()
                if(closed.value) return@execute failed(ChannelSharingFailure.RETIRED)
                when(response.status) {
                    HttpStatusCode.Unauthorized -> return@execute failed(ChannelSharingFailure.UNAUTHORIZED)
                    HttpStatusCode.NotFound,HttpStatusCode.Forbidden -> return@execute failed(ChannelSharingFailure.ACCESS_DENIED)
                    HttpStatusCode.OK -> Unit
                    else -> return@execute failed(ChannelSharingFailure.UNAVAILABLE)
                }
                val bytes=response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES+1L).readByteArray()
                require(bytes.size<=MAX_RESPONSE_BYTES)
                val text=try {bytes.decodeToString(throwOnInvalidSequence=true)} catch(_: Exception) {throw IllegalArgumentException("Invalid sharing UTF-8")}
                val root=json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Invalid sharing response")
                currentCoroutineContext().ensureActive()
                if(closed.value) return@execute failed(ChannelSharingFailure.RETIRED)
                val receipt=monotonicMillis(); require(receipt>=start)
                decode(root,id,start,receipt)
            }
        } ?: failed(ChannelSharingFailure.UNAVAILABLE)
    } catch(cancelled: CancellationException) {throw cancelled}
      catch(_: IllegalArgumentException) {failed(ChannelSharingFailure.INVALID_RESPONSE)}
      catch(_: Exception) {failed(if(closed.value) ChannelSharingFailure.RETIRED else ChannelSharingFailure.UNAVAILABLE)}

    private fun deadline(credit: Long, start: Long): Long {
        require(credit in 1..CHANNEL_SHARING_SAMPLE_MILLIS && start<=Long.MAX_VALUE-credit)
        return start+credit
    }
    private fun JsonObject.string(key: String)=(get(key) as? JsonPrimitive)?.takeIf {it.isString}?.content ?: throw IllegalArgumentException("Invalid sharing field")
    private fun JsonObject.number(key: String)=(get(key) as? JsonPrimitive)?.takeIf {!it.isString}?.longOrNull ?: throw IllegalArgumentException("Invalid sharing number")
    private fun failed(reason: ChannelSharingFailure)=ChannelSharingPacketReply.Failed(reason)
    private companion object {
        const val MAX_REQUEST_BYTES=16_384
        const val MAX_RESPONSE_BYTES=262_144
        const val MAX_PEERS=512
        val PRIVATE_FIELDS=setOf("deadlineEpochMillis","credentials","participantToken","operationId","bearer","password")
    }
}
