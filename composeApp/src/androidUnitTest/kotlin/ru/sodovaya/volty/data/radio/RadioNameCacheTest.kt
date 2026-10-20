package ru.sodovaya.volty.data.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioNameCacheTest {
    @Test
    fun missBeforeAnythingIsCached() {
        assertNull(RadioNameCache().get("c", 10))
    }

    @Test
    fun hitOnSameDayMissOnAnotherDay() {
        val cache = RadioNameCache()
        cache.put("c", 10, setOf("a", "b"))
        assertEquals(setOf("a", "b"), cache.get("c", 10))
        assertNull(cache.get("c", 11), "UTC midnight changes the names")
        assertNull(cache.get("other", 10))
    }
}
