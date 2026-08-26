package ru.sodovaya.volty.backend

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingEndpointContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun locationOnlySharingDoesNotNeedTelemetryAndRevokeRemovesLiveSession() = testApplication {
        val store = SharingStore()
        val dependencies = AppDependencies(AppConfig.forTests(), store, testMode = true)
        application { module(dependencies) }
        val auth = dependencies.tokenService.issueAccessToken("user-1")
        val startedAt = nowMillis()

        val started = client.post("/v1/groups/group-1/sharing") {
            bearerAuth(auth)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"groupId":"group-1","profile":"LOCATION","ttlMillis":60000,"startedAtEpochMillis":$startedAt}""")
        }
        val published = client.post("/v1/groups/group-1/sharing/update") {
            bearerAuth(auth)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"capturedAtEpochMillis":$startedAt,"location":{"latitude":56.8,"longitude":60.6,"accuracyMeters":5.0,"capturedAtEpochMillis":$startedAt,"staleAfterEpochMillis":${startedAt + 30000}},"telemetry":null}""")
        }
        val rejected = client.post("/v1/groups/group-1/sharing/update") {
            bearerAuth(auth)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"capturedAtEpochMillis":$startedAt,"location":{"latitude":56.8,"longitude":60.6,"accuracyMeters":5.0,"capturedAtEpochMillis":$startedAt,"staleAfterEpochMillis":${startedAt + 30000}},"telemetry":{"profile":"LOCATION"}}""")
        }
        val revoked = client.delete("/v1/groups/group-1/sharing") { bearerAuth(auth) }

        assertEquals(HttpStatusCode.OK, started.status)
        assertEquals(HttpStatusCode.OK, published.status)
        assertNull(store.lastTelemetry)
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        assertTrue(rejected.bodyAsText().contains("invalid_telemetry"))
        assertEquals(HttpStatusCode.OK, revoked.status)
        assertNull(store.activeShare)
        assertNull(store.lastLocation)
    }

    @Test
    fun sharingUsesServerTimeWhenDeviceClockIsOutsideAllowedSkew() = testApplication {
        val store = SharingStore()
        val dependencies = AppDependencies(AppConfig.forTests(), store, testMode = true)
        application { module(dependencies) }
        val auth = dependencies.tokenService.issueAccessToken("user-1")
        val deviceStartedAt = nowMillis() - 24 * 60 * 60 * 1_000L

        val started = client.post("/v1/groups/group-1/sharing") {
            bearerAuth(auth)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"groupId":"group-1","profile":"LOCATION","ttlMillis":60000,"startedAtEpochMillis":$deviceStartedAt}""")
        }

        assertEquals(HttpStatusCode.OK, started.status)
        assertTrue(store.activeShare!!.startedAtEpochMillis >= deviceStartedAt + 23 * 60 * 60 * 1_000L)
    }

    @Test
    fun sharingPublishesUsingServerTimeWhenDeviceClockIsOutsideAllowedSkew() = testApplication {
        val store = SharingStore()
        val dependencies = AppDependencies(AppConfig.forTests(), store, testMode = true)
        application { module(dependencies) }
        val auth = dependencies.tokenService.issueAccessToken("user-1")
        val deviceNow = nowMillis() - 24 * 60 * 60 * 1_000L

        client.post("/v1/groups/group-1/sharing") {
            bearerAuth(auth)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"groupId":"group-1","profile":"LOCATION","ttlMillis":60000,"startedAtEpochMillis":$deviceNow}""")
        }
        val published = client.post("/v1/groups/group-1/sharing/update") {
            bearerAuth(auth)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"capturedAtEpochMillis":$deviceNow,"location":{"latitude":56.8,"longitude":60.6,"accuracyMeters":5.0,"capturedAtEpochMillis":$deviceNow,"staleAfterEpochMillis":${deviceNow + 15000}},"telemetry":null}""")
        }

        assertEquals(HttpStatusCode.OK, published.status)
        assertTrue(store.lastLocation!!.capturedAtEpochMillis >= deviceNow + 23 * 60 * 60 * 1_000L)
        assertTrue(store.lastLocation!!.staleAfterEpochMillis > nowMillis())
    }

    @Test
    fun renewalKeepsProfileRotatesExpiryAndDropsPreviousLiveUpdate() = testApplication {
        val store = SharingStore()
        val dependencies = AppDependencies(AppConfig.forTests(), store, testMode = true)
        application { module(dependencies) }
        val auth = dependencies.tokenService.issueAccessToken("user-1")
        val startedAt = nowMillis()

        client.post("/v1/groups/group-1/sharing") {
            bearerAuth(auth)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"groupId":"group-1","profile":"RIDE","ttlMillis":60000,"startedAtEpochMillis":$startedAt}""")
        }
        store.lastLocation = LocationDto(56.8, 60.6, 5.0, startedAt, startedAt + 30000)
        val previousExpiry = store.activeShare!!.expiresAtEpochMillis
        val renewed = client.post("/v1/groups/group-1/sharing/renew") {
            bearerAuth(auth)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"ttlMillis":120000,"startedAtEpochMillis":$startedAt}""")
        }

        assertEquals(HttpStatusCode.OK, renewed.status)
        assertEquals("RIDE", json.parseToJsonElement(renewed.bodyAsText()).jsonObject["profile"]!!.jsonPrimitive.content)
        assertEquals(previousExpiry + 120000L, store.activeShare!!.expiresAtEpochMillis)
        assertNull(store.lastLocation)
        assertNull(store.lastTelemetry)
    }

    @Test
    fun leaveAndRenewPublishObserverSnapshots() = testApplication {
        val events = mutableListOf<LiveEventDto>()
        val store = SharingStore()
        val dependencies = AppDependencies(
            AppConfig.forTests(),
            store,
            testMode = true,
            liveHub = LiveHub(Json, onBroadcast = { _, event -> events += event }),
        )
        application { module(dependencies) }
        val auth = dependencies.tokenService.issueAccessToken("user-1")
        val startedAt = nowMillis()

        client.post("/v1/groups/group-1/sharing") {
            bearerAuth(auth)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"groupId\":\"group-1\",\"profile\":\"LOCATION\",\"ttlMillis\":60000,\"startedAtEpochMillis\":$startedAt}")
        }
        client.post("/v1/groups/group-1/sharing/renew") {
            bearerAuth(auth)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"ttlMillis\":120000,\"startedAtEpochMillis\":$startedAt}")
        }
        client.post("/v1/groups/group-1/leave") {
            bearerAuth(auth)
        }

        assertEquals(
            listOf(LiveEventKind.SNAPSHOT.wireName, LiveEventKind.SNAPSHOT.wireName, LiveEventKind.SNAPSHOT.wireName),
            events.map { it.type },
        )
    }

    @Test
    fun deletingGroupPublishesTerminalObserverSignal() = testApplication {
        val revokedGroups = mutableListOf<String>()
        val store = SharingStore()
        val dependencies = AppDependencies(
            AppConfig.forTests(),
            store,
            testMode = true,
            liveHub = LiveHub(Json, onGroupRevoked = { revokedGroups += it }),
        )
        application { module(dependencies) }

        val response = client.delete("/v1/groups/group-1") {
            bearerAuth(dependencies.tokenService.issueAccessToken("user-1"))
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(listOf("group-1"), revokedGroups)
    }

    @Test
    fun expiredShareReturnsExpiredInsteadOfLookingInactive() = testApplication {
        val store = SharingStore()
        val dependencies = AppDependencies(AppConfig.forTests(), store, testMode = true)
        application { module(dependencies) }
        val auth = dependencies.tokenService.issueAccessToken("user-1")
        val now = nowMillis()
        store.activeShare = ShareRow("user-1", "group-1", "LOCATION", now - 60_000L, now - 1L)

        val response = client.post("/v1/groups/group-1/sharing/update") {
            bearerAuth(auth)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("sharing_expired"))
        assertNull(store.activeShare)
    }

    private class SharingStore : BackendStore {
        private val user = UserRecord("user-1", "rider@example.com", "ignored", "Rider", true)
        var activeShare: ShareRow? = null
        var lastLocation: LocationDto? = null
        var lastTelemetry: SharedTelemetryDto? = null

        override fun findUserById(id: String): UserRecord? = user.takeIf { it.id == id }
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long): Boolean = userId == user.id
        override fun isGroupMember(userId: String, groupId: String): Boolean = userId == user.id && groupId == "group-1"
        override fun startSharing(userId: String, request: StartSharingRequest, expiresAt: Long): ShareRow {
            activeShare = ShareRow(userId, request.groupId, request.profile, request.startedAtEpochMillis, expiresAt)
            lastLocation = null
            lastTelemetry = null
            return activeShare!!
        }
        override fun getShare(userId: String, groupId: String): ShareRow? = activeShare?.takeIf { it.userId == userId && it.groupId == groupId }
        override fun stopSharing(userId: String, groupId: String): Boolean {
            val existed = getShare(userId, groupId) != null
            activeShare = null
            lastLocation = null
            lastTelemetry = null
            return existed
        }
        override fun leaveGroup(userId: String, groupId: String) = Unit
        override fun deleteGroup(userId: String, groupId: String) = Unit
        override fun publishSharing(userId: String, groupId: String, location: LocationDto?, telemetry: SharedTelemetryDto?, capturedAt: Long, now: Long): Boolean {
            lastLocation = location
            lastTelemetry = telemetry
            return true
        }
        override fun snapshot(groupId: String, now: Long): LiveSnapshotDto = LiveSnapshotDto(groupId, now, emptyList())
        override fun expireShares(now: Long): List<ShareRow> {
            val expired = activeShare?.takeIf { it.expiresAtEpochMillis <= now }
            if (expired != null) activeShare = null
            return listOfNotNull(expired)
        }
    }
}
