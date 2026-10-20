package ru.sodovaya.volty.backend.nearby

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ru.sodovaya.volty.backend.ApiException
import ru.sodovaya.volty.backend.AppDependencies

@Serializable
private data class RadioKeyResponse(val communityId:String,val epoch:Long,val key:String)

fun Route.installNearbyRadioKeyRoutes(dependencies:AppDependencies) {
    authenticate("auth-jwt") {
        get("/v2/nearby/communities/{communityId}/radio-key") {
            val actor=call.nearbyActor(dependencies)
            val community=communityCanonicalId(call.parameters["communityId"] ?: "")
            val port=dependencies.nearbyRadioKeys ?: throw ApiException(HttpStatusCode.ServiceUnavailable,"nearby_unavailable","Nearby unavailable")
            when(val result=withContext(Dispatchers.IO) {port.issue(actor,community)}) {
                is RadioKeyResult.Issued -> call.respond(RadioKeyResponse(result.communityId,result.epoch,Base64.getEncoder().encodeToString(result.key)))
                RadioKeyResult.Denied -> throw ApiException(HttpStatusCode.NotFound,"community_unavailable","Community unavailable")
            }
        }
    }
}
