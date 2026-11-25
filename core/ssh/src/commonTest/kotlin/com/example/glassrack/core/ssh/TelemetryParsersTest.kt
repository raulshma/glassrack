package com.example.glassrack.core.ssh

import com.example.glassrack.core.model.ContainerState
import com.example.glassrack.core.model.SystemdActiveState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelemetryParsersTest {
    @Test
    fun parsesCapabilitiesIncludingRaspberryPiAndDockerCompose() {
        val raw = """
            ===UNAME===
            Linux 6.6.51+rpt-rpi-2712 aarch64
            ===OS===
            PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"
            ===MODEL===
            Raspberry Pi 5 Model B Rev 1.0
            ===BINS===
            docker=1
            podman=0
            pct=0
            systemctl=1
            smartctl=0
            zpool=0
            nvidia-smi=0
            vcgencmd=1
            docker_compose=1
            sudo=1
        """.trimIndent()
        val caps = TelemetryParsers.parseCapabilities(raw)
        assertTrue(caps.isRaspberryPi)
        assertTrue(caps.hasDocker)
        assertTrue(caps.hasDockerCompose)
        assertTrue(caps.hasVcgencmd)
        assertEquals("aarch64", caps.arch)
    }

    @Test
    fun parsesCompositeSnapshotWithCpuDeltaAndRpiThrottling() {
        val cache = TelemetrySampleCache()
        val sample1 = """
            ===CPU===
            cpu  100 0 100 800 0 0 0 0
            ===MEM===
            MemTotal:        8000000 kB
            MemAvailable:    4000000 kB
            ===LOAD===
            0.52 0.44 0.39 1/312 1420
            ===UPTIME===
            3600.5
            ===TEMP===
            52400
            ===RPI===
            temp=52.4'C
            throttled=0x50005
            2400000
            Raspberry Pi 5 Model B Rev 1.0
            ===DISK===
            /dev/nvme0n1p2 256000000000 64000000000 192000000000 25% /
            ===NET===
            eth0: 1000 10 0 0 0 0 0 0 500 5 0 0 0 0 0 0
            ===CONTAINERS_COUNT===
            running
            running
            exited
        """.trimIndent()
        val sample2 = sample1
            .replace("cpu  100 0 100 800 0 0 0 0", "cpu  150 0 150 900 0 0 0 0")
            .replace("eth0: 1000", "eth0: 11000")

        TelemetryParsers.parseCompositeSnapshot("rpi", 1000L, 4, "10.0.1.53", sample1, cache, emptyList())
        val snap = TelemetryParsers.parseCompositeSnapshot("rpi", 3000L, 5, "10.0.1.53", sample2, cache, emptyList())

        assertEquals(50f, snap.cpuPct, 0.5f)
        assertEquals(50f, snap.memPct, 0.5f)
        assertEquals(2, snap.runningContainers)
        assertEquals(3, snap.totalContainers)
        assertNotNull(snap.rpiStats)
        assertEquals(2400, snap.rpiStats!!.cpuFreqMhz)
        assertTrue(snap.rpiStats!!.underVoltageDetected)
        assertTrue(snap.rpiStats!!.currentlyThrottled)
        assertEquals(5000L, snap.netRxBps)
    }

    @Test
    fun groupsDockerComposeStacksCleanly() {
        val raw = """
            ===PS===
            id1|jellyfin|linuxserver/jellyfin|running|Up 4d|media-stack|jellyfin|/srv/media|8096->8096/tcp
            id2|immich|ghcr.io/immich|exited|Exited (0)|media-stack|immich|/srv/media|
            id3|watchtower|containrrr/watchtower|running|Up 4d||||
            ===STATS===
            jellyfin|12.4%|1.5GiB / 32GiB
        """.trimIndent()
        val ctrs = TelemetryParsers.parseContainers(raw)
        val (stacks, standalone) = TelemetryParsers.groupComposeStacks(ctrs)
        assertEquals(1, stacks.size)
        assertEquals("media-stack", stacks[0].projectName)
        assertEquals(1, stacks[0].runningCount)
        assertEquals(2, stacks[0].totalCount)
        assertEquals(1, standalone.size)
        assertEquals(ContainerState.RUNNING, standalone[0].state)
        assertEquals(12.4f, ctrs[0].cpuPct, 0.1f)
    }

    @Test
    fun parsesSystemdAndProcesses() {
        val sysRaw = """
            ===UNITS===
            docker.service loaded active running Docker Engine
            wg-quick@wg0.service loaded failed failed WireGuard Tunnel
            ===CRON===
            0 3 * * 0 /usr/local/bin/backup.sh
        """.trimIndent()
        val (units, crons) = TelemetryParsers.parseSystemdAndCron(sysRaw)
        assertEquals(2, units.size)
        assertEquals(SystemdActiveState.FAILED, units[1].activeState)
        assertEquals(1, crons.size)

        val procRaw = """
            PID USER %CPU %MEM RSS STAT ELAPSED COMMAND
            1420 root 14.2 3.1 240000 Ssl 14-02:10 /usr/bin/dockerd
        """.trimIndent()
        val procs = TelemetryParsers.parseProcesses(procRaw)
        assertEquals(1, procs.size)
        assertEquals(1420, procs[0].pid)
    }
}
