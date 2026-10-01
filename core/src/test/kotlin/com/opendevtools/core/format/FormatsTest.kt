package com.opendevtools.core.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FormatsTest {

    @Test
    fun `bytes uses binary units and keeps whole bytes exact`() {
        assertEquals("0 B", Formats.bytes(0))
        assertEquals("0 B", Formats.bytes(-1))
        assertEquals("512 B", Formats.bytes(512))
        assertEquals("1.0 KiB", Formats.bytes(1024))
        assertEquals("1.5 KiB", Formats.bytes(1536))
        assertEquals("1.0 GiB", Formats.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun `percent renders a fraction, which is the shape Proxmox reports`() {
        assertEquals("0.0%", Formats.percent(0.0))
        assertEquals("25.0%", Formats.percent(0.25))
        assertEquals("100.0%", Formats.percent(1.0))
    }

    @Test
    fun `ratio omits the share when the total is unknown`() {
        assertEquals("1.0 KiB / 2.0 KiB (50.0%)", Formats.ratio(1024, 2048))
        assertEquals("1.0 KiB", Formats.ratio(1024, 0))
    }

    @Test
    fun `uptime drops to the largest two units that matter`() {
        assertEquals("-", Formats.uptime(0))
        assertEquals("5m 30s", Formats.uptime(330))
        assertEquals("2h 5m", Formats.uptime(2 * 3600 + 5 * 60))
        assertEquals("3d 4h", Formats.uptime(3 * 86_400 + 4 * 3600))
    }

    @Test
    fun `duration stays in seconds for short tasks`() {
        assertEquals("42s", Formats.duration(42))
        assertEquals("2m 3s", Formats.duration(123))
    }

    @Test
    fun `an ISO timestamp without a zone is still accepted`() {
        assertEquals("-", Formats.isoTimestamp(null))
        assertEquals("-", Formats.isoTimestamp("  "))
        // Bitwarden sometimes omits the trailing Z; the value must not come back mangled.
        val withZone = Formats.isoTimestamp("2026-01-02T03:04:05Z")
        val withoutZone = Formats.isoTimestamp("2026-01-02T03:04:05")
        assertEquals(withZone, withoutZone)
    }

    @Test
    fun `an unparseable timestamp is passed through rather than hidden`() {
        assertEquals("not a date", Formats.isoTimestamp("not a date"))
    }

    @Test
    fun `relative renders the recent past in the coarsest useful unit`() {
        val now = 1_000_000_000L
        assertEquals("just now", Formats.relative(now - 1_000, now))
        assertEquals("30s ago", Formats.relative(now - 30_000, now))
        assertEquals("5m ago", Formats.relative(now - 5 * 60_000, now))
        assertEquals("3h ago", Formats.relative(now - 3 * 3_600_000, now))
        assertEquals("2d ago", Formats.relative(now - 2 * 86_400_000L, now))
    }
}
