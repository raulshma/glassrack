package com.raulshma.grack.core.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Item: fresh capabilities — probeVersion, isRoot and distroId are parsed and versioned. */
class CapabilitiesRefreshTest {

    @Test
    fun parsesDistroIdRootUserAndProbeVersion() {
        val caps = TelemetryParsers.parseCapabilities(
            """
            ===UNAME===
            Linux 6.1.0-18-amd64 x86_64
            ===OS===
            PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"
            ID=debian
            ===UID===
            0
            ===BINS===
            docker=1
            podman=0
            pct=0
            systemctl=1
            smartctl=0
            zpool=0
            nvidia-smi=0
            vcgencmd=0
            docker_compose=1
            sudo=1
            """.trimIndent(),
        )
        assertEquals("debian", caps.distroId)
        assertTrue(caps.isRoot, "id -u == 0 must mark root logins")
        assertTrue(caps.hasDocker)
        assertEquals(TelemetryParsers.CAPABILITIES_PROBE_VERSION, caps.probeVersion)
    }

    @Test
    fun nonRootUserWithoutOsReleaseStillParsesWithDefaults() {
        val caps = TelemetryParsers.parseCapabilities(
            """
            ===UNAME===
            Linux 6.6.51+rpt-rpi-2712 aarch64
            ===OS===
            ===UID===
            1000
            ===MODEL===
            ===BINS===
            docker=0
            sudo=0
            """.trimIndent(),
        )
        assertEquals("", caps.distroId)
        assertEquals(false, caps.isRoot)
        assertEquals(TelemetryParsers.CAPABILITIES_PROBE_VERSION, caps.probeVersion)
    }

    @Test
    fun probeScriptAsksForUidAndOsReleaseId() {
        val script = TelemetryProbes.detectCapabilitiesScript()
        assertTrue(script.contains("id -u"))
        assertTrue(script.contains("===UID==="))
        // The existing grep already includes ID= for distroId.
        assertTrue(script.contains("^(PRETTY_NAME|ID)="))
    }
}
