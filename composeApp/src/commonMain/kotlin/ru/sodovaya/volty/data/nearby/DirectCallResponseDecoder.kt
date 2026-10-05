package ru.sodovaya.volty.data.nearby

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.*
import ru.sodovaya.volty.data.voice.VoiceSessionResponseDecoder
import ru.sodovaya.volty.domain.voice.*

internal class DirectCallResponseDecoder(private val actor: String) {
    fun action(root: JsonObject, expected: String, callee: String?, accepting: Boolean,
        mediaAllowed: Boolean, terminalOnly: Boolean, generation: VoiceGeneration?, operation: VoiceOperationId?,
        status: HttpStatusCode, ordinal: VoiceLeaseRequestId, started: Long, received: Long): DirectCallReply.Ready {
        val call = snapshot(objectValue(requireNotNull(root["call"])))
        require(call.callId == expected && actor in listOf(call.callerId, call.calleeId))
        if (callee != null) require(call.callerId == actor && call.calleeId == callee)
        if (accepting) require(call.calleeId == actor)
        require(!terminalOnly || call.terminal)
        val failure = optionalString(root, "retryableFailure")
        require((status == HttpStatusCode.ServiceUnavailable) == (failure != null))
        failure?.let {require(it in setOf("UNSUPPORTED", "INVALID_RESPONSE", "FORBIDDEN", "REJECTED", "UNAVAILABLE", "TIMEOUT", "INTERRUPTED"))}
        val media = root["session"]?.takeUnless {it == JsonNull}?.let {
            require(mediaAllowed)
            val document = objectValue(it)
            val positive = string(document, "phase") !in setOf("CANCELLED", "EXPIRED")
            require(!positive || (call.phase == DirectCallPhase.ACCEPTED && failure == null))
            VoiceSessionResponseDecoder.decode(document, requireNotNull(generation), operation, null,
                allowCredentials = failure == null && call.phase == DirectCallPhase.ACCEPTED,
                terminalOnly = call.terminal || failure != null, ordinal = ordinal, started = started, received = received,
                requireCredentials = false, expectedRoom = "volty-call-${call.callId}")
        }
        require(status != HttpStatusCode.Accepted || (call.phase == DirectCallPhase.ACCEPTED &&
            media is ru.sodovaya.volty.data.voice.VoiceHttpReply.Pending))
        val ring = root["remainingRingMillis"]?.takeUnless {it == JsonNull}?.let {
            val remaining = long(root, "remainingRingMillis")
            require(call.phase == DirectCallPhase.RINGING && failure == null && remaining in 0..45_000)
            require(started >= 0 && received >= started && started <= Long.MAX_VALUE - remaining)
            DirectCallRingObservation(call.callId, call.revision, ordinal, started, received, started + remaining)
        }
        return DirectCallReply.Ready(call, media, failure, ring)
    }

    fun incoming(root: JsonObject, after: String?, limit: Int): IncomingCallReply.Ready {
        val values = root["calls"]
        require(values is JsonArray && values.size <= limit)
        val calls = values.map {snapshot(objectValue(it))}
        require(calls.all {it.calleeId == actor && it.phase == DirectCallPhase.RINGING && (after == null || it.callId > after)})
        require(calls.zipWithNext().all {(a, b) -> a.callId < b.callId})
        val cursor = optionalString(root, "nextCursor")?.also(::directCallId)
        require(cursor == null || ((after == null || cursor > after) && calls.all {it.callId <= cursor}))
        return IncomingCallReply.Ready(calls, cursor)
    }

    private fun snapshot(root: JsonObject): DirectCallSnapshot {
        val call = string(root, "callId").also(::directCallId)
        val caller = string(root, "callerId").also(::directCallId)
        val callee = string(root, "calleeId").also(::directCallId)
        require(caller != callee)
        val phase = DirectCallPhase.valueOf(string(root, "phase"))
        val outcome = optionalString(root, "outcome")?.let(DirectCallOutcome::valueOf)
        val revision = long(root, "revision").also {require(it >= 0)}
        val deadline = long(root, "ringDeadlineEpochMillis").also {require(it >= 0)}
        require(when (phase) {
            DirectCallPhase.RINGING, DirectCallPhase.ACCEPTED -> outcome == null
            DirectCallPhase.DECLINED -> outcome == DirectCallOutcome.CALLEE_DECLINED
            DirectCallPhase.CANCELLED -> outcome in setOf(DirectCallOutcome.CALLER_CANCELLED, DirectCallOutcome.CALLEE_CANCELLED, DirectCallOutcome.FRIENDSHIP_REVOKED)
            DirectCallPhase.MISSED -> outcome == DirectCallOutcome.RING_TIMEOUT
            DirectCallPhase.ENDED -> outcome in setOf(DirectCallOutcome.CALLER_ENDED, DirectCallOutcome.CALLEE_ENDED, DirectCallOutcome.FRIENDSHIP_REVOKED, DirectCallOutcome.MEDIA_TIMEOUT, DirectCallOutcome.MEDIA_LOST)
        })
        return DirectCallSnapshot(call, caller, callee, phase, revision, deadline, outcome)
    }
    private fun objectValue(value: JsonElement): JsonObject {require(value is JsonObject); return value}
    private fun string(root: JsonObject, name: String): String {
        val value = root[name]; require(value is JsonPrimitive && value.isString); return value.content
    }
    private fun optionalString(root: JsonObject, name: String) = root[name]?.takeUnless {it == JsonNull}?.let {string(root, name)}
    private fun long(root: JsonObject, name: String): Long {
        val value = root[name]; require(value is JsonPrimitive && !value.isString); return requireNotNull(value.longOrNull)
    }
}

private val directCallUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
internal fun directCallId(value: String) {require(directCallUuid.matches(value))}
