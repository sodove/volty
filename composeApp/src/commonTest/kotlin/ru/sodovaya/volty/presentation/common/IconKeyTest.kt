package ru.sodovaya.volty.presentation.common

import kotlin.test.Test
import kotlin.test.assertEquals

class IconKeyTest {

    @Test
    fun `unicycle key uses the wheel avatar`() {
        assertEquals("🛞", iconKeyToEmoji("unicycle"))
    }

    @Test
    fun `legacy wheel key uses the wheel avatar`() {
        assertEquals("🛞", iconKeyToEmoji("wheel"))
    }
}
