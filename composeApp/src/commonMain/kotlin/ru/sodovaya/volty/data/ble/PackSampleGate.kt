package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData

/**
 * Lets a sample through only when the protocol actually produced a NEW one
 * for that pack.
 *
 * [ConnectionSession] reads every pack's `latestData` on every notification,
 * and every protocol caches its last decode — so without this gate a pack
 * that stopped reporting (a Begode branch switched out by its balancing
 * board) keeps being re-submitted with its frozen data at the notification
 * rate. [VehicleConnection]'s per-pack staleness check would then see a
 * "sample" for the dead pack every few hundred milliseconds and never mark
 * it offline.
 *
 * The discriminator is instance IDENTITY (`===`), not structural equality:
 * every protocol replaces its cached `BmsData` wholesale on a successful
 * decode and never mutates it in place (pinned by the identity-contract test
 * in `BegodeProtocolTest`), so a new instance means a real decode happened.
 * Equality would wrongly suppress a parked vehicle whose consecutive decodes
 * carry identical values — those are real proof of life.
 *
 * Not thread-safe, by design: it lives inside one session's single observe
 * coroutine, the same funnel [VehicleConnection] relies on.
 */
internal class PackSampleGate(packCount: Int) {

    private val lastSeen = arrayOfNulls<BmsData>(packCount)

    /**
     * Returns true — and records the instance — when [sample] is a different
     * instance than the last one seen for [packIndex]; false when it is the
     * protocol's cached decode being re-read.
     */
    fun advance(packIndex: Int, sample: BmsData): Boolean {
        if (lastSeen[packIndex] === sample) return false
        lastSeen[packIndex] = sample
        return true
    }
}
