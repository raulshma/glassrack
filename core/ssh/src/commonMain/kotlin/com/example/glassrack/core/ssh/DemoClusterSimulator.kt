package com.example.glassrack.core.ssh

import com.example.glassrack.core.model.ContainerAction
import com.example.glassrack.core.model.ContainerInfo
import com.example.glassrack.core.model.ContainerRuntime
import com.example.glassrack.core.model.ContainerState
import com.example.glassrack.core.model.CronEntry
import com.example.glassrack.core.model.CustomProbeResult
import com.example.glassrack.core.model.DiskMount
import com.example.glassrack.core.model.GpuStats
import com.example.glassrack.core.model.MetricSnapshot
import com.example.glassrack.core.model.NetworkInterfaceInfo
import com.example.glassrack.core.model.NetworkPortInfo
import com.example.glassrack.core.model.ProcessInfo
import com.example.glassrack.core.model.ProcessSignal
import com.example.glassrack.core.model.RaspberryPiStats
import com.example.glassrack.core.model.RemoteFileEntry
import com.example.glassrack.core.model.Server
import com.example.glassrack.core.model.SystemdActiveState
import com.example.glassrack.core.model.SystemdUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sin

class DemoClusterSimulator {
    private var tickCounter = 0

    private val containersByServer = mutableMapOf(
        "demo-rpi-core" to mutableListOf(
            ContainerInfo(
                id = "c1a2b3c4d5e6",
                shortId = "c1a2b3c4d5e6",
                name = "pihole",
                image = "pihole/pihole:2026.10.0",
                runtime = ContainerRuntime.DOCKER,
                state = ContainerState.RUNNING,
                statusText = "Up 42 days (healthy)",
                composeProject = "infra-stack",
                composeService = "pihole",
                composeWorkingDir = "/opt/stacks/infra",
                portsSummary = "53->53/udp, 8080->80/tcp",
                cpuPct = 2.4f,
                memUsedBytes = 148L * 1024 * 1024,
                memLimitBytes = 8L * 1024 * 1024 * 1024,
                isCritical = true,
            ),
            ContainerInfo(
                id = "c2a2b3c4d5e7",
                shortId = "c2a2b3c4d5e7",
                name = "caddy",
                image = "caddy:2.9-alpine",
                runtime = ContainerRuntime.DOCKER,
                state = ContainerState.RUNNING,
                statusText = "Up 42 days",
                composeProject = "infra-stack",
                composeService = "caddy",
                composeWorkingDir = "/opt/stacks/infra",
                portsSummary = "80->80/tcp, 443->443/tcp",
                cpuPct = 0.6f,
                memUsedBytes = 42L * 1024 * 1024,
                memLimitBytes = 8L * 1024 * 1024 * 1024,
                isCritical = true,
            ),
            ContainerInfo(
                id = "c3a2b3c4d5e8",
                shortId = "c3a2b3c4d5e8",
                name = "home-assistant",
                image = "ghcr.io/home-assistant/home-assistant:stable",
                runtime = ContainerRuntime.DOCKER,
                state = ContainerState.RUNNING,
                statusText = "Up 18 days",
                composeProject = "home-stack",
                composeService = "homeassistant",
                composeWorkingDir = "/opt/stacks/home",
                portsSummary = "8123->8123/tcp",
                cpuPct = 6.8f,
                memUsedBytes = 612L * 1024 * 1024,
                memLimitBytes = 8L * 1024 * 1024 * 1024,
            ),
        ),
        "demo-media-compose" to mutableListOf(
            ContainerInfo(
                id = "m1a2b3c4d5e1",
                shortId = "m1a2b3c4d5e1",
                name = "jellyfin",
                image = "lscr.io/linuxserver/jellyfin:latest",
                runtime = ContainerRuntime.DOCKER,
                state = ContainerState.RUNNING,
                statusText = "Up 14 days",
                composeProject = "media-stack",
                composeService = "jellyfin",
                composeWorkingDir = "/srv/compose/media",
                portsSummary = "8096->8096/tcp",
                cpuPct = 8.4f,
                memUsedBytes = 1480L * 1024 * 1024,
                memLimitBytes = 32L * 1024 * 1024 * 1024,
                updateAvailable = true,
            ),
            ContainerInfo(
                id = "m2a2b3c4d5e2",
                shortId = "m2a2b3c4d5e2",
                name = "immich-server",
                image = "ghcr.io/immich-app/immich-server:release",
                runtime = ContainerRuntime.DOCKER,
                state = ContainerState.RUNNING,
                statusText = "Up 9 days (healthy)",
                composeProject = "media-stack",
                composeService = "immich",
                composeWorkingDir = "/srv/compose/media",
                portsSummary = "2283->2283/tcp",
                cpuPct = 4.9f,
                memUsedBytes = 1120L * 1024 * 1024,
                memLimitBytes = 32L * 1024 * 1024 * 1024,
            ),
            ContainerInfo(
                id = "m3a2b3c4d5e3",
                shortId = "m3a2b3c4d5e3",
                name = "paperless-ngx",
                image = "ghcr.io/paperless-ngx/paperless-ngx:latest",
                runtime = ContainerRuntime.DOCKER,
                state = ContainerState.EXITED,
                statusText = "Exited (0) 2 hours ago",
                composeProject = "docs-stack",
                composeService = "paperless",
                composeWorkingDir = "/srv/compose/docs",
                portsSummary = "8000->8000/tcp",
                cpuPct = 0f,
                memUsedBytes = 0L,
                memLimitBytes = 32L * 1024 * 1024 * 1024,
            ),
            ContainerInfo(
                id = "m4a2b3c4d5e4",
                shortId = "m4a2b3c4d5e4",
                name = "sonarr",
                image = "lscr.io/linuxserver/sonarr:latest",
                runtime = ContainerRuntime.DOCKER,
                state = ContainerState.RUNNING,
                statusText = "Up 14 days",
                composeProject = "media-stack",
                composeService = "sonarr",
                composeWorkingDir = "/srv/compose/media",
                portsSummary = "8989->8989/tcp",
                cpuPct = 1.8f,
                memUsedBytes = 340L * 1024 * 1024,
                memLimitBytes = 32L * 1024 * 1024 * 1024,
            ),
        ),
        "demo-rpi-edge" to mutableListOf(
            ContainerInfo(
                id = "e1a2b3c4d5e1",
                shortId = "e1a2b3c4d5e1",
                name = "zigbee2mqtt",
                image = "koenkk/zigbee2mqtt:latest",
                runtime = ContainerRuntime.DOCKER,
                state = ContainerState.RUNNING,
                statusText = "Up 31 days",
                composeProject = "iot-stack",
                composeService = "zigbee2mqtt",
                composeWorkingDir = "/home/pi/iot",
                portsSummary = "8081->8080/tcp",
                cpuPct = 3.1f,
                memUsedBytes = 190L * 1024 * 1024,
                memLimitBytes = 4L * 1024 * 1024 * 1024,
            ),
            ContainerInfo(
                id = "e2a2b3c4d5e2",
                shortId = "e2a2b3c4d5e2",
                name = "mosquitto",
                image = "eclipse-mosquitto:2",
                runtime = ContainerRuntime.DOCKER,
                state = ContainerState.RUNNING,
                statusText = "Up 31 days",
                composeProject = "iot-stack",
                composeService = "mqtt",
                composeWorkingDir = "/home/pi/iot",
                portsSummary = "1883->1883/tcp",
                cpuPct = 0.4f,
                memUsedBytes = 24L * 1024 * 1024,
                memLimitBytes = 4L * 1024 * 1024 * 1024,
                isCritical = true,
            ),
        ),
    )

    private val systemdUnitsByServer = mutableMapOf(
        "demo-rpi-core" to mutableListOf(
            SystemdUnit("docker.service", "loaded", SystemdActiveState.ACTIVE, "running", "Docker Application Container Engine", "enabled"),
            SystemdUnit("ssh.service", "loaded", SystemdActiveState.ACTIVE, "running", "OpenBSD Secure Shell server", "enabled"),
            SystemdUnit("tailscaled.service", "loaded", SystemdActiveState.ACTIVE, "running", "Tailscale node agent", "enabled"),
            SystemdUnit("unattended-upgrades.service", "loaded", SystemdActiveState.INACTIVE, "dead", "Unattended Upgrades Shutdown", "enabled"),
        ),
        "demo-media-compose" to mutableListOf(
            SystemdUnit("docker.service", "loaded", SystemdActiveState.ACTIVE, "running", "Docker Application Container Engine", "enabled"),
            SystemdUnit("smbd.service", "loaded", SystemdActiveState.ACTIVE, "running", "Samba SMB Daemon", "enabled"),
            SystemdUnit("smartd.service", "loaded", SystemdActiveState.ACTIVE, "running", "Self Monitoring and Reporting Technology Daemon", "enabled"),
            SystemdUnit("wg-quick@wg0.service", "loaded", SystemdActiveState.FAILED, "failed", "WireGuard via wg-quick(8) for wg0", "enabled"),
        ),
        "demo-rpi-edge" to mutableListOf(
            SystemdUnit("docker.service", "loaded", SystemdActiveState.ACTIVE, "running", "Docker Application Container Engine", "enabled"),
            SystemdUnit("bluetooth.service", "loaded", SystemdActiveState.ACTIVE, "running", "Bluetooth service", "enabled"),
        ),
    )

    private val filesContent = mutableMapOf(
        "/opt/stacks/infra/docker-compose.yml" to """
            services:
              pihole:
                image: pihole/pihole:2026.10.0
                container_name: pihole
                restart: unless-stopped
                ports:
                  - "53:53/tcp"
                  - "53:53/udp"
                  - "8080:80/tcp"
              caddy:
                image: caddy:2.9-alpine
                container_name: caddy
                restart: unless-stopped
                ports:
                  - "80:80"
                  - "443:443"
        """.trimIndent(),
        "/srv/compose/media/docker-compose.yml" to """
            services:
              jellyfin:
                image: lscr.io/linuxserver/jellyfin:latest
                container_name: jellyfin
                restart: unless-stopped
                ports:
                  - "8096:8096"
              immich:
                image: ghcr.io/immich-app/immich-server:release
                container_name: immich-server
                restart: unless-stopped
              sonarr:
                image: lscr.io/linuxserver/sonarr:latest
                container_name: sonarr
                restart: unless-stopped
        """.trimIndent(),
    )

    fun sampleSnapshot(server: Server, nowEpochMs: Long): MetricSnapshot {
        tickCounter++
        val wave = sin(tickCounter * 0.45 + server.sortOrder * 1.3).toFloat()
        val containers = containersByServer[server.id].orEmpty()
        val running = containers.count { it.state == ContainerState.RUNNING }
        return when (server.id) {
            "demo-rpi-core" -> MetricSnapshot(
                serverId = server.id,
                timestampEpochMs = nowEpochMs,
                cpuPct = (22f + wave * 7f).coerceIn(5f, 95f),
                perCorePct = listOf(24f + wave * 6f, 18f - wave * 4f, 27f + wave * 5f, 19f + wave * 3f),
                memUsedBytes = 3_840_000_000L + (wave * 180_000_000L).toLong(),
                memTotalBytes = 8_589_934_592L,
                load1m = 0.68f + wave * 0.14f,
                load5m = 0.72f,
                load15m = 0.65f,
                uptimeSec = 42L * 86_400L + 6L * 3_600L + tickCounter * 5L,
                tempC = 51.4f + wave * 2.1f,
                netRxBps = 2_400_000L + (wave * 650_000L).toLong(),
                netTxBps = 920_000L + (wave * 240_000L).toLong(),
                latencyMs = 4 + (tickCounter % 3),
                activeEndpoint = server.primaryHost,
                runningContainers = running,
                totalContainers = containers.size,
                disks = listOf(
                    DiskMount("/dev/nvme0n1p2", "/", 68L * 1024 * 1024 * 1024, 256L * 1024 * 1024 * 1024, 27),
                    DiskMount("/dev/nvme0n1p1", "/boot/firmware", 120L * 1024 * 1024, 512L * 1024 * 1024, 23),
                ),
                rpiStats = RaspberryPiStats(
                    boardModel = "Raspberry Pi 5 Model B Rev 1.0",
                    socTempC = (51.4 + wave * 2.1),
                    cpuFreqMhz = 2400,
                    throttledRaw = "0x0",
                    fanRpm = 2150 + (wave * 120).toInt(),
                ),
                customResults = server.customProbes.map {
                    CustomProbeResult(it.id, it.label, it.command, "18.4% blocked (24,810 DNS queries)", 0)
                },
            )
            "demo-media-compose" -> MetricSnapshot(
                serverId = server.id,
                timestampEpochMs = nowEpochMs,
                cpuPct = (36f + wave * 11f).coerceIn(8f, 95f),
                perCorePct = listOf(41f + wave * 8f, 34f - wave * 5f, 39f + wave * 6f, 31f + wave * 4f),
                memUsedBytes = 19_400_000_000L + (wave * 420_000_000L).toLong(),
                memTotalBytes = 34_359_738_368L,
                load1m = 1.48f + wave * 0.25f,
                load5m = 1.32f,
                load15m = 1.19f,
                uptimeSec = 14L * 86_400L + 11L * 3_600L + tickCounter * 5L,
                tempC = 57.8f + wave * 2.4f,
                netRxBps = 18_500_000L + (wave * 3_200_000L).toLong(),
                netTxBps = 42_100_000L + (wave * 6_400_000L).toLong(),
                latencyMs = 9 + (tickCounter % 4),
                activeEndpoint = server.primaryHost,
                runningContainers = running,
                totalContainers = containers.size,
                disks = listOf(
                    DiskMount("/dev/sda2", "/", 94L * 1024 * 1024 * 1024, 500L * 1024 * 1024 * 1024, 19),
                    DiskMount("/dev/md0", "/mnt/media", 11_400L * 1024 * 1024 * 1024, 16_000L * 1024 * 1024 * 1024, 71),
                ),
                gpus = listOf(
                    GpuStats("NVIDIA RTX A2000", (28 + wave * 9).toInt().coerceIn(5, 95), 2480, 6144, 54, 24.6),
                ),
            )
            else -> MetricSnapshot(
                serverId = server.id,
                timestampEpochMs = nowEpochMs,
                cpuPct = (14f + wave * 4f).coerceIn(3f, 80f),
                perCorePct = listOf(16f + wave * 3f, 12f - wave * 2f, 15f + wave * 2f, 13f),
                memUsedBytes = 1_450_000_000L + (wave * 64_000_000L).toLong(),
                memTotalBytes = 4_294_967_296L,
                load1m = 0.34f + wave * 0.08f,
                load5m = 0.31f,
                load15m = 0.29f,
                uptimeSec = 89L * 86_400L + 4L * 3_600L + tickCounter * 5L,
                tempC = 46.2f + wave * 1.4f,
                netRxBps = 410_000L + (wave * 80_000L).toLong(),
                netTxBps = 280_000L + (wave * 60_000L).toLong(),
                latencyMs = 7 + (tickCounter % 3),
                activeEndpoint = server.primaryHost,
                runningContainers = running,
                totalContainers = containers.size,
                disks = listOf(
                    DiskMount("/dev/mmcblk0p2", "/", 18L * 1024 * 1024 * 1024, 64L * 1024 * 1024 * 1024, 28),
                ),
                rpiStats = RaspberryPiStats(
                    boardModel = "Raspberry Pi 4 Model B Rev 1.5",
                    socTempC = 46.2 + wave * 1.4,
                    cpuFreqMhz = 1500,
                    throttledRaw = "0x0",
                ),
            )
        }
    }

    fun getContainers(serverId: String): List<ContainerInfo> =
        containersByServer[serverId]?.toList().orEmpty()

    fun mutateContainer(serverId: String, containerIdOrName: String, action: ContainerAction): SshExecResult {
        val list = containersByServer[serverId] ?: return SshExecResult(1, "", "Server not found", 120)
        val idx = list.indexOfFirst { it.id == containerIdOrName || it.name == containerIdOrName }
        if (idx < 0) return SshExecResult(1, "", "No such container: $containerIdOrName", 140)
        val item = list[idx]
        val updated = when (action) {
            ContainerAction.START -> item.copy(state = ContainerState.RUNNING, statusText = "Up 1 second")
            ContainerAction.RESTART -> item.copy(state = ContainerState.RUNNING, statusText = "Up 1 second (restarted)")
            ContainerAction.STOP, ContainerAction.KILL -> item.copy(state = ContainerState.EXITED, statusText = "Exited (0) just now", cpuPct = 0f, memUsedBytes = 0L)
            ContainerAction.PAUSE -> item.copy(state = ContainerState.PAUSED, statusText = "Up 14 days (Paused)")
            ContainerAction.UNPAUSE -> item.copy(state = ContainerState.RUNNING, statusText = "Up 14 days")
            ContainerAction.PULL_RECREATE -> item.copy(state = ContainerState.RUNNING, statusText = "Up 2 seconds", updateAvailable = false)
            ContainerAction.REMOVE -> {
                list.removeAt(idx)
                return SshExecResult(0, item.name, "", 420)
            }
        }
        list[idx] = updated
        return SshExecResult(0, "${item.name}\nAction ${action.label} completed cleanly.", "", 480)
    }

    fun mutateComposeStack(serverId: String, projectName: String, up: Boolean): SshExecResult {
        val list = containersByServer[serverId] ?: return SshExecResult(1, "", "Server not found", 120)
        val updatedNames = mutableListOf<String>()
        for (i in list.indices) {
            if (list[i].composeProject == projectName) {
                val c = list[i]
                updatedNames += c.name
                list[i] = if (up) {
                    c.copy(state = ContainerState.RUNNING, statusText = "Up 2 seconds")
                } else {
                    c.copy(state = ContainerState.EXITED, statusText = "Exited (0) just now", cpuPct = 0f, memUsedBytes = 0L)
                }
            }
        }
        return SshExecResult(
            exitCode = 0,
            stdout = "Compose stack '$projectName' (${updatedNames.joinToString()}) ${if (up) "started" else "stopped"}.",
            stderr = "",
            durationMs = 950L,
        )
    }

    fun containerLogs(containerName: String): String = """
        [INFO] $containerName: starting runtime worker pool (4 threads)
        [INFO] $containerName: listening on internal bridge 172.18.0.4
        [INFO] $containerName: healthcheck probe passed (HTTP 200 in 4ms)
        [INFO] $containerName: sync checkpoint committed cleanly
    """.trimIndent()

    fun getSystemdAndCron(serverId: String): Pair<List<SystemdUnit>, List<CronEntry>> {
        val units = systemdUnitsByServer[serverId]?.toList().orEmpty()
        val crons = listOf(
            CronEntry("0 3 * * 0", "/usr/local/bin/compose-backup.sh", "root crontab"),
            CronEntry("*/15 * * * *", "/usr/local/bin/check-nvme-smart.sh", "root crontab"),
        )
        return units to crons
    }

    fun mutateSystemdUnit(serverId: String, unitName: String, verb: String): SshExecResult {
        val list = systemdUnitsByServer[serverId] ?: return SshExecResult(1, "", "No units", 120)
        val idx = list.indexOfFirst { it.name == unitName }
        if (idx >= 0) {
            val u = list[idx]
            list[idx] = when (verb) {
                "start", "restart" -> u.copy(activeState = SystemdActiveState.ACTIVE, subState = "running")
                "stop" -> u.copy(activeState = SystemdActiveState.INACTIVE, subState = "dead")
                else -> u
            }
        }
        return SshExecResult(0, "systemctl $verb $unitName completed.", "", 310L)
    }

    fun getProcesses(serverId: String): List<ProcessInfo> = listOf(
        ProcessInfo(1420, "root", 8.4f, 4.1f, 348_000, "Ssl", "14d 03:12", "/usr/bin/dockerd -H fd://"),
        ProcessInfo(2194, "media", 6.2f, 5.8f, 1_480_000, "Sl", "14d 02:55", "jellyfin --datadir /config"),
        ProcessInfo(3012, "pi", 2.1f, 1.4f, 112_000, "Ss", "42d 06:11", "pihole-FTL --foreground"),
        ProcessInfo(4188, "root", 0.9f, 0.7f, 48_000, "Ss", "02:14", "sshd: homelab@pts/0"),
        ProcessInfo(5120, "caddy", 0.5f, 0.4f, 42_000, "Ssl", "42d 06:09", "caddy run --config /etc/caddy/Caddyfile"),
    )

    fun sendProcessSignal(pid: Int, signal: ProcessSignal): SshExecResult =
        SshExecResult(0, "Sent ${signal.signalName} (${signal.code}) to PID $pid", "", 180L)

    fun getNetworkDetails(serverId: String): Pair<List<NetworkInterfaceInfo>, List<NetworkPortInfo>> {
        val ifaces = listOf(
            NetworkInterfaceInfo("eth0", "10.0.1.${if (serverId == "demo-rpi-core") 53 else 20}", "", 142_800_000_000L, 98_400_000_000L, 2_400_000L, 920_000L, true),
            NetworkInterfaceInfo("tailscale0", "100.88.14.${if (serverId == "demo-rpi-core") 5 else 9}", "", 14_200_000_000L, 11_800_000_000L, 180_000L, 95_000L, true),
            NetworkInterfaceInfo("docker0", "172.17.0.1", "", 8_100_000_000L, 6_400_000_000L, 0L, 0L, true),
        )
        val ports = listOf(
            NetworkPortInfo("tcp", "0.0.0.0", 22, "sshd"),
            NetworkPortInfo("tcp", "0.0.0.0", 443, "caddy"),
            NetworkPortInfo("tcp", "0.0.0.0", 8096, "jellyfin"),
            NetworkPortInfo("udp", "0.0.0.0", 53, "pihole-FTL"),
        )
        return ifaces to ports
    }

    fun listFiles(path: String): List<RemoteFileEntry> = when (path.trimEnd('/')) {
        "", "/" -> listOf(
            RemoteFileEntry("opt", "/opt", true, 4096, "drwxr-xr-x", 1760000000L),
            RemoteFileEntry("srv", "/srv", true, 4096, "drwxr-xr-x", 1760000000L),
            RemoteFileEntry("etc", "/etc", true, 4096, "drwxr-xr-x", 1760000000L),
        )
        "/opt", "/opt/stacks", "/opt/stacks/infra" -> listOf(
            RemoteFileEntry("docker-compose.yml", "/opt/stacks/infra/docker-compose.yml", false, 412, "-rw-r--r--", 1760000000L),
        )
        else -> listOf(
            RemoteFileEntry("docker-compose.yml", "/srv/compose/media/docker-compose.yml", false, 520, "-rw-r--r--", 1760000000L),
        )
    }

    fun readFile(path: String): String =
        filesContent[path] ?: "# Demo file preview: $path\n# Connect a real SSH node to edit live remote files.\n"

    fun writeFile(path: String, content: String) {
        filesContent["$path.bak"] = filesContent[path].orEmpty()
        filesContent[path] = content
    }

    fun openDemoShell(server: Server): SshShellSession = DemoShellSession(server)
}

private class DemoShellSession(private val server: Server) : SshShellSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _output = MutableSharedFlow<ByteArray>(replay = 8, extraBufferCapacity = 64)
    override val outputFlow: Flow<ByteArray> = _output.asSharedFlow()
    private val _connected = MutableStateFlow(true)
    override val isConnected: StateFlow<Boolean> = _connected.asStateFlow()
    private val lineBuffer = StringBuilder()

    init {
        scope.launch {
            delay(80)
            val banner = buildString {
                append("\r\n\u001B[1;36mGlassrack Demo PTY\u001B[0m • connected to \u001B[1m${server.name}\u001B[0m (${server.capabilities.distro})\r\n")
                append("Type \u001B[33mdocker ps\u001B[0m, \u001B[33mvcgencmd measure_temp\u001B[0m, \u001B[33muname -a\u001B[0m, or \u001B[33mhelp\u001B[0m.\r\n")
                append(prompt())
            }
            _output.emit(banner.encodeToByteArray())
        }
    }

    private fun prompt(): String = "\u001B[32m${server.username}@${server.name}\u001B[0m:\u001B[34m~\u001B[0m$ "

    override suspend fun sendBytes(bytes: ByteArray) {
        if (!_connected.value) return
        val text = bytes.decodeToString()
        for (ch in text) {
            when (ch) {
                '\r', '\n' -> {
                    _output.emit("\r\n".encodeToByteArray())
                    val cmd = lineBuffer.toString().trim()
                    lineBuffer.clear()
                    handleCommand(cmd)
                }
                '\u007F', '\b' -> {
                    if (lineBuffer.isNotEmpty()) {
                        lineBuffer.deleteAt(lineBuffer.length - 1)
                        _output.emit("\b \b".encodeToByteArray())
                    }
                }
                '\u0003' -> {
                    lineBuffer.clear()
                    _output.emit("^C\r\n${prompt()}".encodeToByteArray())
                }
                else -> {
                    lineBuffer.append(ch)
                    _output.emit(ch.toString().encodeToByteArray())
                }
            }
        }
    }

    private suspend fun handleCommand(cmd: String) {
        val resp = when {
            cmd.isEmpty() -> ""
            cmd == "help" -> "Simulated commands: uname -a, docker ps, vcgencmd measure_temp, free -h, uptime, clear\r\n"
            cmd == "clear" -> {
                _output.emit("\u001B[2J\u001B[H${prompt()}".encodeToByteArray())
                return
            }
            cmd.startsWith("uname") -> "Linux ${server.name} ${server.capabilities.kernel} ${server.capabilities.arch} GNU/Linux\r\n"
            cmd.startsWith("vcgencmd") -> "temp=51.4'C\r\nthrottled=0x0\r\n"
            cmd.startsWith("docker ps") -> "CONTAINER ID   IMAGE                      STATUS        NAMES\r\nc1a2b3c4d5e6   pihole/pihole:2026.10.0    Up 42 days    pihole\r\nc2a2b3c4d5e7   caddy:2.9-alpine           Up 42 days    caddy\r\n"
            cmd.startsWith("free") -> "               total        used        free      shared  buff/cache   available\r\nMem:           7.8Gi       3.6Gi       1.9Gi       128Mi       2.3Gi       4.2Gi\r\n"
            cmd.startsWith("uptime") -> " 10:05:12 up 42 days,  6:14,  1 user,  load average: 0.68, 0.72, 0.65\r\n"
            else -> "sh: simulated demo node executed '$cmd' (exit 0)\r\n"
        }
        _output.emit((resp + prompt()).encodeToByteArray())
    }

    override suspend fun resizePty(cols: Int, rows: Int) = Unit

    override suspend fun close() {
        _connected.value = false
    }
}
