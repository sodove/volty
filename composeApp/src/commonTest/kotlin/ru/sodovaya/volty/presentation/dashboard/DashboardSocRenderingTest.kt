package ru.sodovaya.volty.presentation.dashboard

import ru.sodovaya.volty.domain.model.BmsData
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardSocRenderingTest {

    @Test
    fun `disconnected aggregate does not render a fabricated zero percent`() {
        assertEquals("—", dashboardSocValue(BmsData()))
    }

    @Test
    fun `connected zero state of charge remains a real zero`() {
        assertEquals("0", dashboardSocValue(BmsData(isConnected = true, soc = 0f, socKnown = true)))
    }
}
