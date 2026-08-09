package ru.sodovaya.volty.data.bms.vesc

import kotlin.math.roundToInt
import ru.sodovaya.volty.domain.model.MotorConfig

/**
 * The fixed-layout answer to VESC's `COMM_GET_MCCONF_TEMP` request.
 *
 * The frame contains more limits than this app currently consumes. They stay
 * here as named values so decoding keeps the wire order explicit, while
 * [motorConfig] exposes only the geometry that Volty can use.
 */
data class VescSetupConfig(
    val maxErpm: Float,
    val maxWattsOut: Float,
    val maxInputCurrentA: Float,
    val motorPoles: Int,
    val gearRatio: Float,
    val wheelDiameterM: Float
) {
    /**
     * The controller's usable geometry, or null when its configuration cannot
     * produce a meaningful speed calculation.
     */
    val motorConfig: MotorConfig?
        get() = if (wheelDiameterM <= 0f || gearRatio <= 0f || motorPoles < 2) {
            null
        } else {
            MotorConfig(
                polePairs = motorPoles / 2,
                wheelDiameterMm = (wheelDiameterM * 1000f).roundToInt(),
                gearRatio = gearRatio
            )
        }

    companion object {
        const val OPCODE_GET_MCCONF_TEMP = 91
        private const val BODY_BYTES = 49

        fun decode(payload: ByteArray): VescSetupConfig? {
            val reader = VescReader(payload)
            if (!reader.has(1) || reader.u8() != OPCODE_GET_MCCONF_TEMP) return null
            if (!reader.has(BODY_BYTES)) return null

            reader.f32auto()
            reader.f32auto()
            reader.f32auto()
            val maxErpm = reader.f32auto()
            reader.f32auto()
            reader.f32auto()
            reader.f32auto()
            val maxWattsOut = reader.f32auto()
            reader.f32auto()
            val maxInputCurrentA = reader.f32auto()

            return VescSetupConfig(
                maxErpm = maxErpm,
                maxWattsOut = maxWattsOut,
                maxInputCurrentA = maxInputCurrentA,
                motorPoles = reader.u8(),
                gearRatio = reader.f32auto(),
                wheelDiameterM = reader.f32auto()
            )
        }
    }
}
