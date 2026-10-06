package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import ru.sodovaya.volty.domain.nearby.community.*

class CommunityMembersHttpPort(client:HttpClient,baseUrl:String,private val actor:String,
    private val bearer:suspend ()->String?):CommunityMembersPort {
    private val base=nearbyHttpsApiBaseUrl(baseUrl)
    init {invitationDraftId(actor)}
    private val http=client.config {followRedirects=false;expectSuccess=false}
    private val json=Json {ignoreUnknownKeys=true}
    override suspend fun list(community:String,after:String?,limit:Int):CommunityResult<CommunityMembersPage> {
        invitationDraftId(community);after?.let(::invitationDraftId);require(limit in 1..50)
        return try {
            withTimeoutOrNull(10_000) {
                val auth=bearer()?.takeIf {it.isNotBlank()} ?: return@withTimeoutOrNull failed(CommunityFailure.UNAUTHORIZED)
                http.prepareGet("$base/communities/$community/members") {
                    bearerAuth(auth);header(HttpHeaders.Accept,ContentType.Application.Json.toString())
                    parameter("limit",limit);after?.let {parameter("after",it)}
                }.execute {response ->
                    if(response.status.value!=200) return@execute failed(when(response.status.value) {
                        401 -> CommunityFailure.UNAUTHORIZED
                        403,404 -> CommunityFailure.ACCESS_DENIED
                        else -> CommunityFailure.UNAVAILABLE
                    })
                    // Fifty names can each use six-byte JSON escapes; 16 KiB would reject a valid page.
                    val bytes=response.bodyAsChannel().readRemaining(32*1024L+1).readByteArray()
                    require(bytes.size<=32*1024)
                    val text=try {bytes.decodeToString(throwOnInvalidSequence=true)}
                    catch(_:Exception) {return@execute failed(CommunityFailure.INVALID_RESPONSE)}
                    val root=json.parseToJsonElement(text) as? JsonObject ?: invalid()
                    require(string(root,"communityId")==community && string(root,"ownerId")==actor)
                    val items=root["items"] as? JsonArray ?: invalid()
                    require(items.size<=limit)
                    val members=items.map {element ->
                        val row=element as? JsonObject ?: invalid()
                        val id=string(row,"userId").also(::invitationDraftId)
                        val name=string(row,"displayName").also {require(it.length in 2..80 && it.isNotBlank())}
                        val joined=primitive(row,"joinedAtMillis").let {require(!it.isString);requireNotNull(it.longOrNull).also {at -> require(at>=0)}}
                        CommunityMemberSummary(id,name,joined)
                    }
                    val ids=members.map {it.userId}
                    require(ids.distinct().size==ids.size && ids==ids.sorted() && (after==null || ids.all {it>after}))
                    CommunityResult.Ready(CommunityMembersPage(community,actor,members))
                }
            } ?: failed(CommunityFailure.UNAVAILABLE)
        } catch(cancelled:CancellationException) {throw cancelled}
          catch(_:IllegalArgumentException) {failed(CommunityFailure.INVALID_RESPONSE)}
          catch(_:Exception) {failed(CommunityFailure.UNAVAILABLE)}
    }
    fun close()=http.close()
    private fun primitive(root:JsonObject,key:String)=(root[key] as? JsonPrimitive)?.takeUnless {it==JsonNull} ?: invalid()
    private fun string(root:JsonObject,key:String)=primitive(root,key).also {require(it.isString)}.content
    private fun invalid():Nothing=throw IllegalArgumentException("Invalid community members page")
    private fun failed(reason:CommunityFailure)=CommunityResult.Failed(reason)
}
