package com.example.glassrack

import com.example.glassrack.core.model.Formatters
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedLogicAndroidHostTest {

    @Test
    fun formatBitrateAndDurationProduceExpectedUnits() {
        assertEquals("1.0 Gbps", Formatters.formatBitrate(125_000_000L))
        assertEquals("450ms", Formatters.formatDuration(450L))
        assertEquals("2m 5s", Formatters.formatDuration(125_000L))
    }
}
