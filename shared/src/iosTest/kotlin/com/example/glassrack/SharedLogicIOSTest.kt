package com.example.glassrack

import com.example.glassrack.core.model.Formatters
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedLogicIOSTest {

    @Test
    fun formatPercentClampsOutOfRangeValues() {
        assertEquals("0%", Formatters.formatPercent(-5f))
        assertEquals("100%", Formatters.formatPercent(142f))
        assertEquals("75%", Formatters.formatPercent(75.4f))
    }
}
