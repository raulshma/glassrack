package com.example.glassrack.core.ssh

import com.example.glassrack.core.model.ComposeStack
import com.example.glassrack.core.model.ContainerInfo
import com.example.glassrack.core.model.ContainerRuntime
import com.example.glassrack.core.model.ContainerState
import com.example.glassrack.core.model.CronEntry
import com.example.glassrack.core.model.CustomProbeDefinition
import com.example.glassrack.core.model.CustomProbeResult
import com.example.glassrack.core.model.DiskMount
import com.example.glassrack.core.model.GpuStats
import com.example.glassrack.core.model.MetricSnapshot
import com.example.glassrack.core.model.NetworkInterfaceInfo
import com.example.glassrack.core.model.NetworkPortInfo
import com.example.glassrack.core.model.OsCapabilities
import com.example.glassrack.core.model.ProcessInfo
import com.example.glassrack.core.model.RaspberryPiStats
import com.example.glassrack.core.model.SystemdActiveState
import com.example.glassrack.core.model.SystemdUnit
import com.example.glassrack.core.model.ZfsPoolInfo
import kotlinx.collections.immutable.toImmutableList

class TelemetrySampleCache {
    private data class CpuCounters(val total: Long, val idle: Long)
    private data class NetCounters(val rxBytes: Long, val txBytes: Long, val timestampMs: Long)

    private val prevCpu = mutableMapOf<String, Map<String, CpuCounters>>()
    private val prevNet = mutableMapOf<String, NetCounters>()

    fun computeCpuDelta(serverId: String, rawCpuBlock: String): Pair<Float, List<Float>> {
        val parsed = mutableMapOf<String, CpuCounters>()
        for (line in rawCpuBlock.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5 || !parts[0].startsWith("cpu")) continue
            val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 4) continue
            val idle = nums[3] + (nums.getOrNull(4) ?: 0L)
            val total = nums.sum()
            parsed[parts[0]] = CpuCounters(total = total, idle = idle)
        }
        val previous = prevCpu.put(serverId, parsed) ?: return 0f to emptyList()
        fun pctFor(key: String): Float? {
            val cur = parsed[key] ?: return null
            val old = previous[key] ?: return null
            val dt = (cur.total - old.total).coerceAtLeast(1L)
            val di = (cur.idle - old.idle).coerceIn(0L, dt)
            return ((dt - di).toFloat() / dt.toFloat()) * 100f
        }
        val overall = pctFor("cpu") ?: 0f
        val cores = parsed.keys
            .filter { it != "cpu" && it.removePrefix("cpu").all(Char::isDigit) }
            .sortedBy { it.removePrefix("cpu").toIntOrNull() ?: 0 }
            .mapNotNull { pctFor(it) }
        return overall to cores
    }

    fun computeNetDelta(serverId: String, rawNetBlock: String, timestampMs: Long): Pair<Long, Long> {
        var rxTotal = 0L
        var txTotal = 0L
        for (line in rawNetBlock.lineSequence()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue
            val iface = line.substring(0, colonIdx).trim()
            if (iface == "lo") continue
            val cols = line.substring(colonIdx + 1).trim().split(Regex("\\s+"))
            if (cols.size >= 9) {
                rxTotal += cols[0].toLongOrNull() ?: 0L
                txTotal += cols[8].toLongOrNull() ?: 0L
            }
        }
        val prev = prevNet.put(serverId, NetCounters(rxTotal, txTotal, timestampMs)) ?: return 0L to 0L
        val dtSec = ((timestampMs - prev.timestampMs).coerceAtLeast(500L)) / 1000.0
        val rxBps = ((rxTotal - prev.rxBytes).coerceAtLeast(0L) / dtSec).toLong()
        val txBps = ((txTotal - prev.txBytes).coerceAtLeast(0L) / dtSec).toLong()
        return rxBps to txBps
    }
}

object TelemetryParsers {
    fun splitSections(output: String): Map<String, String> {
        val result = mutableMapOf<String, StringBuilder>()
        var currentKey: String? = null
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("===") && trimmed.endsWith("===") && trimmed.length > 6) {
                currentKey = trimmed.removePrefix("===").removeSuffix("===")
                result.getOrPut(currentKey) { StringBuilder() }
            } else if (currentKey != null) {
                result.getOrPut(currentKey) { StringBuilder() }.appendLine(line)
            }
        }
        return result.mapValues { it.value.toString().trim() }
    }

    fun parseCapabilities(output: String): OsCapabilities {
        val sec = splitSections(output)
        val unameParts = sec["UNAME"].orEmpty().split(Regex("\\s+"))
        val osLines = sec["OS"].orEmpty().lines()
        val pretty = osLines.firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter("=")?.trim('"') ?: ""
        val model = sec["MODEL"].orEmpty()
        val bins = sec["BINS"].orEmpty().lines()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq > 0) line.substring(0, eq).trim() to (line.substring(eq + 1).trim() == "1") else null
            }.toMap()
        val isPi = model.contains("Raspberry Pi", ignoreCase = true) ||
            pretty.contains("Raspbian", ignoreCase = true) ||
            bins["vcgencmd"] == true
        return OsCapabilities(
            osFamily = unameParts.getOrElse(0) { "Linux" },
            kernel = unameParts.getOrElse(1) { "" },
            arch = unameParts.getOrElse(2) { "" },
            distro = pretty,
            isRaspberryPi = isPi,
            hasDocker = bins["docker"] == true,
            hasDockerCompose = bins["docker_compose"] == true,
            hasPodman = bins["podman"] == true,
            hasPct = bins["pct"] == true,
            hasSystemctl = bins["systemctl"] == true,
            hasSmartctl = bins["smartctl"] == true,
            hasZpool = bins["zpool"] == true,
            hasNvidiaSmi = bins["nvidia-smi"] == true,
            hasVcgencmd = bins["vcgencmd"] == true,
            hasSudo = bins["sudo"] == true,
        )
    }

    fun parseCompositeSnapshot(
        serverId: String,
        timestampMs: Long,
        latencyMs: Int,
        activeEndpoint: String,
        output: String,
        cache: TelemetrySampleCache,
        customProbes: List<CustomProbeDefinition>,
    ): MetricSnapshot {
        val sec = splitSections(output)
        val (cpuPct, perCore) = cache.computeCpuDelta(serverId, sec["CPU"].orEmpty())

        val memMap = mutableMapOf<String, Long>()
        sec["MEM"].orEmpty().lineSequence().forEach { line ->
            val parts = line.split(':')
            if (parts.size == 2) {
                val kb = parts[1].trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
                memMap[parts[0].trim()] = kb * 1024L
            }
        }
        val memTotal = memMap["MemTotal"] ?: 0L
        val memAvail = memMap["MemAvailable"] ?: ((memMap["MemFree"] ?: 0L) + (memMap["Cached"] ?: 0L))
        val memUsed = (memTotal - memAvail).coerceAtLeast(0L)
        val swapTotal = memMap["SwapTotal"] ?: 0L
        val swapFree = memMap["SwapFree"] ?: 0L
        val swapUsed = (swapTotal - swapFree).coerceAtLeast(0L)

        val loadParts = sec["LOAD"].orEmpty().split(Regex("\\s+"))
        val uptimeSec = sec["UPTIME"].orEmpty().trim().toDoubleOrNull()?.toLong() ?: 0L

        val sysTempRaw = sec["TEMP"].orEmpty().trim().toDoubleOrNull()
        var tempC = sysTempRaw?.let { if (it > 1000.0) (it / 1000.0).toFloat() else it.toFloat() }

        val rpiStats = parseRpiStats(sec["RPI"].orEmpty())
        if (tempC == null) {
            tempC = rpiStats?.socTempC?.toFloat()
        }

        val disks = sec["DISK"].orEmpty().lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size >= 6) {
                val total = cols[1].toLongOrNull() ?: return@mapNotNull null
                val used = cols[2].toLongOrNull() ?: 0L
                val pct = cols[4].removeSuffix("%").toIntOrNull() ?: 0
                DiskMount(
                    filesystem = cols[0],
                    mountPoint = cols[5],
                    usedBytes = used,
                    totalBytes = total,
                    usedPercent = pct,
                )
            } else null
        }.toList()

        val (rxBps, txBps) = cache.computeNetDelta(serverId, sec["NET"].orEmpty(), timestampMs)

        val states = sec["CONTAINERS_COUNT"].orEmpty().lines().filter { it.isNotBlank() }
        val runningCount = states.count { it.equals("running", ignoreCase = true) }

        val gpus = sec["GPU"].orEmpty().lineSequence().mapNotNull { line ->
            val cols = line.split(',').map { it.trim() }
            if (cols.size >= 5) {
                GpuStats(
                    name = cols[0],
                    utilizationPct = cols[1].toIntOrNull() ?: 0,
                    vramUsedMb = cols[2].toIntOrNull() ?: 0,
                    vramTotalMb = cols[3].toIntOrNull() ?: 0,
                    tempC = cols[4].toIntOrNull() ?: 0,
                    powerWatts = cols.getOrNull(5)?.toDoubleOrNull(),
                )
            } else null
        }.toList()

        val zfsPools = sec["ZFS"].orEmpty().lineSequence().mapNotNull { line ->
            val cols = line.split('\t', ' ').filter { it.isNotBlank() }
            if (cols.size >= 4) {
                ZfsPoolInfo(
                    name = cols[0],
                    state = cols[1],
                    sizeBytes = cols[2].toLongOrNull() ?: 0L,
                    allocatedBytes = cols[3].toLongOrNull() ?: 0L,
                )
            } else null
        }.toList()

        val customResults = customProbes.mapNotNull { probe ->
            val raw = sec["CUSTOM_${probe.id}"] ?: return@mapNotNull null
            val lines = raw.lines()
            val exitLine = lines.lastOrNull { it.startsWith("__EXIT__=") }
            val exitCode = exitLine?.substringAfter("__EXIT__=")?.toIntOrNull() ?: 0
            val body = lines.filterNot { it.startsWith("__EXIT__=") }.joinToString("\n").trim()
            CustomProbeResult(
                id = probe.id,
                label = probe.label,
                command = probe.command,
                output = body,
                exitCode = exitCode,
            )
        }

        return MetricSnapshot(
            serverId = serverId,
            timestampEpochMs = timestampMs,
            cpuPct = cpuPct,
            perCorePct = perCore,
            memUsedBytes = memUsed,
            memTotalBytes = memTotal,
            swapUsedBytes = swapUsed,
            swapTotalBytes = swapTotal,
            load1m = loadParts.getOrNull(0)?.toFloatOrNull() ?: 0f,
            load5m = loadParts.getOrNull(1)?.toFloatOrNull() ?: 0f,
            load15m = loadParts.getOrNull(2)?.toFloatOrNull() ?: 0f,
            uptimeSec = uptimeSec,
            tempC = tempC,
            netRxBps = rxBps,
            netTxBps = txBps,
            latencyMs = latencyMs,
            activeEndpoint = activeEndpoint,
            runningContainers = runningCount,
            totalContainers = states.size,
            disks = disks,
            rpiStats = rpiStats,
            gpus = gpus,
            zfsPools = zfsPools,
            customResults = customResults,
        )
    }

    fun parseRpiStats(raw: String): RaspberryPiStats? {
        if (raw.isBlank()) return null
        var temp: Double? = null
        var throttledRaw: String? = null
        var throttledBits = 0
        var freqMhz: Int? = null
        var boardModel = ""
        for (line in raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }) {
            when {
                line.startsWith("temp=") -> {
                    temp = line.removePrefix("temp=").substringBefore("'").toDoubleOrNull()
                }
                line.startsWith("throttled=") -> {
                    throttledRaw = line.removePrefix("throttled=")
                    throttledBits = throttledRaw.removePrefix("0x").toIntOrNull(16) ?: 0
                }
                line.all(Char::isDigit) -> {
                    val khz = line.toIntOrNull()
                    if (khz != null && khz > 1000) freqMhz = khz / 1000
                }
                line.contains("Raspberry", ignoreCase = true) -> boardModel = line
            }
        }
        return RaspberryPiStats(
            boardModel = boardModel,
            socTempC = temp,
            cpuFreqMhz = freqMhz,
            throttledRaw = throttledRaw,
            underVoltageDetected = (throttledBits and 0x1) != 0,
            armFreqCapped = (throttledBits and 0x2) != 0,
            currentlyThrottled = (throttledBits and 0x4) != 0,
            softTempLimit = (throttledBits and 0x8) != 0,
            underVoltageOccurred = (throttledBits and 0x10000) != 0,
        )
    }

    fun parseContainers(output: String, runtime: ContainerRuntime = ContainerRuntime.DOCKER): List<ContainerInfo> {
        val sec = splitSections(output)
        val statsMap = mutableMapOf<String, Pair<Float, Pair<Long, Long>>>()
        sec["STATS"].orEmpty().lineSequence().forEach { line ->
            val cols = line.split('|')
            if (cols.size >= 3) {
                val cpu = cols[1].removeSuffix("%").trim().toFloatOrNull() ?: 0f
                val memParts = cols[2].split('/').map { it.trim() }
                val used = parseHumanBytes(memParts.getOrElse(0) { "0" })
                val limit = parseHumanBytes(memParts.getOrElse(1) { "0" })
                statsMap[cols[0].trim()] = cpu to (used to limit)
            }
        }
        return sec["PS"].orEmpty().lineSequence().mapNotNull { line ->
            val cols = line.split('|')
            if (cols.size < 5) return@mapNotNull null
            val id = cols[0].trim()
            val name = cols[1].trim()
            val stat = statsMap[name]
            val state = when (cols[3].trim().lowercase()) {
                "running" -> ContainerState.RUNNING
                "paused" -> ContainerState.PAUSED
                "exited" -> ContainerState.EXITED
                "restarting" -> ContainerState.RESTARTING
                "created" -> ContainerState.CREATED
                "dead" -> ContainerState.DEAD
                else -> ContainerState.UNKNOWN
            }
            ContainerInfo(
                id = id,
                shortId = id.take(12),
                name = name,
                image = cols[2].trim(),
                runtime = runtime,
                state = state,
                statusText = cols[4].trim(),
                composeProject = cols.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() },
                composeService = cols.getOrNull(6)?.trim()?.takeIf { it.isNotEmpty() },
                composeWorkingDir = cols.getOrNull(7)?.trim()?.takeIf { it.isNotEmpty() },
                portsSummary = cols.getOrNull(8)?.trim().orEmpty(),
                cpuPct = stat?.first ?: 0f,
                memUsedBytes = stat?.second?.first ?: 0L,
                memLimitBytes = stat?.second?.second ?: 0L,
            )
        }.toList()
    }

    fun groupComposeStacks(containers: List<ContainerInfo>): Pair<List<ComposeStack>, List<ContainerInfo>> {
        val grouped = containers.filter { !it.composeProject.isNullOrBlank() }
            .groupBy { it.composeProject!! }
            .map { (project, list) ->
                ComposeStack(
                    projectName = project,
                    workingDir = list.firstNotNullOfOrNull { it.composeWorkingDir },
                    containers = list.sortedBy { it.name }.toImmutableList(),
                )
            }
            .sortedBy { it.projectName }
        val standalone = containers.filter { it.composeProject.isNullOrBlank() }.sortedBy { it.name }
        return grouped to standalone
    }

    fun parseSystemdAndCron(output: String): Pair<List<SystemdUnit>, List<CronEntry>> {
        val sec = splitSections(output)
        val units = sec["UNITS"].orEmpty().lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"), limit = 5)
            if (cols.size < 4) return@mapNotNull null
            val active = when (cols[2].lowercase()) {
                "active" -> SystemdActiveState.ACTIVE
                "reloading" -> SystemdActiveState.RELOADING
                "inactive" -> SystemdActiveState.INACTIVE
                "failed" -> SystemdActiveState.FAILED
                "activating" -> SystemdActiveState.ACTIVATING
                "deactivating" -> SystemdActiveState.DEACTIVATING
                else -> SystemdActiveState.UNKNOWN
            }
            SystemdUnit(
                name = cols[0],
                loadState = cols[1],
                activeState = active,
                subState = cols[3],
                description = cols.getOrElse(4) { "" },
            )
        }.toList()

        val crons = sec["CRON"].orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            if (parts.size < 6) return@mapNotNull null
            CronEntry(
                schedule = parts.take(5).joinToString(" "),
                command = parts[5],
                source = "user crontab",
            )
        }.toList()
        return units to crons
    }

    fun parseProcesses(output: String): List<ProcessInfo> {
        return output.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val cols = line.trim().split(Regex("\\s+"), limit = 8)
                if (cols.size < 8) return@mapNotNull null
                ProcessInfo(
                    pid = cols[0].toIntOrNull() ?: return@mapNotNull null,
                    user = cols[1],
                    cpuPct = cols[2].toFloatOrNull() ?: 0f,
                    memPct = cols[3].toFloatOrNull() ?: 0f,
                    rssKb = cols[4].toLongOrNull() ?: 0L,
                    state = cols[5],
                    elapsed = cols[6],
                    command = cols[7],
                )
            }
            .toList()
    }

    fun parseNetworkDetails(output: String): Pair<List<NetworkInterfaceInfo>, List<NetworkPortInfo>> {
        val sec = splitSections(output)
        val ipMap = mutableMapOf<String, String>()
        sec["IFACE"].orEmpty().lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 4) {
                ipMap[parts[1]] = parts[3].substringBefore('/')
            }
        }
        val ifaces = sec["NETDEV"].orEmpty().lineSequence().mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = line.substring(0, colon).trim()
            val cols = line.substring(colon + 1).trim().split(Regex("\\s+"))
            if (cols.size < 9) return@mapNotNull null
            NetworkInterfaceInfo(
                name = name,
                ipv4 = ipMap[name].orEmpty(),
                rxBytes = cols[0].toLongOrNull() ?: 0L,
                txBytes = cols[8].toLongOrNull() ?: 0L,
                linkUp = name != "lo",
            )
        }.toList()

        val ports = sec["PORTS"].orEmpty().lineSequence().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 5) return@mapNotNull null
            val local = cols[4]
            val port = local.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            val proc = cols.drop(5).joinToString(" ").substringAfter("users:((\"").substringBefore('"')
            NetworkPortInfo(
                protocol = cols[0],
                localAddress = local.substringBeforeLast(':'),
                port = port,
                processName = proc.ifBlank { "kernel" },
            )
        }.toList()
        return ifaces to ports
    }

    internal fun parseHumanBytes(raw: String): Long {
        val clean = raw.trim()
        if (clean.isEmpty()) return 0L
        val numStr = clean.takeWhile { it.isDigit() || it == '.' }
        val unitStr = clean.drop(numStr.length).trim().lowercase()
        val num = numStr.toDoubleOrNull() ?: return 0L
        val mult = when (unitStr) {
            "b" -> 1.0
            "k", "kb" -> 1_000.0
            "kib" -> 1024.0
            "m", "mb" -> 1_000_000.0
            "mib" -> 1024.0 * 1024.0
            "g", "gb" -> 1_000_000_000.0
            "gib" -> 1024.0 * 1024.0 * 1024.0
            "t", "tb" -> 1_000_000_000_000.0
            "tib" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }
        return (num * mult).toLong()
    }
}
