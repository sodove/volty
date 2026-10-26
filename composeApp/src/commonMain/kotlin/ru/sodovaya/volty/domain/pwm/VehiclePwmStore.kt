package ru.sodovaya.volty.domain.pwm

/** What the app has learned about one wheel PWM: the evidence it reports none, and the calibration (null until earned). */
data class PwmMemory(val calibration: PwmCalibration?, val evidence: PwmEvidence) {
    companion object {
        val EMPTY = PwmMemory(null, PwmEvidence.UNKNOWN)
    }
}

/**
 * Per-vehicle PWM state, persisted. Keyed by vehicle id alone: unlike the energy model it does not depend
 * on the pack shape. Non-finite values are never stored and never loaded (treated as absent).
 */
interface VehiclePwmStore {
    /** [PwmMemory.EMPTY] for a vehicle with no row. */
    suspend fun load(vehicleId: String): PwmMemory
    /**
     * Ruling R3: a save never lowers the stored evidence (REPORTS > NEVER_REPORTED > UNKNOWN) and never
     * replaces a stored calibration with null (an evidence-only save keeps the calibration). Wiping a
     * calibration is [resetCalibration]'s job alone.
     */
    suspend fun save(vehicleId: String, memory: PwmMemory)

    /** Drops the calibration (wizard re-run / editor reset) and keeps the evidence. */
    suspend fun resetCalibration(vehicleId: String)
    suspend fun reset(vehicleId: String)
}
