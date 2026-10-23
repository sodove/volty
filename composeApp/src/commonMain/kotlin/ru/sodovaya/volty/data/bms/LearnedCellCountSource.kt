package ru.sodovaya.volty.data.bms

/**
 * The narrowest read of what the live connection has PROVEN about the active vehicle's series
 * cell count: the Begode decoder's `derivedCellCount()`, or null when nothing is connected or the
 * link has not proven one yet.
 *
 * Its own interface, like [ControllerConfigSource], so the vehicle editor can label its S row
 * "determined by the wheel" without growing `BmsRepository` (eleven fakes implement that).
 * A snapshot, not a flow: the editor re-reads it on every live emission, which is when the
 * decoder learns it.
 */
interface LearnedCellCountSource {
    fun learnedCellCount(): Int?
}
