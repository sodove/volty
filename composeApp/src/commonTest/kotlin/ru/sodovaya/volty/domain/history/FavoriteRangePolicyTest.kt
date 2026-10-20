package ru.sodovaya.volty.domain.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FavoriteRangePolicyTest {
    @Test
    fun containedAndEqualSelectionsArePreserved() {
        val ride = HistoryRange(100L, 200L)

        assertEquals(HistoryRange(120L, 180L), clampFavoriteRange(HistoryRange(120L, 180L), ride))
        assertEquals(ride, clampFavoriteRange(ride, ride))
    }

    @Test
    fun partialOverlapsAreClippedAtEitherRideEdge() {
        val ride = HistoryRange(100L, 200L)

        assertEquals(HistoryRange(100L, 150L), clampFavoriteRange(HistoryRange(80L, 150L), ride))
        assertEquals(HistoryRange(150L, 200L), clampFavoriteRange(HistoryRange(150L, 220L), ride))
    }

    @Test
    fun disjointSelectionsBeforeOrAfterRideAreRejected() {
        val ride = HistoryRange(100L, 200L)

        assertFailsWith<IllegalArgumentException> {
            clampFavoriteRange(HistoryRange(10L, 20L), ride)
        }
        assertFailsWith<IllegalArgumentException> {
            clampFavoriteRange(HistoryRange(220L, 230L), ride)
        }
    }

    @Test
    fun zeroWidthSelectionOrRideIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            clampFavoriteRange(HistoryRange(120L, 120L), HistoryRange(100L, 200L))
        }
        assertFailsWith<IllegalArgumentException> {
            clampFavoriteRange(HistoryRange(100L, 200L), HistoryRange(120L, 120L))
        }
    }

    @Test
    fun boundaryOnlyContactIsRejected() {
        val ride = HistoryRange(100L, 150L)

        assertFailsWith<IllegalArgumentException> {
            clampFavoriteRange(HistoryRange(50L, 100L), ride)
        }
        assertFailsWith<IllegalArgumentException> {
            clampFavoriteRange(HistoryRange(150L, 200L), ride)
        }
    }

    @Test
    fun extremeLongBoundsAreIntersectedWithoutWidthOverflow() {
        val ride = HistoryRange(Long.MIN_VALUE + 1L, Long.MAX_VALUE - 1L)

        assertEquals(
            ride,
            clampFavoriteRange(HistoryRange(Long.MIN_VALUE, Long.MAX_VALUE), ride),
        )
    }
}
