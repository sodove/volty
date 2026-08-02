package ru.sodovaya.volty.presentation.common

import kotlin.test.Test
import kotlin.test.assertEquals

class IconKeyTest {

    @Test
    fun `unicycle and legacy wheel keys use the wheel avatar`() {
        assertEquals("🛞", iconKeyToEmoji("unicycle"))
        assertEquals("🛞", iconKeyToEmoji("wheel"))
    }
}
