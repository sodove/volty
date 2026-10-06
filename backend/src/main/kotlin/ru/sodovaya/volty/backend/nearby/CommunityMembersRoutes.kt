package ru.sodovaya.volty.backend.nearby

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ru.sodovaya.volty.backend.ApiException
import ru.sodovaya.volty.backend.AppDependencies

@Serializable
private data class MemberResponse(val userId:String,val displayName:String,val joinedAtMillis:Long)
@Serializable
private data class MembersResponse(val communityId:String,val ownerId:String,val items:List<MemberResponse>)

fun Route.installNearbyCommunityMembersRoutes(dependencies:AppDependencies) {
    authenticate("auth-jwt") {
        get("/v2/nearby/communities/{communityId}/members") {
            val actor=call.nearbyActor(dependencies)
            val community=communityCanonicalId(call.parameters["communityId"] ?: "")
            val port=dependencies.nearbyMembers ?: throw ApiException(HttpStatusCode.ServiceUnavailable,"nearby_unavailable","Nearby unavailable")
            fun single(name:String):String? {
                val values=call.request.queryParameters.getAll(name) ?: return null
                if(values.size!=1) invalidMembersPage()
                return values.single()
            }
            val after=single("after")?.let(::communityCanonicalId)
            val limit=single("limit")?.let {it.toIntOrNull() ?: invalidMembersPage()} ?: 20
            if(limit !in 1..50) invalidMembersPage()
            when(val result=withContext(Dispatchers.IO) {port.list(actor,community,after,limit)}) {
                is CommunityMembersResult.Listed -> call.respond(MembersResponse(result.communityId,result.ownerId,
                    result.items.map {MemberResponse(it.userId,it.displayName,it.joinedAtMillis)}))
                CommunityMembersResult.Denied -> throw ApiException(HttpStatusCode.NotFound,"community_unavailable","Community unavailable")
            }
        }
    }
}
private fun invalidMembersPage():Nothing=throw ApiException(HttpStatusCode.BadRequest,"invalid_request","Request is invalid")
