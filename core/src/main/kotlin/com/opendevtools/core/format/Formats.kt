package com.opendevtools.core.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Human-readable renderings shared by both tool windows. */
object Formats {

    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    private val DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    private val UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")

    fun bytes(value: Long): String {
        if (value <= 0) return "0 B"
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024 && unit < UNITS.lastIndex) {
            size /= 1024
            unit++
        }
        return if (unit == 0) "$value B" else String.format(Locale.ROOT, "%.1f %s", size, UNITS[unit])
    }

    /** [value] is a fraction in 0..1, the shape every Proxmox usage field uses. */
    fun percent(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * 100)

    fun ratio(used: Long, total: Long): String {
        if (total <= 0) return bytes(used)
        return bytes(used) + " / " + bytes(total) + " (" + percent(used.toDouble() / total) + ")"
    }

    fun uptime(seconds: Long): String {
        if (seconds <= 0) return "-"
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m ${seconds % 60}s"
        }
    }

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> uptime(seconds)
    }

    fun timestamp(epochSeconds: Long): String =
        if (epochSeconds <= 0) "-" else TIMESTAMP.format(Instant.ofEpochSecond(epochSeconds))

    /** Date only, used where rows are grouped by day. */
    fun date(epochSeconds: Long): String =
        if (epochSeconds <= 0) "-" else DATE.format(Instant.ofEpochSecond(epochSeconds))

    /** ISO-8601 as sent by the Bitwarden API, rendered in the local time zone. */
    fun isoTimestamp(value: String?): String {
        if (value.isNullOrBlank()) return "-"
        val instant = runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { Instant.parse(value + "Z") }.getOrNull()
            ?: return value
        return TIMESTAMP.format(instant)
    }

    fun relative(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val delta = (now - epochMillis) / 1000
        return when {
            delta < 5 -> "just now"
            delta < 60 -> "${delta}s ago"
            delta < 3_600 -> "${delta / 60}m ago"
            delta < 86_400 -> "${delta / 3_600}h ago"
            else -> "${delta / 86_400}d ago"
        }
    }
}
