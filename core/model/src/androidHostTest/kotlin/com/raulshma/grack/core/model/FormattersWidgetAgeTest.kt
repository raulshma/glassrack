package com.raulshma.grack.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** The "Updated Xm ago" lines the widgets render under their data. */
class FormattersWidgetAgeTest {

    private val now = 1_800_000_000_000L

    @Test
    fun nullTimestampReportsNoData() {
        assertEquals("No data yet", Formatters.formatWidgetAge(null, now))
    }

    @Test
    fun subMinuteReadsAsJustNow() {
        assertEquals("Updated just now", Formatters.formatWidgetAge(now - 5_000L, now))
        assertEquals("Updated just now", Formatters.formatWidgetAge(now, now))
    }

    @Test
    fun minutesAndHoursAndDays() {
        assertEquals("Updated 1m ago", Formatters.formatWidgetAge(now - 60_000L, now))
        assertEquals("Updated 5m ago", Formatters.formatWidgetAge(now - 5 * 60_000L, now))
        assertEquals("Updated 59m ago", Formatters.formatWidgetAge(now - 59 * 60_000L, now))
        assertEquals("Updated 1h ago", Formatters.formatWidgetAge(now - 60 * 60_000L, now))
        assertEquals("Updated 2h ago", Formatters.formatWidgetAge(now - 130 * 60_000L, now))
        assertEquals("Updated 3d ago", Formatters.formatWidgetAge(now - 3 * 1_440L * 60_000L, now))
    }

    @Test
    fun futureTimestampsDoNotGoNegative() {
        assertEquals("Updated just now", Formatters.formatWidgetAge(now + 90_000L, now))
    }
}
