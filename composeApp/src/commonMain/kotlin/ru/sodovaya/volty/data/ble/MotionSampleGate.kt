package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.ControllerData

/**
 * Lets a sample through only when the protocol actually produced a NEW one
 * for that controller.
 *
 * The motion twin of [PackSampleGate]: [ConnectionSession] would read every
 * controller's `latestMotion` on every notification, and a [MotionSource]
 * caches its last decode — so without this gate a controller that stopped
 * reporting keeps being re-submitted with its frozen data at the
 * notification rate, and a staleness check would never see it go quiet.
 *
 * The discriminator is instance IDENTITY (`===`), not structural equality:
 * a [MotionSource] replaces its cached `ControllerData` wholesale on a
 * successful decode and never mutates it in place, so a new instance means
 * a real decode happened. Equality would wrongly suppress a parked vehicle
 * whose consecutive decodes carry identical values — those are real proof
 * of life.
 *
 * Not thread-safe, by design: it lives inside one session's single observe
 * coroutine, the same funnel [VehicleConnection] relies on.
 */
internal class MotionSampleGate(controllerCount: Int) {

    private val lastSeen = arrayOfNulls<ControllerData>(controllerCount)

    /**
     * Returns true — and records the instance — when [sample] is a different
     * instance than the last one seen for [controllerIndex]; false when it
     * is the source's cached decode being re-read.
     */
    fun advance(controllerIndex: Int, sample: ControllerData): Boolean {
        if (lastSeen[controllerIndex] === sample) return false
        lastSeen[controllerIndex] = sample
        return true
    }
}
