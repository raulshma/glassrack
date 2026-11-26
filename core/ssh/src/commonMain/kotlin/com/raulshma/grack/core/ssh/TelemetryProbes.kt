package com.raulshma.grack.core.ssh

import com.raulshma.grack.core.model.CustomProbeDefinition
import com.raulshma.grack.core.model.OsCapabilities

object TelemetryProbes {
    fun detectCapabilitiesScript(): String = """
        echo "===UNAME==="
        uname -s -r -m
        echo "===OS==="
        grep -E '^(PRETTY_NAME|ID)=' /etc/os-release 2>/dev/null || true
        echo "===MODEL==="
        tr -d '\0' < /proc/device-tree/model 2>/dev/null || true
        echo "===BINS==="
        for b in docker podman pct systemctl smartctl zpool nvidia-smi vcgencmd; do
          command -v "${'$'}b" >/dev/null 2>&1 && echo "${'$'}b=1" || echo "${'$'}b=0"
        done
        if docker compose version >/dev/null 2>&1 || command -v docker-compose >/dev/null 2>&1; then
          echo "docker_compose=1"
        else
          echo "docker_compose=0"
        fi
        sudo -n true >/dev/null 2>&1 && echo "sudo=1" || echo "sudo=0"
    """.trimIndent()

    fun buildCompositeProbe(
        capabilities: OsCapabilities,
        customProbes: List<CustomProbeDefinition> = emptyList(),
    ): String = buildString {
        appendLine("echo '===CPU==='")
        appendLine("grep '^cpu' /proc/stat 2>/dev/null || true")
        appendLine("echo '===MEM==='")
        appendLine("grep -E '^(MemTotal|MemAvailable|MemFree|Buffers|Cached|SwapTotal|SwapFree):' /proc/meminfo 2>/dev/null || true")
        appendLine("echo '===LOAD==='")
        appendLine("cat /proc/loadavg 2>/dev/null || true")
        appendLine("echo '===UPTIME==='")
        appendLine("cut -d' ' -f1 /proc/uptime 2>/dev/null || true")
        appendLine("echo '===TEMP==='")
        appendLine("cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || true")
        if (capabilities.isRaspberryPi || capabilities.hasVcgencmd) {
            appendLine("echo '===RPI==='")
            appendLine("vcgencmd measure_temp 2>/dev/null || true")
            appendLine("vcgencmd get_throttled 2>/dev/null || true")
            appendLine("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null || true")
            appendLine("tr -d '\\0' < /proc/device-tree/model 2>/dev/null || true")
        }
        appendLine("echo '===DISK==='")
        appendLine("df -P -B1 2>/dev/null | tail -n +2 | grep -vE '^(tmpfs|devtmpfs|overlay|squashfs)' || true")
        appendLine("echo '===NET==='")
        appendLine("tail -n +3 /proc/net/dev 2>/dev/null || true")
        if (capabilities.hasDocker || capabilities.hasPodman) {
            val bin = if (capabilities.hasDocker) "docker" else "podman"
            appendLine("echo '===CONTAINERS_COUNT==='")
            appendLine("$bin ps -a --format '{{.State}}' 2>/dev/null || true")
        }
        if (capabilities.hasNvidiaSmi) {
            appendLine("echo '===GPU==='")
            appendLine("nvidia-smi --query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw --format=csv,noheader,nounits 2>/dev/null || true")
        }
        if (capabilities.hasZpool) {
            appendLine("echo '===ZFS==='")
            appendLine("zpool list -Hp -o name,health,size,alloc 2>/dev/null || true")
        }
        customProbes.forEach { probe ->
            appendLine("echo '===CUSTOM_${probe.id}==='")
            appendLine("(${probe.command}) 2>&1 | head -n 5; echo \"__EXIT__=${'$'}?\"")
        }
    }

    fun dockerContainersScript(usePodman: Boolean = false): String {
        val bin = if (usePodman) "podman" else "docker"
        return """
            echo "===PS==="
            $bin ps -a --no-trunc --format '{{.ID}}|{{.Names}}|{{.Image}}|{{.State}}|{{.Status}}|{{.Label "com.docker.compose.project"}}|{{.Label "com.docker.compose.service"}}|{{.Label "com.docker.compose.project.working_dir"}}|{{.Ports}}' 2>/dev/null || true
            echo "===STATS==="
            $bin stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' 2>/dev/null || true
        """.trimIndent()
    }

    fun systemdUnitsScript(): String = """
        echo "===UNITS==="
        systemctl list-units --type=service --all --no-pager --no-legend --plain 2>/dev/null | head -n 120 || true
        echo "===CRON==="
        crontab -l 2>/dev/null | grep -v '^#' | grep -v '^$' || true
    """.trimIndent()

    fun processesScript(): String =
        "ps -eo pid,user:20,%cpu,%mem,rss,stat,etime,args --sort=-%cpu 2>/dev/null | head -n 100"

    fun networkDetailScript(): String = """
        echo "===IFACE==="
        ip -o -4 addr show 2>/dev/null || true
        echo "===NETDEV==="
        tail -n +3 /proc/net/dev 2>/dev/null || true
        echo "===PORTS==="
        ss -tulnp 2>/dev/null | tail -n +2 | head -n 60 || true
    """.trimIndent()
}
