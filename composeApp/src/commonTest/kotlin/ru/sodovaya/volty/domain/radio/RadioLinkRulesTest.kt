package ru.sodovaya.volty.domain.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioLinkRulesTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val someDay = 20_000L * day

    @Test
    fun todaysNameGetsTheWholeBudgetOutsideTheMidnightWindow() {
        assertEquals(listOf(30_000L), radioJoinBudgets(someDay + 6 * hour, 30_000))
        assertEquals(listOf(8_000L), radioJoinBudgets(someDay + 13 * hour, 8_000))
        assertEquals(listOf(5_000L), radioJoinBudgets(someDay + day - 1, 5_000))
    }

    @Test
    fun yesterdaysNameSharesThirtyPercentJustAfterUtcMidnight() {
        assertEquals(listOf(21_000L, 9_000L), radioJoinBudgets(someDay, 30_000))
        assertEquals(listOf(5_600L, 2_400L), radioJoinBudgets(someDay + 3 * hour, 8_000))
        assertEquals(listOf(3_500L, 1_500L), radioJoinBudgets(someDay + 6 * hour - 1, 5_000))
    }

    @Test
    fun budgetsNeverExceedTheTotal() {
        for (total in listOf(1L, 7L, 999L, 4_999L, 120_000L)) {
            assertEquals(total, radioJoinBudgets(someDay + hour, total).sum())
            assertEquals(total, radioJoinBudgets(someDay + 12 * hour, total).sum())
        }
    }

    private fun r(ssid: String, bssid: String?, atMillis: Long) = RadioScanResult(ssid, bssid, atMillis * 1000)

    @Test
    fun onlyResultsFromThisScanCount() {
        val results = listOf(
            r("DIRECT-Vy-old", "aa:bb:cc:00:00:01", atMillis = 9_999), // cached from an earlier scan
            r("DIRECT-Vy-new", "aa:bb:cc:00:00:02", atMillis = 10_000),
            r("DIRECT-Vy-new2", "aa:bb:cc:00:00:03", atMillis = 12_500),
        )
        assertEquals(
            listOf("DIRECT-Vy-new", "DIRECT-Vy-new2"),
            RadioScanFilter.fresh(results, scanStartedAtMillis = 10_000, ownBssids = emptySet()).map { it.ssid },
        )
    }

    @Test
    fun ownGroupInterfaceIsNeverAForeignHub() {
        val results = listOf(
            // Own interface: the device address with the locally administered bit set, upper case.
            r("DIRECT-Vy-x", "AA:BB:CC:00:00:01", atMillis = 20_000),
            r("DIRECT-Vy-x", "a8:bb:cc:00:00:09", atMillis = 20_000),
        )
        val own = setOf("a8:bb:cc:00:00:01")
        assertEquals(
            listOf("a8:bb:cc:00:00:09"),
            RadioScanFilter.fresh(results, 20_000, own).map { it.bssid },
        )
    }

    @Test
    fun normalizeMacClearsTheLocallyAdministeredBit() {
        assertEquals("a8:bb:cc:00:00:01", RadioScanFilter.normalizeMac("AA:BB:CC:00:00:01"))
        assertEquals("a8:bb:cc:00:00:01", RadioScanFilter.normalizeMac("a8:bb:cc:00:00:01"))
        assertEquals("00:11:22:33:44:55", RadioScanFilter.normalizeMac("02:11:22:33:44:55"))
        assertEquals("not-a-mac", RadioScanFilter.normalizeMac("NOT-A-MAC"))
    }

    @Test
    fun existingClientAttachmentToOurGroupIsReused() {
        val names = listOf("DIRECT-Vy-today", "DIRECT-Vy-yesterday")
        assertEquals("192.168.49.1", reusableRadioAttachment(true, false, "192.168.49.1", "DIRECT-Vy-yesterday", names))
        assertNull(reusableRadioAttachment(true, false, "192.168.49.1", "DIRECT-other", names), "another group")
        assertNull(reusableRadioAttachment(true, true, "192.168.49.1", "DIRECT-Vy-today", names), "we are the owner")
        assertNull(reusableRadioAttachment(false, false, "192.168.49.1", "DIRECT-Vy-today", names), "not formed")
        assertNull(reusableRadioAttachment(true, false, null, "DIRECT-Vy-today", names), "no owner address")
        assertNull(reusableRadioAttachment(true, false, "192.168.49.1", null, names), "unknown name")
    }
}
