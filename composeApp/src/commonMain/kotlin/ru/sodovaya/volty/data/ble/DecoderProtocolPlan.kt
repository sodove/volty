package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.bms.MotionSource
import ru.sodovaya.volty.domain.model.TelemetryOrigin
import ru.sodovaya.volty.domain.model.TelemetryRole
import ru.sodovaya.volty.domain.model.requireTelemetryAddress

/** One local observation address a decoder may publish. */
internal data class DecoderSource(
    val origin: TelemetryOrigin,
    val localIndex: Int,
    val role: TelemetryRole = TelemetryRole.CONTRIBUTOR,
    val assemblyIndex: Int? = null,
) {
    init {
        requireTelemetryAddress(origin, localIndex, role, assemblyIndex)
    }
}

/** Pure decoder topology plus the single factory that builds that topology. */
internal class DecoderProtocolPlan(
    val packCount: Int,
    val controllerCount: Int,
    sources: List<DecoderSource>,
    private val factory: () -> BmsProtocol,
) {
    val sources: List<DecoderSource> = sources.toList()

    init {
        require(packCount >= 0)
        require(controllerCount >= 0)
    }

    fun create(): BmsProtocol {
        val protocol = factory()
        check(protocol.packCount == packCount) {
            "Decoder plan declares $packCount packs but its protocol reports ${protocol.packCount}"
        }
        val actualControllerCount = (protocol as? MotionSource)?.controllerCount ?: 0
        check(actualControllerCount == controllerCount) {
            "Decoder plan declares $controllerCount controllers but its protocol reports $actualControllerCount"
        }
        return protocol
    }
}
