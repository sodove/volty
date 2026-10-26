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
    suspend fun save(vehicleId: String, memory: PwmMemory)
    suspend fun reset(vehicleId: String)
}
