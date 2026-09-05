package ru.sodovaya.volty.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.path
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.auth.principal
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import io.ktor.serialization.kotlinx.json.json
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

private const val ISSUER = "volty"
private const val AUDIENCE = "volty-app"

class AppDependencies(
    val config: AppConfig,
    val store: BackendStore,
    val json: Json = backendJson(),
    val testMode: Boolean = false,
    val liveHub: LiveHub = LiveHub(json),
    val navigationProvider: NavigationProvider = navigationProviderFor(config),
) {
    val tokenService = TokenService(config)
    val refreshTokenService = RefreshTokenService(config.jwtSecret.toByteArray(), config.accessTtlSeconds, config.refreshTtlSeconds)
    val rateLimiter = RateLimiter(maxRequests = 120, windowSeconds = 60)
    val navigationSearchLimiter = RateLimiter(maxRequests = 20, windowSeconds = 60)
    val navigationRouteLimiter = RateLimiter(maxRequests = 10, windowSeconds = 60)
    val navigationSearchCache = NavigationTtlCache<List<NavigationPlaceDto>>(maxEntries = 256, ttlMillis = 60_000L)
    val navigationRouteCache = NavigationTtlCache<NavigationRouteResponse>(maxEntries = 256, ttlMillis = 30_000L)
    val voiceService = VoiceService(config)

    companion object {
        fun create(config: AppConfig = AppConfig.fromEnvironment()): AppDependencies {
            val dataSource = createDataSource(config)
            SchemaMigrator(dataSource).migrate()
            val json = backendJson()
            return AppDependencies(config, JdbcStore(dataSource, json), json = json)
        }

        fun forTests() = AppDependencies(AppConfig.forTests(), object : BackendStore {}, testMode = true)
    }
}

class TokenService(private val config: AppConfig) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    fun issueAccessToken(userId: String, nowEpochSeconds: Long = Instant.now().epochSecond): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withSubject(userId)
        .withClaim("kind", "access")
        .withIssuedAt(Date(nowEpochSeconds * 1_000))
        .withExpiresAt(Date((nowEpochSeconds + config.accessTtlSeconds) * 1_000))
        .withJWTId(uuid())
        .sign(algorithm)

    fun verifier() = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).build()
}

class LiveHub(
    private val json: Json,
    private val onBroadcast: (String, LiveEventDto) -> Unit = { _, _ -> },
    private val onGroupRevoked: (String) -> Unit = {},
) {
    private data class LiveSession(val userId: String, val session: io.ktor.server.websocket.DefaultWebSocketServerSession)
    private val sessions = ConcurrentHashMap<String, MutableSet<LiveSession>>()

    fun add(groupId: String, userId: String, session: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        sessions.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }.add(LiveSession(userId, session))
    }

    suspend fun addAndRevalidate(
        groupId: String,
        userId: String,
        session: io.ktor.server.websocket.DefaultWebSocketServerSession,
        stillMember: suspend () -> Boolean,
    ): Boolean {
        add(groupId, userId, session)
        if (stillMember()) return true
        remove(groupId, session)
        terminate(LiveSession(userId, session))
        return false
    }

    fun remove(groupId: String, session: io.ktor.server.websocket.DefaultWebSocketServerSession) {
        sessions[groupId]?.let { members -> members.removeIf { it.session == session }; if (members.isEmpty()) sessions.remove(groupId, members) }
    }

    suspend fun revokeUser(userId: String) = revoke { it.userId == userId }

    suspend fun revokeUserFromGroup(groupId: String, userId: String) = revokeGroupMembers(groupId) { it.userId == userId }

    suspend fun revokeGroup(groupId: String) {
        onGroupRevoked(groupId)
        sessions.remove(groupId)?.forEach { terminate(it) }
    }

    private suspend fun revokeGroupMembers(groupId: String, predicate: (LiveSession) -> Boolean) {
        val members = sessions[groupId] ?: return
        members.filter(predicate).forEach { member ->
            members.remove(member)
            terminate(member)
        }
        if (members.isEmpty()) sessions.remove(groupId, members)
    }

    private suspend fun revoke(predicate: (LiveSession) -> Boolean) {
        sessions.forEach { (groupId, members) ->
            members.filter(predicate).forEach { member ->
                members.remove(member)
                terminate(member)
            }
            if (members.isEmpty()) sessions.remove(groupId, members)
        }
    }

    private suspend fun terminate(member: LiveSession) {
        runCatching {
            member.session.send(Frame.Text(json.encodeToString(LiveEventDto(LiveEventKind.TERMINATED.wireName))))
            member.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "subscription is no longer valid"))
        }
    }

    suspend fun broadcast(groupId: String, event: LiveEventDto) {
        onBroadcast(groupId, event)
        val payload = json.encodeToString(event)
        sessions[groupId]?.toList()?.forEach { member ->
            runCatching { member.session.send(Frame.Text(payload)) }.onFailure { remove(groupId, member.session) }
        }
    }
}

fun main() {
    startServer()
}

fun Application.module(dependencies: AppDependencies = AppDependencies.create()) {
    val logger = LoggerFactory.getLogger("VoltyBackend")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    install(CallLogging)
    install(CallId) {
        generate { uuid() }
        verify { it.isNotBlank() }
    }
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
        header("Permissions-Policy", "geolocation=(), microphone=()")
    }
    install(ContentNegotiation) { json(dependencies.json) }
    install(CORS) {
        dependencies.config.corsOrigins.forEach { origin ->
            val uri = runCatching { URI(origin) }.getOrNull()
            if (uri?.host != null) allowHost(uri.host, schemes = listOf(uri.scheme))
        }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowCredentials = true
    }
    install(WebSockets)
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.statusCode, ApiError(cause.code, cause.message, call.requestId()))
        }
        exception<io.ktor.server.plugins.ContentTransformationException> { call, _ -> call.respondError(HttpStatusCode.BadRequest, "invalid_json", "Request body is invalid") }
        exception<NoSuchElementException> { call, cause -> call.respondError(HttpStatusCode.NotFound, "not_found", cause.message ?: "Resource not found") }
        exception<GroupOwnerRequiredException> { call, cause -> call.respondError(HttpStatusCode.Forbidden, "forbidden", cause.message ?: "Only the group owner can delete it") }
        exception<GroupOwnerCannotLeaveException> { call, cause -> call.respondError(HttpStatusCode.Forbidden, "group_owner_cannot_leave", cause.message ?: "The sole group owner cannot leave the group") }
        exception<IllegalStateException> { call, cause -> call.respondError(HttpStatusCode.Conflict, "conflict", cause.message ?: "Request conflicts with current state") }
        exception<IllegalArgumentException> { call, cause -> call.respondError(HttpStatusCode.BadRequest, "invalid_request", cause.message ?: "Request is invalid") }
        exception<Throwable> { call, cause -> logger.error("Unhandled request failure", cause); call.respondError(HttpStatusCode.InternalServerError, "server_error", "Internal server error") }
    }
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(dependencies.tokenService.verifier())
            challenge { _, _ -> call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Authentication is required") }
            validate { credential ->
                val subject = credential.payload.subject
                val issuedAt = credential.payload.issuedAt?.time?.div(1_000) ?: 0
                if (credential.payload.getClaim("kind").asString() == "access" && subject != null && dependencies.store.isAccessActive(subject, issuedAt)) JWTPrincipal(credential.payload) else null
            }
        }
    }

    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        if (call.request.path().startsWith("/v1/")) {
            val key = "${call.request.local.remoteHost}:${call.request.httpMethod.value}:${call.request.path().substringBeforeLast('/', call.request.path())}"
            val now = Instant.now().epochSecond
            if (!dependencies.rateLimiter.allow(key, now)) {
                val retryAfter = dependencies.rateLimiter.retryAfterSeconds(key, now)
                call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
                call.respondError(HttpStatusCode.TooManyRequests, "rate_limited", "Too many requests", mapOf("retryAfterSeconds" to retryAfter.toString()))
                finish()
            }
        }
    }

    routing {
        installOfflineRegionRoutes(dependencies)
        get("/health") { call.respond(HealthResponse("ok", "volty-backend", "0.1.0")) }
            route("/v1") {
            installNavigationRoutes(dependencies)
            post("/auth/register") {
                val body = call.receive<RegisterRequest>()
                val email = Validation.email(body.email).orBadRequest()
                val password = Validation.password(body.password).orBadRequest()
                val displayName = Validation.displayName(body.displayName).orBadRequest()
                val user = try { dependencies.store.createUser(email, BCrypt.hashpw(password, BCrypt.gensalt(12)), displayName, nowMillis()) } catch (e: Exception) { if (e.isUniqueViolation()) throw ApiException(HttpStatusCode.Conflict, "email_taken", "Email is already registered") else throw e }
                call.respond(HttpStatusCode.Created, issueSession(dependencies, user))
            }
            post("/auth/login") {
                val body = call.receive<LoginRequest>()
                val email = Validation.email(body.email).orBadRequest()
                val user = dependencies.store.findUserByEmail(email)
                if (user == null || !BCrypt.checkpw(body.password, user.passwordHash)) throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Email or password is incorrect")
                call.respond(issueSession(dependencies, user))
            }
            post("/auth/refresh") {
                val body = call.receive<RefreshRequest>()
                val hash = dependencies.refreshTokenService.hash(body.refreshToken)
                val row = dependencies.store.findRefreshToken(hash) ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Refresh token is invalid")
                val now = Instant.now().epochSecond
                if (row.revokedAtEpochSeconds != null) {
                    dependencies.store.revokeAllUserTokens(row.userId, now)
                    throw ApiException(HttpStatusCode.Unauthorized, "refresh_reuse", "Refresh token has already been used")
                }
                if (row.expiresAtEpochSeconds <= now) throw ApiException(HttpStatusCode.Unauthorized, "refresh_expired", "Refresh token has expired")
                val user = dependencies.store.findUserById(row.userId) ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Account is unavailable")
                val next = dependencies.refreshTokenService.issueRefreshToken(user.id, now)
                if (!dependencies.store.rotateRefreshToken(row.id, next.hash, now)) throw ApiException(HttpStatusCode.Unauthorized, "refresh_reuse", "Refresh token has already been used")
                dependencies.store.insertRefreshToken(user.id, next.hash, next.expiresAtEpochSeconds, now)
                call.respond(
                    SessionResponse(
                        accessToken = dependencies.tokenService.issueAccessToken(user.id, now),
                        refreshToken = next.raw,
                        expiresAtEpochMillis = (now + dependencies.config.accessTtlSeconds) * 1_000,
                    ),
                )
            }
            get("/auth/verify") {
                val token = call.request.queryParameters["token"] ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "token is required")
                val userId = dependencies.store.consumeOneTimeToken("EMAIL_VERIFY", sha256(token), nowMillis()) ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_token", "Verification token is invalid or expired")
                dependencies.store.markEmailVerified(userId)
                call.respond(VerifyResponse())
            }
            post("/auth/verify") {
                val token = call.receive<TokenRequest>().token
                val userId = dependencies.store.consumeOneTimeToken("EMAIL_VERIFY", sha256(token), nowMillis()) ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_token", "Verification token is invalid or expired")
                dependencies.store.markEmailVerified(userId)
                call.respond(VerifyResponse())
            }
            post("/auth/password-reset/request") {
                val body = call.receive<PasswordResetRequest>()
                val email = Validation.email(body.email).orBadRequest()
                dependencies.store.findUserByEmail(email)?.let { user ->
                    issueOneTimeToken(dependencies, user.id, "PASSWORD_RESET")
                }
                call.respond(HttpStatusCode.Accepted, mapOf("accepted" to true))
            }
            post("/auth/password-reset") {
                val body = call.receive<PasswordResetConfirmRequest>()
                val password = Validation.password(body.newPassword).orBadRequest()
                val userId = dependencies.store.consumeOneTimeToken("PASSWORD_RESET", sha256(body.token), nowMillis()) ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_token", "Reset token is invalid or expired")
                dependencies.store.updatePassword(userId, BCrypt.hashpw(password, BCrypt.gensalt(12)), nowMillis())
                call.respond(mapOf("reset" to true))
            }
            post("/auth/password-reset/confirm") {
                val body = call.receive<PasswordResetConfirmRequest>()
                val password = Validation.password(body.newPassword).orBadRequest()
                val userId = dependencies.store.consumeOneTimeToken("PASSWORD_RESET", sha256(body.token), nowMillis()) ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_token", "Reset token is invalid or expired")
                dependencies.store.updatePassword(userId, BCrypt.hashpw(password, BCrypt.gensalt(12)), nowMillis())
                call.respond(mapOf("reset" to true))
            }

            authenticate("auth-jwt") {
                get("/profile") { call.respond(call.user(dependencies).toProfile()) }
                patch("/profile") {
                    val body = call.receive<ProfileUpdateRequest>()
                    val name = Validation.displayName(body.displayName).orBadRequest()
                    call.respond(dependencies.store.updateDisplayName(call.user(dependencies).id, name).toProfile())
                }
                post("/auth/logout") {
                    dependencies.store.revokeAllUserTokens(call.user(dependencies).id, Instant.now().epochSecond)
                    call.respond(mapOf("loggedOut" to true))
                }
                delete("/account") {
                    val user = call.user(dependencies)
                    val affectedGroups = dependencies.store.listGroupIdsForUser(user.id)
                    dependencies.store.deleteAccount(user.id)
                    affectedGroups.forEach { groupId -> dependencies.liveHub.broadcast(groupId, LiveEventDto(LiveEventKind.REVOKED.wireName, userId = user.id)) }
                    dependencies.liveHub.revokeUser(user.id)
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/friends") { call.respond(dependencies.store.listFriends(call.user(dependencies).id)) }
                get("/users/search") {
                    val query = Validation.searchQuery(call.request.queryParameters["q"] ?: "").orBadRequest()
                    call.respond(dependencies.store.searchUsers(call.user(dependencies).id, query))
                }
                post("/friends/requests") {
                    val user = call.user(dependencies); val body = call.receive<FriendRequestDto>()
                    if (body.userId == user.id) throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "Cannot befriend yourself")
                    if (dependencies.store.findUserById(body.userId) == null) throw ApiException(HttpStatusCode.NotFound, "not_found", "User not found")
                    try {
                        val result = dependencies.store.createFriendRequest(user.id, body.userId, nowMillis())
                        call.respond(HttpStatusCode.Created, result)
                        return@post
                    } catch (e: Exception) {
                        if (e.isUniqueViolation()) throw ApiException(HttpStatusCode.Conflict, "friendship_exists", "A friendship already exists")
                        throw e
                    }
                }
                post("/friends/requests/{friendshipId}/respond") {
                    val body = call.receive<FriendRespondRequest>(); val result = dependencies.store.respondToFriendRequest(call.user(dependencies).id, call.parameters["friendshipId"] ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "friendshipId is required"), body.accept); call.respond(result)
                }
                get("/groups") { call.respond(dependencies.store.listGroups(call.user(dependencies).id)) }
                post("/groups") {
                    val body = call.receive<CreateGroupRequest>(); val name = Validation.groupName(body.name).orBadRequest(); call.respond(HttpStatusCode.Created, dependencies.store.createGroup(call.user(dependencies).id, name, nowMillis()))
                }
                post("/groups/join") {
                    val body = call.receive<JoinGroupRequest>(); if (body.inviteCode.length !in 6..32) throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "inviteCode is invalid"); call.respond(dependencies.store.joinGroup(call.user(dependencies).id, body.inviteCode.trim().uppercase(), nowMillis()))
                }
                post("/groups/{groupId}/leave") {
                    val user = call.user(dependencies)
                    val groupId = call.groupId()
                    dependencies.store.leaveGroup(user.id, groupId)
                    broadcastSnapshot(dependencies, groupId)
                    dependencies.liveHub.revokeUserFromGroup(groupId, user.id)
                    call.respond(mapOf("left" to true))
                }
                delete("/groups/{groupId}") { val groupId = call.groupId(); dependencies.store.deleteGroup(call.user(dependencies).id, groupId); dependencies.liveHub.revokeGroup(groupId); call.respond(HttpStatusCode.NoContent) }
                post("/sharing/start") { handleStartSharing(call, dependencies, call.receive()) }
                post("/groups/{groupId}/sharing") { handleSharingPost(call, dependencies) }
                post("/groups/{groupId}/sharing/renew") { handleRenewSharing(call, dependencies) }
                post("/groups/{groupId}/sharing/update") { handlePublishSharing(call, dependencies) }
                post("/groups/{groupId}/sharing/stop") { handleStopSharing(call, dependencies) }
                delete("/groups/{groupId}/sharing") { handleStopSharing(call, dependencies) }
                get("/voice/provider") { call.respond(dependencies.voiceService.providerResponse()) }
                post("/groups/{groupId}/voice/join") {
                    val user = call.user(dependencies)
                    val groupId = call.groupId()
                    if (!dependencies.store.isGroupMember(user.id, groupId)) {
                        throw ApiException(HttpStatusCode.Forbidden, "forbidden", "You are not a group member")
                    }
                    call.respond(dependencies.voiceService.issueJoin(user, groupId))
                }
                post("/groups/{groupId}/voice/leave") { call.respond(mapOf("left" to true)) }
            }
        }
        authenticate("auth-jwt") {
            webSocket("/v1/groups/{groupId}/live") { serveLiveGroup(call, dependencies) }
            webSocket("/v1/ws/groups/{groupId}") { serveLiveGroup(call, dependencies) }
        }
    }

    if (!dependencies.testMode) {
        scope.launch {
            while (isActive) {
                delay(LIVE_LOCATION_FRESHNESS_MILLIS)
                dependencies.store.expireShares(nowMillis()).forEach { expireAndBroadcast(dependencies, it) }
            }
        }
        monitor.subscribe(io.ktor.server.application.ApplicationStopped) { scope.cancel() }
    }
}

private suspend fun handleStartSharing(call: ApplicationCall, dependencies: AppDependencies, body: StartSharingRequest) {
    val user = call.user(dependencies)
    val profile = Validation.profile(body.profile).orBadRequest()
    if (body.ttlMillis <= 0 || body.ttlMillis > dependencies.config.maxShareTtlMillis) throw ApiException(HttpStatusCode.BadRequest, "invalid_ttl", "Sharing TTL is outside the allowed range")
    val now = nowMillis()
    val groupId = call.parameters["groupId"] ?: body.groupId
    if (body.groupId != groupId && body.groupId.isNotBlank()) throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "groupId does not match the path")
    if (!dependencies.store.isGroupMember(user.id, groupId)) throw ApiException(HttpStatusCode.Forbidden, "forbidden", "You are not a group member")
    val share = dependencies.store.startSharing(
        user.id,
        body.copy(groupId = groupId, profile = profile, startedAtEpochMillis = now),
        now + body.ttlMillis,
    )
    broadcastSnapshot(dependencies, groupId)
    call.respond(SharingResponse(share.groupId, share.profile, share.expiresAtEpochMillis))
}

private suspend fun handlePublishSharing(call: ApplicationCall, dependencies: AppDependencies, suppliedBody: PublishSharingRequest? = null) {
    val user = call.user(dependencies)
    val groupId = call.groupId()
    if (!dependencies.store.isGroupMember(user.id, groupId)) throw ApiException(HttpStatusCode.Forbidden, "forbidden", "You are not a group member")
    val now = nowMillis()
    val currentShare = dependencies.store.getShare(user.id, groupId)
    if (currentShare != null && now >= currentShare.expiresAtEpochMillis) {
        dependencies.store.expireShares(now).forEach { expireAndBroadcast(dependencies, it) }
        throw ApiException(HttpStatusCode.Conflict, "sharing_expired", "Sharing has expired")
    }
    dependencies.store.expireShares(now).forEach { expireAndBroadcast(dependencies, it) }
    val share = dependencies.store.getShare(user.id, groupId) ?: throw ApiException(HttpStatusCode.Conflict, "sharing_inactive", "Sharing is not active")
    val body = suppliedBody ?: call.receive<PublishSharingRequest>()
    val location = body.location ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_location", "A valid, fresh location is required while sharing")
    val serverLocation = location.copy(
        capturedAtEpochMillis = now,
        staleAfterEpochMillis = now + SERVER_LOCATION_STALE_AFTER_MILLIS,
    )
    if (!validLocation(serverLocation, now)) throw ApiException(HttpStatusCode.BadRequest, "invalid_location", "A valid, fresh location is required while sharing")
    if (!SharingRules.isPublishable(share.startedAtEpochMillis, share.expiresAtEpochMillis, now, now)) throw ApiException(HttpStatusCode.BadRequest, "invalid_timestamp", "Update timestamp is outside the active share window")
    val telemetry = sanitizeTelemetry(body.telemetry, share.profile)
    if (!dependencies.store.publishSharing(user.id, groupId, serverLocation, telemetry, now, now)) {
        throw ApiException(HttpStatusCode.Conflict, "sharing_inactive", "Sharing is not active")
    }
    dependencies.liveHub.broadcast(groupId, LiveEventDto(LiveEventKind.SNAPSHOT.wireName, dependencies.store.snapshot(groupId, now)))
    call.respond(mapOf("published" to true))
}

private suspend fun handleRenewSharing(call: ApplicationCall, dependencies: AppDependencies) {
    val user = call.user(dependencies)
    val groupId = call.groupId()
    if (!dependencies.store.isGroupMember(user.id, groupId)) throw ApiException(HttpStatusCode.Forbidden, "forbidden", "You are not a group member")
    val now = nowMillis()
    val current = dependencies.store.getShare(user.id, groupId)
    if (current != null && now >= current.expiresAtEpochMillis) {
        dependencies.store.expireShares(now).forEach { expireAndBroadcast(dependencies, it) }
        throw ApiException(HttpStatusCode.Conflict, "sharing_expired", "Sharing has expired")
    }
    dependencies.store.expireShares(now).forEach { expireAndBroadcast(dependencies, it) }
    val active = dependencies.store.getShare(user.id, groupId)
        ?: throw ApiException(HttpStatusCode.Conflict, "sharing_inactive", "Sharing is not active")
    val body = call.receive<RenewSharingRequest>()
    if (!SharingRules.isTtlValid(body.ttlMillis, dependencies.config.maxShareTtlMillis)) {
        throw ApiException(HttpStatusCode.BadRequest, "invalid_ttl", "Sharing TTL is outside the allowed range")
    }
    val renewed = dependencies.store.startSharing(
        user.id,
        StartSharingRequest(groupId, active.profile, body.ttlMillis, now),
        active.expiresAtEpochMillis + body.ttlMillis,
    )
    broadcastSnapshot(dependencies, groupId)
    call.respond(SharingResponse(renewed.groupId, renewed.profile, renewed.expiresAtEpochMillis))
}

private suspend fun handleSharingPost(call: ApplicationCall, dependencies: AppDependencies) {
    val payload = call.receive<JsonObject>()
    if (payload.containsKey("ttlMillis")) {
        handleStartSharing(call, dependencies, dependencies.json.decodeFromJsonElement<StartSharingRequest>(payload))
    } else {
        handlePublishSharing(call, dependencies, dependencies.json.decodeFromJsonElement<PublishSharingRequest>(payload))
    }
}

private suspend fun handleStopSharing(call: ApplicationCall, dependencies: AppDependencies) {
    val user = call.user(dependencies)
    val groupId = call.groupId()
    if (!dependencies.store.isGroupMember(user.id, groupId)) throw ApiException(HttpStatusCode.Forbidden, "forbidden", "You are not a group member")
    val stopped = dependencies.store.stopSharing(user.id, groupId)
    if (stopped) dependencies.liveHub.broadcast(groupId, LiveEventDto(LiveEventKind.REVOKED.wireName, userId = user.id))
    call.respond(mapOf("stopped" to stopped))
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.serveLiveGroup(call: ApplicationCall, dependencies: AppDependencies) {
    val user = call.user(dependencies)
    val groupId = call.groupId()
    if (!dependencies.store.isGroupMember(user.id, groupId)) {
        send(Frame.Text(dependencies.json.encodeToString(LiveEventDto(LiveEventKind.TERMINATED.wireName))))
        close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "not a group member"))
        return
    }
    if (!dependencies.liveHub.addAndRevalidate(groupId, user.id, this) { dependencies.store.isGroupMember(user.id, groupId) }) return
    try {
        dependencies.store.expireShares(nowMillis()).forEach { expireAndBroadcast(dependencies, it) }
        send(Frame.Text(dependencies.json.encodeToString(LiveEventDto(LiveEventKind.SNAPSHOT.wireName, dependencies.store.snapshot(groupId, nowMillis())))))
        for (frame in incoming) if (frame is Frame.Close) break
    }
    finally { dependencies.liveHub.remove(groupId, this) }
}

private suspend fun broadcastSnapshot(dependencies: AppDependencies, groupId: String) {
    val now = nowMillis()
    try {
        dependencies.store.expireShares(now).forEach { expireAndBroadcast(dependencies, it) }
    } catch (error: IllegalStateException) {
        if (!dependencies.testMode) throw error
    }
    val snapshot = try {
        dependencies.store.snapshot(groupId, now)
    } catch (error: IllegalStateException) {
        if (!dependencies.testMode) throw error
        null
    }
    dependencies.liveHub.broadcast(groupId, LiveEventDto(LiveEventKind.SNAPSHOT.wireName, snapshot))
}

private suspend fun expireAndBroadcast(dependencies: AppDependencies, share: ShareRow) {
    dependencies.liveHub.broadcast(share.groupId, LiveEventDto(LiveEventKind.EXPIRED.wireName, userId = share.userId))
}

private fun issueOneTimeToken(dependencies: AppDependencies, userId: String, purpose: String): String {
    val raw = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString()
    dependencies.store.createOneTimeToken(userId, purpose, sha256(raw), nowMillis() + if (purpose == "EMAIL_VERIFY") 86_400_000 else 3_600_000)
    return raw
}

private fun issueSession(dependencies: AppDependencies, user: UserRecord, nowEpochSeconds: Long = Instant.now().epochSecond): SessionResponse {
    val refresh = dependencies.refreshTokenService.issueRefreshToken(user.id, nowEpochSeconds)
    dependencies.store.insertRefreshToken(user.id, refresh.hash, refresh.expiresAtEpochSeconds, nowEpochSeconds)
    return SessionResponse(
        accessToken = dependencies.tokenService.issueAccessToken(user.id, nowEpochSeconds),
        refreshToken = refresh.raw,
        expiresAtEpochMillis = (nowEpochSeconds + dependencies.config.accessTtlSeconds) * 1_000,
    )
}

private fun sanitizeTelemetry(value: SharedTelemetryDto?, profile: String): SharedTelemetryDto? {
    if (profile == "LOCATION") {
        if (value != null) throw ApiException(HttpStatusCode.BadRequest, "invalid_telemetry", "LOCATION sharing cannot include telemetry")
        return null
    }
    if (value == null || value.profile != profile) throw ApiException(HttpStatusCode.BadRequest, "invalid_telemetry", "Telemetry profile must match the active sharing profile")
    return if (profile == "FULL") value else value.copy(
        profile = "RIDE",
        packVoltageV = TelemetryNumberDto(false, false), batteryCurrentA = TelemetryNumberDto(false, false), escTempC = TelemetryNumberDto(false, false), motorTempC = TelemetryNumberDto(false, false), cellMinV = TelemetryNumberDto(false, false), cellMaxV = TelemetryNumberDto(false, false), cellDeltaV = TelemetryNumberDto(false, false), faults = TelemetryFaultsDto(false, false),
    )
}

private fun validLocation(location: LocationDto, now: Long? = null): Boolean = location.latitude.isFinite() && location.latitude in -90.0..90.0 && location.longitude.isFinite() && location.longitude in -180.0..180.0 && location.accuracyMeters.isFinite() && location.accuracyMeters >= 0 && location.staleAfterEpochMillis >= location.capturedAtEpochMillis && (now == null || SharingRules.isLocationFresh(location.staleAfterEpochMillis, now))

private const val SERVER_LOCATION_STALE_AFTER_MILLIS = LIVE_LOCATION_FRESHNESS_MILLIS

private fun ApplicationCall.user(dependencies: AppDependencies): UserRecord {
    val principal = principal<JWTPrincipal>() ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Authentication is required")
    return dependencies.store.findUserById(principal.payload.subject) ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Account is unavailable")
}

private fun UserRecord.toProfile() = ProfileResponse(id, displayName, emailVerified = emailVerified)
private fun ApplicationCall.groupId(): String = parameters["groupId"] ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "groupId is required")
internal fun ApplicationCall.requestId(): String = callId ?: "unknown"
private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String, details: Map<String, String>? = null) = respond(status, ApiError(code, message, requestId(), details))
private fun <T> Result<T>.orBadRequest(): T = getOrElse { throw ApiException(HttpStatusCode.BadRequest, "invalid_request", it.message ?: "Request is invalid") }
private fun Throwable.isUniqueViolation(): Boolean = this is java.sql.SQLException && sqlState == "23505"
