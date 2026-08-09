package ru.sodovaya.volty.presentation.common

import ru.sodovaya.volty.domain.model.BmsData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BmsMetricMapperTest {
    @Test
    fun `missing pack current and power are absent while measured zero remains visible`() {
        val unavailable = BmsData(
            current = 37f,
            hasCurrent = false,
            power = 900f,
            hasPower = false,
            isConnected = true
        )
        assertNull(BmsMetricMapper.currentValue(unavailable))
        assertNull(BmsMetricMapper.powerValue(unavailable))

        val balanced = unavailable.copy(current = 0f, hasCurrent = true, power = 0f, hasPower = true)
        assertEquals("+0.0", BmsMetricMapper.currentValue(balanced))
        assertEquals("0", BmsMetricMapper.powerValue(balanced))
    }
}
