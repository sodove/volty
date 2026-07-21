package ru.sodovaya.volty.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * How a vehicle's packs are wired together. Drives both the aggregation
 * formulas and how a missing pack is treated: in parallel a pack can be
 * switched out on purpose, in series a missing pack makes the aggregate
 * physically meaningless.
 */
enum class PackTopology { PARALLEL, SERIES }

/**
 * Configuration of a single battery pack: where to read it from. Persisted.
 *
 * Packs of one Begode wheel share a single [bmsAddress] — the wheel exposes
 * both of them over one BLE link. A group of independent BMS has a distinct
 * address per pack.
 */
data class Pack(
    /** 0-based; defines ordering in the UI. */
    val index: Int,
    val label: String,
    val bmsType: BmsType,
    val bmsAddress: String,
    val cellCount: Int? = null
)

/**
 * A physically replaceable assembly inside a pack (Begode: four 12S/20S
 * assemblies wired 2S2P). Empty when the BMS reports no such breakdown,
 * which is every BMS volty supports today.
 */
data class SectionState(
    val index: Int,
    val voltage: Float,
    val temperatures: List<Float> = emptyList()
)

/** Live state of one pack. */
@OptIn(ExperimentalTime::class)
data class PackState(
    val pack: Pack,
    val data: BmsData,
    val sections: List<SectionState> = emptyList(),
    val isOnline: Boolean = false,
    val lastSeenAt: Instant? = null
)
