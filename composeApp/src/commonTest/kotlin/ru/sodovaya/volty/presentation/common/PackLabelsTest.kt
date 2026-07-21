package ru.sodovaya.volty.presentation.common

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack
import kotlin.test.Test
import kotlin.test.assertEquals

class PackLabelsTest {

    private fun pack(index: Int, label: String) =
        Pack(index = index, label = label, bmsType = BmsType.BEGODE, bmsAddress = "AA:BB")

    @Test
    fun singlePackKeepsItsOwnStoredName() {
        // A one-pack vehicle's pack is labelled after the vehicle by
        // singlePackVehicle(); there is nothing to compare it against, so the
        // user's own name passes through verbatim.
        val label = packLabelFor(pack(index = 0, label = "GOTWAY_75042"), packCount = 1)
        assertEquals(PackLabel.Own("GOTWAY_75042"), label)
    }

    @Test
    fun multiPackYieldsPositionalLabelsForEveryIndexIncludingTheFirst() {
        // Stored labels of a multi-pack Begode are inconsistent: pack 0
        // carries the vehicle name, synthesised packs carry "Pack N". On
        // display EVERY branch must be positional — including the first,
        // whose stored label is the vehicle's own name.
        assertEquals(
            PackLabel.Positional(1),
            packLabelFor(pack(index = 0, label = "GOTWAY_75042"), packCount = 2)
        )
        assertEquals(
            PackLabel.Positional(2),
            packLabelFor(pack(index = 1, label = "Pack 2"), packCount = 2)
        )
    }

    @Test
    fun positionalNumberFollowsThePackIndexNotTheStoredLabel() {
        // A stored label that happens to disagree with the index must not
        // leak through: the branch number is the pack's identity in the UI.
        assertEquals(
            PackLabel.Positional(3),
            packLabelFor(pack(index = 2, label = "My custom name"), packCount = 3)
        )
    }
}
