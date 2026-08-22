package ru.sodovaya.volty.domain.social

object SocialSessionPolicy {
    fun requiresAuthentication(session: SocialSession): Boolean =
        session !is SocialSession.Authenticated ||
            session.tokenState != SessionTokenState.ACTIVE

    /** Compatibility hook for old callers; this backend has no mail gate. */
    fun requiresEmailVerification(session: SocialSession): Boolean = false
}

enum class LocationShareStatus {
    NOT_STARTED,
    ACTIVE,
    EXPIRED,
    REVOKED,
}

enum class LocationSnapshotStatus {
    FRESH,
    STALE,
}

sealed interface LocationShareStartResult {
    data class Started(val window: LocationShareWindow) : LocationShareStartResult
    data object NotAuthenticated : LocationShareStartResult
    data object InvalidTtl : LocationShareStartResult
}

object LocationSharePolicy {
    const val maxTtlMillis: Long = 24L * 60L * 60L * 1_000L

    fun start(
        session: SocialSession,
        audienceGroupId: RideGroupId,
        nowEpochMillis: Long,
        ttlMillis: Long,
    ): LocationShareStartResult {
        if (SocialSessionPolicy.requiresAuthentication(session)) {
            return LocationShareStartResult.NotAuthenticated
        }
        if (
            ttlMillis <= 0L ||
            ttlMillis > maxTtlMillis ||
            nowEpochMillis > Long.MAX_VALUE - ttlMillis
        ) {
            return LocationShareStartResult.InvalidTtl
        }
        return LocationShareStartResult.Started(
            LocationShareWindow(
                audienceGroupId = audienceGroupId,
                startedAtEpochMillis = nowEpochMillis,
                expiresAtEpochMillis = nowEpochMillis + ttlMillis,
            ),
        )
    }

    fun status(
        window: LocationShareWindow,
        nowEpochMillis: Long,
        revoked: Boolean = false,
    ): LocationShareStatus = when {
        revoked -> LocationShareStatus.REVOKED
        nowEpochMillis < window.startedAtEpochMillis -> LocationShareStatus.NOT_STARTED
        nowEpochMillis >= window.expiresAtEpochMillis -> LocationShareStatus.EXPIRED
        else -> LocationShareStatus.ACTIVE
    }

    fun snapshotStatus(
        location: LocationSnapshot,
        nowEpochMillis: Long,
    ): LocationSnapshotStatus = if (nowEpochMillis >= location.staleAfterEpochMillis) {
        LocationSnapshotStatus.STALE
    } else {
        LocationSnapshotStatus.FRESH
    }

    /** Expired or stale data is retained for honest UI state but never republished. */
    fun shouldPublish(
        window: LocationShareWindow,
        location: LocationSnapshot,
        nowEpochMillis: Long,
    ): Boolean =
        status(window, nowEpochMillis) == LocationShareStatus.ACTIVE &&
            snapshotStatus(location, nowEpochMillis) == LocationSnapshotStatus.FRESH &&
            location.capturedAtEpochMillis >= window.startedAtEpochMillis &&
            location.capturedAtEpochMillis <= nowEpochMillis
}

object TelemetryShareMapper {
    fun map(profile: TelemetryShareProfile, earned: EarnedTelemetry): SharedTelemetry {
        val shareRide = profile == TelemetryShareProfile.RIDE || profile == TelemetryShareProfile.FULL
        val shareFull = profile == TelemetryShareProfile.FULL
        return SharedTelemetry(
            profile = profile,
            speedKmh = earned.speedKmh.include(shareRide),
            batterySocFraction = earned.batterySocFraction.include(shareRide),
            packVoltageV = earned.packVoltageV.include(shareFull),
            batteryCurrentA = earned.batteryCurrentA.include(shareFull),
            powerW = earned.powerW.include(shareRide),
            escTempC = earned.escTempC.include(shareFull),
            motorTempC = earned.motorTempC.include(shareFull),
            cellMinV = earned.cellMinV.include(shareFull),
            cellMaxV = earned.cellMaxV.include(shareFull),
            cellDeltaV = earned.cellDeltaV.include(shareFull),
            faults = earned.faults.include(shareFull),
        )
    }

    private fun TelemetryNumber.include(allowed: Boolean): TelemetryNumber =
        if (allowed) this else TelemetryNumber.unsupported()

    private fun TelemetryFaults.include(allowed: Boolean): TelemetryFaults =
        if (allowed) this else TelemetryFaults.unsupported()
}
