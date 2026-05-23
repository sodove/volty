package com.volty.app.data.bms

/** BLE UUIDs for a BMS type. notifyCharUuid may equal writeCharUuid for single-characteristic BMS. */
data class BmsUuids(
    val serviceUuid: String,
    val notifyCharUuid: String,
    val writeCharUuid: String
)
