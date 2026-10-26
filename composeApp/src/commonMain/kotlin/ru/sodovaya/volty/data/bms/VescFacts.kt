package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.DeviceFact
import ru.sodovaya.volty.domain.model.DeviceFactKey

/**
 * What one VESC controller has said so far. [index] is its 1-based place on a gateway link and null
 * on a plain single-controller link; a null [controllerId] / [faultCode] was not decoded.
 */
internal class VescControllerFacts(
    val index: Int?,
    val controllerId: Int?,
    val faultCode: Int?,
)

/**
 * The facts a VESC link reports, shared by [VescProtocol] and [VescGatewayProtocol] so the keys and
 * the wording of the values are one statement.
 *
 * Only what the wire has actually said is listed:
 *  - `CONTROLLER_ID`: the `controller_id` byte the node appends to a values reply
 *    ([ru.sodovaya.volty.data.bms.vesc.VescValues.controllerId]);
 *  - `FAULT_CODES`: the RAW `mc_fault_code` of the last values reply, "0" meaning no fault
 *    ([ru.sodovaya.volty.data.bms.vesc.VescValues.rawFaultCode]);
 *  - `CAN_IDS`: the ids the last `PING_CAN` answered, comma-separated, only when it answered any.
 *
 * **There is no `FIRMWARE` fact, deliberately.** Neither protocol decodes a firmware version
 * (`COMM_FW_VERSION` is never requested), and a fact is only listed once the wire has said it.
 */
internal fun vescFacts(controllers: List<VescControllerFacts>, canIds: List<Int>?): List<DeviceFact> = buildList {
    for (c in controllers) {
        c.controllerId?.let { add(DeviceFact(DeviceFactKey.CONTROLLER_ID, it.toString(), index = c.index)) }
        c.faultCode?.let { add(DeviceFact(DeviceFactKey.FAULT_CODES, it.toString(), index = c.index)) }
    }
    if (!canIds.isNullOrEmpty()) add(DeviceFact(DeviceFactKey.CAN_IDS, canIds.joinToString(", ")))
}
