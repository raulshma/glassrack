package com.raulshma.grack

import com.raulshma.grack.core.model.Formatters
import com.raulshma.grack.core.model.StorageUnit
import com.raulshma.grack.core.model.TemperatureUnit
import com.raulshma.grack.core.model.ThemeVariant
import com.raulshma.grack.core.model.UserPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedCommonTest {

    @Test
    fun userPreferencesDefaultsFollowSystemExpressiveTheme() {
        val prefs = UserPreferences()
        assertEquals(ThemeVariant.DYNAMIC_SYSTEM, prefs.themeVariant)
        assertNull(prefs.darkModeOverride)
        assertEquals(0xFF006B7D, prefs.seedColorArgb)
    }

    @Test
    fun formattersRenderMetricsAccurately() {
        assertEquals("1 GiB", Formatters.formatBytes(1024L * 1024L * 1024L, StorageUnit.BINARY_GIB))
        assertEquals("1 GB", Formatters.formatBytes(1_000_000_000L, StorageUnit.SI_GB))
        assertEquals("54°C", Formatters.formatTemperature(54.2f, TemperatureUnit.CELSIUS))
        assertEquals("1d 2h", Formatters.formatUptime(86_400L + 7_200L))
    }
}
