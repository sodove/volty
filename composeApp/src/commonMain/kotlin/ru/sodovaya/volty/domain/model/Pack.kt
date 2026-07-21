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
 * Extend a pack list to [count] slots, synthesising the missing ones from the
 * first pack.
 *
 * A protocol can know more about the battery than the stored profile does: a
 * Begode wheel reports two branches while the vehicle was created with one
 * pack. The synthesised packs share the real pack's [Pack.bmsType] and
 * [Pack.bmsAddress] because that is physically true — the wheel multiplexes
 * every branch over ONE BLE link — and get a positional label, since copying
 * the vehicle's name would show two identically-named packs in the UI.
 *
 * The label is deliberately NOT localised: [Pack] is a persisted domain entity
 * (it is written to the database and read back on a later launch, possibly
 * under a different locale) and `domain/model` has no Compose-resources
 * dependency to load a string from. Localising it would mean either dragging
 * resource loading into the domain layer or storing a locale-stamped string.
 *
 * Returns the receiver unchanged when it already has [count] packs or more —
 * a single-pack BMS never grows a slot, and a profile the user has configured
 * with MORE packs than the protocol reports is left alone too.
 */
fun List<Pack>.expandedTo(count: Int): List<Pack> {
    if (isEmpty() || size >= count) return this
    val template = first()
    return this + (size until count).map { i ->
        Pack(
            index = i,
            label = "Pack ${i + 1}",
            bmsType = template.bmsType,
            bmsAddress = template.bmsAddress
        )
    }
}

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
