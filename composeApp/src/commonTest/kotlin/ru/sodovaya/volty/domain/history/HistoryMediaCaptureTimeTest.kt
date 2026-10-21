package ru.sodovaya.volty.domain.history

import kotlin.test.*

class HistoryMediaCaptureTimeTest {
    @Test fun knownOffsetMapsCaptureToUtcWithoutUsingCreationOrCurrentClock() {
        assertEquals(1000L, historyMediaCaptureTime("1970:01:01 05:00:01", "+05:00", null))
        assertEquals(1000L, historyMediaCaptureTime("1969:12:31 22:30:01", "-01:30", null))
    }
    @Test fun missingOrBlankOffsetDoesNotInventTime() {
        for (offset in listOf(null, "", "  :  ")) assertNull(historyMediaCaptureTime("2026:10:21 12:00:00", offset, null))
    }
    @Test fun invalidDateDoesNotNormalizeIntoAnotherDay() {
        for (date in listOf(null, "2026:02:30 12:00:00", "2026:13:01 12:00:00", "2026:10:21 24:00:00", "2026-10-21 12:00:00"))
            assertNull(historyMediaCaptureTime(date, "+05:00", null))
    }
    @Test fun malformedOffsetIsUnknownRatherThanZero() {
        for (offset in listOf("UTC", "Z", "+99:00", "05:00", "+05:99"))
            assertNull(historyMediaCaptureTime("2026:10:21 12:00:00", offset, null))
    }
    @Test fun SubsecondsAreRetainedAtMillisecondPrecision() {
        assertEquals(1123L, historyMediaCaptureTime("1970:01:01 00:00:01", "+00:00", "123456"))
        assertEquals(1100L, historyMediaCaptureTime("1970:01:01 00:00:01", "+00:00", "1"))
    }
    @Test fun invalidSubsecondsDoNotBecomeAFabricatedExactSecond() {
        for (fraction in listOf("", "abc", "-1", "1234567890"))
            assertNull(historyMediaCaptureTime("1970:01:01 00:00:01", "+00:00", fraction))
    }
}
