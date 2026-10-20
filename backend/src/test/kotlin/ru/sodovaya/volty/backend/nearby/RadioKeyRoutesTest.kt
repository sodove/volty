package ru.sodovaya.volty.backend.nearby

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import ru.sodovaya.volty.backend.*
import java.util.Base64
import kotlin.test.*

class RadioKeyRoutesTest {
    private val actor="10000000-0000-0000-0000-000000000001"
    private val community="20000000-0000-0000-0000-000000000002"
    private val path="/v2/nearby/communities/$community/radio-key"
    private fun dependencies(port:RadioKeyEndpointPort?=null)=AppDependencies(AppConfig.forTests(),object:BackendStore {
        override fun findUserById(id:String)=UserRecord(actor,"private@example.invalid","ignored","Owner",true).takeIf {id==actor}
        override fun isAccessActive(userId:String,issuedAtEpochSeconds:Long)=userId==actor
    },testMode=true,nearbyRadioKeys=port)

    @Test fun memberGetsEpochAndBase64KeyOfThirtyTwoBytes()=testApplication {
        val key=ByteArray(32) {it.toByte()};val calls=mutableListOf<String>()
        val deps=dependencies(RadioKeyEndpointPort {a,c -> calls+="$a:$c";RadioKeyResult.Issued(c,7,key)});application {module(deps)}
        val response=client.get(path) {bearerAuth(deps.tokenService.issueAccessToken(actor))}
        assertEquals(HttpStatusCode.OK,response.status)
        val root=Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("communityId","epoch","key"),root.keys)
        assertEquals(community,root.getValue("communityId").jsonPrimitive.content)
        assertEquals(7L,root.getValue("epoch").jsonPrimitive.long)
        assertContentEquals(key,Base64.getDecoder().decode(root.getValue("key").jsonPrimitive.content))
        assertEquals(listOf("$actor:$community"),calls)
    }
    @Test fun deniedIsGenericNotFound()=testApplication {
        val deps=dependencies(RadioKeyEndpointPort {_,_ -> RadioKeyResult.Denied});application {module(deps)}
        val response=client.get(path) {bearerAuth(deps.tokenService.issueAccessToken(actor))}
        assertEquals(HttpStatusCode.NotFound,response.status)
        assertEquals("community_unavailable",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
    }
    @Test fun missingTokenIsUnauthorizedAndPortIsNeverCalled()=testApplication {
        var called=false
        val deps=dependencies(RadioKeyEndpointPort {_,_ -> called=true;RadioKeyResult.Denied});application {module(deps)}
        assertEquals(HttpStatusCode.Unauthorized,client.get(path).status);assertFalse(called)
    }
    @Test fun unconfiguredSecretIsServiceUnavailable()=testApplication {
        val deps=dependencies();application {module(deps)}
        val response=client.get(path) {bearerAuth(deps.tokenService.issueAccessToken(actor))}
        assertEquals(HttpStatusCode.ServiceUnavailable,response.status)
        assertEquals("nearby_unavailable",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
    }
}
