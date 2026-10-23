package ru.sodovaya.volty.presentation.common.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsMotionTest {
    @Test
    fun targetingTheHubIsBackAndAnythingElseIsForward() {
        assertEquals(PageDirection.BACK, pageDirection(targetIsHub = true))
        assertEquals(PageDirection.FORWARD, pageDirection(targetIsHub = false))
    }

    @Test
    fun tappingAClosedSectionOpensItAndTappingTheOpenOneClosesIt() {
        assertEquals("a", toggleExpanded<String>(current = null, tapped = "a"))
        assertNull(toggleExpanded(current = "a", tapped = "a"))
    }

    @Test
    fun tappingAnotherSectionReplacesTheOpenOne() {
        assertEquals("b", toggleExpanded(current = "a", tapped = "b"))
    }
}
