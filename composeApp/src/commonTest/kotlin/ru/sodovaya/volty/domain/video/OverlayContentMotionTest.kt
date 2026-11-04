package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverlayContentMotionTest {
    @Test fun easesByFifteenPercentOnEachNewFrameAndIsIdempotentWithinFrame() {
        val motion = OverlayContentMotion()
        motion.beginFrame("vehicle", 0)
        assertEquals(100f, motion.position("speed", 100f))

        motion.beginFrame("vehicle", 16)
        assertEquals(115f, motion.position("speed", 200f))
        assertEquals(115f, motion.position("speed", 200f))
        assertEquals(115f, motion.position("speed", 300f))
        motion.beginFrame("vehicle", 16)
        assertEquals(115f, motion.position("speed", 200f), "a second draw in one display frame must not advance again")

        motion.beginFrame("vehicle", 32)
        assertEquals(127.75f, motion.position("speed", 200f))
    }

    @Test fun widgetsEaseIndependentlyAndDoNotOvershootWhenTargetsReverse() {
        val motion = OverlayContentMotion()
        motion.beginFrame("vehicle", 0)
        assertEquals(0f, motion.position("left", 0f))
        assertEquals(100f, motion.position("right", 100f))

        motion.beginFrame("vehicle", 16)
        assertEquals(15f, motion.position("left", 100f))
        assertEquals(85f, motion.position("right", 0f))

        motion.beginFrame("vehicle", 32)
        assertEquals(5.25f, motion.position("left", -50f))
    }

    @Test fun aStructurallyChangedContextResetsAllWidgetPositions() {
        val motion = OverlayContentMotion()
        motion.beginFrame(listOf("preset", "geometry"), 0)
        assertEquals(100f, motion.position("speed", 100f))

        motion.beginFrame(listOf("preset", "geometry"), 16)
        assertEquals(115f, motion.position("speed", 200f))

        motion.beginFrame(listOf("preset", "different geometry"), 32)
        assertEquals(200f, motion.position("speed", 200f))
    }

    @Test fun aLongGapOrBackwardsTimeResetsTheMotion() {
        val motion = OverlayContentMotion()
        motion.beginFrame("vehicle", 100)
        assertEquals(100f, motion.position("speed", 100f))

        motion.beginFrame("vehicle", 351)
        assertEquals(200f, motion.position("speed", 200f))

        motion.beginFrame("vehicle", 350)
        assertEquals(300f, motion.position("speed", 300f))
    }

    @Test fun exactPositionsEaseWhileDrawKeySnapshotsAreQuantizedAndEventuallySettle() {
        val motion = OverlayContentMotion()
        motion.beginFrame("vehicle", 0)
        assertEquals(0f, motion.position("speed", 0f))

        motion.beginFrame("vehicle", 16)
        assertEquals(0.015, motion.position("speed", 0.1f).toDouble(), 0.000001)
        assertEquals(0f, motion.positions.getValue("speed"))

        motion.beginFrame("vehicle", 32)
        assertEquals(0.02775, motion.position("speed", 0.1f).toDouble(), 0.000001)
        assertEquals(0f, motion.positions.getValue("speed"))

        var time = 48L
        var position = 0f
        repeat(20) {
            motion.beginFrame("vehicle", time)
            position = motion.position("speed", 0.1f)
            time += 16
        }
        assertEquals(0.1f, position)
        assertEquals(0.125f, motion.positions.getValue("speed"))
    }

    @Test fun nonFiniteTargetsNeverProduceNonFinitePositions() {
        val motion = OverlayContentMotion()
        motion.beginFrame("vehicle", 0)
        assertTrue(motion.position("speed", Float.NaN).isFinite())

        motion.beginFrame("vehicle", 16)
        assertTrue(motion.position("speed", Float.POSITIVE_INFINITY).isFinite())
    }
}
