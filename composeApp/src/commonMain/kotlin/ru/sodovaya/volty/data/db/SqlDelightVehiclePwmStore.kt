package ru.sodovaya.volty.data.db

import ru.sodovaya.volty.domain.pwm.AlarmOnset
import ru.sodovaya.volty.domain.pwm.PwmBasis
import ru.sodovaya.volty.domain.pwm.PwmCalibration
import ru.sodovaya.volty.domain.pwm.PwmCalibrationLearner
import ru.sodovaya.volty.domain.pwm.PwmCalibrationSource
import ru.sodovaya.volty.domain.pwm.PwmEvidence
import ru.sodovaya.volty.domain.pwm.PwmMemory
import ru.sodovaya.volty.domain.pwm.VehiclePwmStore

/**
 * [VehiclePwmStore] over `VehiclePwmRow` - a table of its own (PWM is not energy, and it is not keyed by
 * the pack shape). The onset history is one compact text column, see [encodeOnsets].
 */
class SqlDelightVehiclePwmStore(provider: VoltyDatabaseProvider) : VehiclePwmStore {
    private val queries = provider.database.vehiclePwmRowQueries

    override suspend fun load(vehicleId: String): PwmMemory {
        val row = queries.selectByVehicle(vehicleId).executeAsOneOrNull() ?: return PwmMemory.EMPTY
        val evidence = runCatching { PwmEvidence.valueOf(row.evidence) }.getOrDefault(PwmEvidence.UNKNOWN)
        return PwmMemory(calibrationOf(row), evidence)
    }

    private fun calibrationOf(row: VehiclePwmRow): PwmCalibration? {
        val k = row.k?.toFloat()?.takeIf { it.isFinite() } ?: return null
        val r = row.r?.toFloat()?.takeIf { it.isFinite() } ?: return null
        val source = row.source?.let { s -> runCatching { PwmCalibrationSource.valueOf(s) }.getOrNull() } ?: return null
        val basis = row.basis?.let { b -> runCatching { PwmBasis.valueOf(b) }.getOrNull() } ?: return null
        return PwmCalibration(
            k = k, r = r, points = row.points.toInt(), source = source,
            spread = row.spread?.toFloat()?.takeIf { it.isFinite() },
            basis = basis, onsets = decodeOnsets(row.onsets)
        )
    }

    override suspend fun save(vehicleId: String, memory: PwmMemory) {
        // Ruling R3, in one transaction (read-modify-write): the stored row is a floor, not a slate.
        queries.transaction {
            val row = queries.selectByVehicle(vehicleId).executeAsOneOrNull()
            val storedEvidence = row?.let { r -> runCatching { PwmEvidence.valueOf(r.evidence) }.getOrDefault(PwmEvidence.UNKNOWN) }
                ?: PwmEvidence.UNKNOWN
            val storedCal = row?.let(::calibrationOf)
            write(vehicleId, PwmEvidence.monotone(storedEvidence, memory.evidence), memory.calibration.takeIfFinite() ?: storedCal)
        }
    }

    private fun PwmCalibration?.takeIfFinite(): PwmCalibration? = this?.takeIf { it.k.isFinite() && it.r.isFinite() }

    override suspend fun resetCalibration(vehicleId: String) {
        queries.transaction {
            val row = queries.selectByVehicle(vehicleId).executeAsOneOrNull() ?: return@transaction
            val evidence = runCatching { PwmEvidence.valueOf(row.evidence) }.getOrDefault(PwmEvidence.UNKNOWN)
            write(vehicleId, evidence, null)
        }
    }

    private fun write(vehicleId: String, evidence: PwmEvidence, calibration: PwmCalibration?) {
        // A calibration with a non-finite k or r is dropped whole (it would poison every estimate); the
        // evidence is kept - it is a separate fact.
        val cal = calibration
        queries.upsert(
            vehicleId = vehicleId,
            evidence = evidence.name,
            k = cal?.k?.toDouble(),
            r = cal?.r?.toDouble(),
            points = cal?.points?.toLong() ?: 0L,
            source = cal?.source?.name,
            spread = cal?.spread?.takeIf { it.isFinite() }?.toDouble(),
            basis = cal?.basis?.name,
            onsets = cal?.let { encodeOnsets(it.onsets) } ?: ""
        )
    }

    override suspend fun reset(vehicleId: String) {
        queries.deleteByVehicle(vehicleId)
    }
}

/**
 * `speedKmh,packV,phaseA|-,atMillis` entries joined by `;`, newest last. Non-finite onsets are dropped and the
 * list is cut to the newest [PwmCalibrationLearner.MAX_ONSETS]. Floats use Kotlin's shortest round-trip text.
 */
internal fun encodeOnsets(onsets: List<AlarmOnset>): String =
    onsets.filter { it.speedKmh.isFinite() && it.packV.isFinite() && (it.phaseA == null || it.phaseA.isFinite()) }
        .takeLast(PwmCalibrationLearner.MAX_ONSETS)
        .joinToString(";") { "${it.speedKmh},${it.packV},${it.phaseA ?: "-"},${it.atMillis}" }

/** Inverse of [encodeOnsets]; a malformed or non-finite entry is skipped, never fatal. */
internal fun decodeOnsets(text: String): List<AlarmOnset> =
    text.split(';').mapNotNull { entry ->
        val p = entry.split(',')
        if (p.size != 4) return@mapNotNull null
        val speed = p[0].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
        val packV = p[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
        val phase = if (p[2] == "-") null else (p[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null)
        val at = p[3].toLongOrNull() ?: return@mapNotNull null
        AlarmOnset(speed, packV, phase, at)
    }.takeLast(PwmCalibrationLearner.MAX_ONSETS)
