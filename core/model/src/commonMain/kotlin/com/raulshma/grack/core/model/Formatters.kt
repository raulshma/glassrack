package com.raulshma.grack.core.model

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

object Formatters {
    fun formatBytes(bytes: Long, unit: StorageUnit = StorageUnit.BINARY_GIB): String {
        if (bytes <= 0L) return if (unit == StorageUnit.BINARY_GIB) "0 B" else "0 B"
        val base = if (unit == StorageUnit.BINARY_GIB) 1024.0 else 1000.0
        val suffixes = if (unit == StorageUnit.BINARY_GIB) {
            listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
        } else {
            listOf("B", "KB", "MB", "GB", "TB", "PB")
        }
        var value = bytes.toDouble()
        var idx = 0
        while (value >= base && idx < suffixes.lastIndex) {
            value /= base
            idx++
        }
        val rounded = (value * 10.0).roundToInt() / 10.0
        val text = if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
        return "$text ${suffixes[idx]}"
    }

    fun formatBitrate(bytesPerSec: Long): String {
        val bitsPerSec = bytesPerSec.coerceAtLeast(0L) * 8.0
        return when {
            bitsPerSec >= 1_000_000_000.0 -> "${((bitsPerSec / 1_000_000_000.0) * 10.0).roundToInt() / 10.0} Gbps"
            bitsPerSec >= 1_000_000.0 -> "${((bitsPerSec / 1_000_000.0) * 10.0).roundToInt() / 10.0} Mbps"
            bitsPerSec >= 1_000.0 -> "${((bitsPerSec / 1_000.0) * 10.0).roundToInt() / 10.0} Kbps"
            else -> "${bitsPerSec.roundToInt()} bps"
        }
    }

    fun formatTemperature(tempC: Float?, unit: TemperatureUnit): String {
        if (tempC == null) return "—"
        return when (unit) {
            TemperatureUnit.CELSIUS -> "${tempC.roundToInt()}°C"
            TemperatureUnit.FAHRENHEIT -> "${((tempC * 9f / 5f) + 32f).roundToInt()}°F"
        }
    }

    fun formatUptime(uptimeSec: Long): String {
        if (uptimeSec <= 0L) return "0m"
        val days = uptimeSec / 86_400
        val hours = (uptimeSec % 86_400) / 3_600
        val mins = (uptimeSec % 3_600) / 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0 || days > 0) append("${hours}h")
            if (days == 0L && hours == 0L) append("${mins}m")
        }.trim()
    }

    fun formatPercent(pct: Float): String = "${pct.coerceIn(0f, 100f).roundToInt()}%"

    fun formatDuration(durationMs: Long): String {
        return when {
            durationMs < 1_000L -> "${durationMs}ms"
            durationMs < 60_000L -> "${((durationMs / 100.0).roundToInt() / 10.0)}s"
            else -> "${durationMs / 60_000}m ${(durationMs % 60_000) / 1_000}s"
        }
    }

    /** Formats an epoch millisecond timestamp in the local timezone as `MMM dd, HH:mm:ss`. */
    fun formatTimestamp(epochMs: Long): String {
        val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
        val month = dt.month.name.take(3).lowercase().replaceFirstChar { it.uppercaseChar() }
        val day = dt.day.toString().padStart(2, '0')
        val hh = dt.hour.toString().padStart(2, '0')
        val mm = dt.minute.toString().padStart(2, '0')
        val ss = dt.second.toString().padStart(2, '0')
        return "$month $day, $hh:$mm:$ss"
    }
}
