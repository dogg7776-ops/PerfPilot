package com.oai.perfpilot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import java.io.File
import kotlin.math.roundToInt

/**
 * Truth-first read-only telemetry for K90 Max / MT6993.
 * CPU, GPU and battery temperature are independent probes. If a value cannot be
 * verified, return N/A rather than substituting another sensor or invented data.
 */
class RuntimeMetricsReader(context: Context, private val shell: ShellEngine) {
    private val appContext = context.applicationContext

    @Volatile private var cachedGpuPath: String? = null
    @Volatile private var lastGpuDeepScanAt = 0L
    @Volatile private var lastGpuSource = "none"

    fun readCpu(): String {
        val usage = try { sampleProcStatLocal() } catch (_: Throwable) { null }
            ?: sampleProcStatShell()
        val freq = readFirstUseful(
            "for p in /sys/devices/system/cpu/cpufreq/policy*; do f=\$(cat \"\$p/scaling_cur_freq\" 2>/dev/null); [ -n \"\$f\" ] && printf '%s=%s ' \"\$(basename \"\$p\")\" \"\$f\"; done",
            2200
        )
        val prettyFreq = freq?.let { raw ->
            raw.trim().split(Regex("\\s+")).mapNotNull { token ->
                val parts = token.split('=', limit = 2)
                val khz = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                val ghz = khz / 1_000_000.0
                "${parts[0]} ${"%.2f".format(java.util.Locale.US, ghz)}G"
            }.take(3).joinToString(" · ")
        }.orEmpty()
        return listOfNotNull(usage?.let { "$it%" }, prettyFreq.ifBlank { null })
            .joinToString(" · ").ifBlank { "N/A" }
    }

    private fun parseCpuLine(line: String): Pair<Long, Long>? {
        if (!line.startsWith("cpu ")) return null
        val nums = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        val total = nums.sum()
        val idle = nums.getOrElse(3) { 0L } + nums.getOrElse(4) { 0L }
        return total to idle
    }

    private fun usageFrom(a: Pair<Long, Long>, b: Pair<Long, Long>): Int? {
        val dt = b.first - a.first
        val di = b.second - a.second
        if (dt <= 0) return null
        return ((dt - di).toDouble() * 100.0 / dt).roundToInt().coerceIn(0, 100)
    }

    private fun sampleProcStatLocal(): Int? {
        fun sample(): Pair<Long, Long>? {
            val line = File("/proc/stat").useLines { seq -> seq.firstOrNull { it.startsWith("cpu ") } } ?: return null
            return parseCpuLine(line)
        }
        val a = sample() ?: return null
        Thread.sleep(160)
        val b = sample() ?: return null
        return usageFrom(a, b)
    }

    private fun sampleProcStatShell(): Int? {
        val r = runRead("head -n 1 /proc/stat 2>/dev/null; sleep 0.18; head -n 1 /proc/stat 2>/dev/null", 1600)
        if (!r.ok) return null
        val lines = r.out.lineSequence().filter { it.startsWith("cpu ") }.toList()
        if (lines.size < 2) return null
        val a = parseCpuLine(lines[0]) ?: return null
        val b = parseCpuLine(lines[1]) ?: return null
        return usageFrom(a, b)
    }

    /**
     * K90 Max / MT6993 GPU frequency reader.
     * Fast path: cached/known sysfs nodes.
     * MTK path: gpufreqv2 status + OPP table mapping.
     * Slow discovery runs at most once every 30 seconds when all known paths fail.
     */
    fun readGpu(): String {
        readCachedGpuPath()?.let { return it }
        readKnownGpuSysfs()?.let { return it }
        readGpufreqV2()?.let { return it }

        val now = SystemClock.elapsedRealtime()
        if (now - lastGpuDeepScanAt > 30_000L) {
            lastGpuDeepScanAt = now
            discoverGpuSysfs()?.let { return it }
        }

        readLegacyGpuProc()?.let { return it }
        lastGpuSource = "unresolved"
        return "N/A"
    }

    private fun readCachedGpuPath(): String? {
        val path = cachedGpuPath ?: return null
        val r = runRead("cat ${shQuote(path)} 2>/dev/null | head -n1", 1400)
        if (!r.ok || r.out.isBlank()) {
            cachedGpuPath = null
            return null
        }
        val display = parseGpuFrequency(r.out) ?: run {
            cachedGpuPath = null
            return null
        }
        lastGpuSource = "sysfs:$path"
        return display
    }

    private fun readKnownGpuSysfs(): String? {
        val d = '$'
        val cmd = """
            for f in \
              /sys/kernel/ged/hal/current_freqency \
              /sys/kernel/ged/hal/current_frequency \
              /sys/kernel/ged/hal/gpu_freq \
              /sys/kernel/ged/gpu_freq \
              /sys/class/misc/mali*/device/devfreq/*/cur_freq \
              /sys/devices/platform/*gpu*/devfreq/*/cur_freq \
              /sys/devices/platform/*mali*/devfreq/*/cur_freq \
              /sys/devices/platform/soc/*gpu*/devfreq/*/cur_freq \
              /sys/class/devfreq/*/cur_freq; do
              [ -r "${d}f" ] || continue
              target=${d}(readlink -f "${d}f" 2>/dev/null)
              dir=${d}(dirname "${d}f")
              name=${d}(cat "${d}dir/name" 2>/dev/null | head -n1)
              case "${d}f ${d}target ${d}name" in
                *gpu*|*GPU*|*mali*|*MALI*|*mfg*|*MFG*|*ged*|*GED*|*13000000*)
                  v=${d}(cat "${d}f" 2>/dev/null | head -n1)
                  [ -n "${d}v" ] && { printf 'SYSFS|%s|%s\n' "${d}f" "${d}v"; exit 0; }
                ;;
              esac
            done
            exit 1
        """.trimIndent()
        val r = runRead(cmd, 2200)
        if (!r.ok || r.out.isBlank()) return null
        val line = r.out.lineSequence().firstOrNull { it.startsWith("SYSFS|") } ?: return null
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val display = parseGpuFrequency(parts[2]) ?: return null
        cachedGpuPath = parts[1]
        lastGpuSource = "sysfs:${parts[1]}"
        return display
    }

    private fun readGpufreqV2(): String? {
        val d = '$'
        val cmd = """
            [ -r /proc/gpufreqv2/gpufreq_status ] || exit 1
            echo '__STATUS__'
            cat /proc/gpufreqv2/gpufreq_status 2>/dev/null | head -n 120
            if [ -r /proc/gpufreqv2/stack_working_opp_table ]; then
              echo '__STACK_TABLE__'
              cat /proc/gpufreqv2/stack_working_opp_table 2>/dev/null | head -n 140
            fi
            if [ -r /proc/gpufreqv2/gpu_working_opp_table ]; then
              echo '__GPU_TABLE__'
              cat /proc/gpufreqv2/gpu_working_opp_table 2>/dev/null | head -n 140
            fi
            exit 0
        """.trimIndent()
        val r = runRead(cmd, 2400)
        if (!r.ok || r.out.isBlank()) return null

        val status = section(r.out, "__STATUS__", listOf("__STACK_TABLE__", "__GPU_TABLE__"))
        parseFrequencyFromStatus(status)?.let {
            lastGpuSource = "gpufreqv2-status"
            return it
        }

        val stackIdx = extractOppIndex(status, "STACK")
        val gpuIdx = extractOppIndex(status, "GPU")
        if (stackIdx != null) {
            val table = section(r.out, "__STACK_TABLE__", listOf("__GPU_TABLE__"))
            parseOppTableFrequency(table, stackIdx)?.let {
                lastGpuSource = "gpufreqv2-stack-opp[$stackIdx]"
                return it
            }
        }
        if (gpuIdx != null) {
            val table = section(r.out, "__GPU_TABLE__", emptyList())
            parseOppTableFrequency(table, gpuIdx)?.let {
                lastGpuSource = "gpufreqv2-gpu-opp[$gpuIdx]"
                return it
            }
        }
        return null
    }

    private fun discoverGpuSysfs(): String? {
        val d = '$'
        val cmd = """
            for root in /sys/devices/platform /sys/class; do
              [ -d "${d}root" ] || continue
              find "${d}root" -maxdepth 9 -type f \( -name cur_freq -o -name current_freq -o -name gpu_freq \) 2>/dev/null | \
                grep -Ei '/(gpu|mali|mfg|ged|gpufreq)[^/]*/|/(gpu|mali|mfg|ged|gpufreq)/' | head -n 30 | while read f; do
                  [ -r "${d}f" ] || continue
                  v=${d}(cat "${d}f" 2>/dev/null | head -n1)
                  [ -n "${d}v" ] && { printf 'DISCOVER|%s|%s\n' "${d}f" "${d}v"; break; }
                done
            done
        """.trimIndent()
        val r = runRead(cmd, 3200)
        if (!r.ok || r.out.isBlank()) return null
        val line = r.out.lineSequence().firstOrNull { it.startsWith("DISCOVER|") } ?: return null
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val display = parseGpuFrequency(parts[2]) ?: return null
        cachedGpuPath = parts[1]
        lastGpuSource = "discovered:${parts[1]}"
        return display
    }

    private fun readLegacyGpuProc(): String? {
        val cmd = """
            if [ -r /proc/gpufreq/gpufreq_var_dump ]; then
              grep -Ei 'real[ _-]*freq|g_cur_opp_idx' /proc/gpufreq/gpufreq_var_dump 2>/dev/null | head -n 12
              exit 0
            fi
            if [ -r /proc/mali/frequency ]; then
              cat /proc/mali/frequency 2>/dev/null | head -n 4
              exit 0
            fi
            exit 1
        """.trimIndent()
        val r = runRead(cmd, 1800)
        if (!r.ok || r.out.isBlank()) return null
        val display = parseFrequencyFromStatus(r.out) ?: parseGpuFrequency(r.out)
        if (display != null) lastGpuSource = "legacy-gpufreq"
        return display
    }

    private fun parseFrequencyFromStatus(text: String): String? {
        val lines = text.lineSequence().filter { line ->
            line.contains("freq", true) || line.contains("STACK-OPP", true) ||
                line.contains("STACK OPP", true) || line.contains("GPU-OPP", true) ||
                line.contains("GPU OPP", true) || line.contains("fgpu", true)
        }
        val explicit = Regex("""(?i)(?:real[ _-]*freq|cur(?:rent)?[ _-]*freq|freq(?:uency)?|fgpu)\s*[:=]?\s*([0-9]{2,10})(?:\s*(mhz|khz|hz))?""")
        for (line in lines) {
            val match = explicit.find(line) ?: continue
            val n = match.groupValues[1].toLongOrNull() ?: continue
            val unit = match.groupValues.getOrNull(2).orEmpty()
            parseGpuFrequencyNumber(n, unit)?.let { return it }
        }

        for (line in text.lineSequence().filter { it.contains("STACK", true) || it.contains("GPU", true) }) {
            val nums = Regex("([0-9]{4,10})").findAll(line)
                .mapNotNull { it.groupValues[1].toLongOrNull() }
                .filter { it in 20_000L..2_000_000_000L }
                .toList()
            nums.maxOrNull()?.let { n -> parseGpuFrequencyNumber(n, "")?.let { return it } }
        }
        return null
    }

    private fun extractOppIndex(status: String, kind: String): Int? {
        val re = Regex("""(?i)$kind[- ]?OPP[^0-9-]{0,16}\[?(-?[0-9]{1,3})""")
        return status.lineSequence().mapNotNull { line -> re.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .firstOrNull { it >= 0 }
    }

    private fun parseOppTableFrequency(table: String, index: Int): String? {
        val indexPatterns = listOf(
            Regex("""^\s*\[\s*$index\s*]\b"""),
            Regex("""^\s*$index\s*[:)]""")
        )
        val line = table.lineSequence().firstOrNull { l -> indexPatterns.any { it.containsMatchIn(l) } } ?: return null
        val explicit = Regex("""(?i)(?:freq|frequency)\s*[:=]?\s*([0-9]{2,10})(?:\s*(mhz|khz|hz))?""").find(line)
        if (explicit != null) {
            val n = explicit.groupValues[1].toLongOrNull() ?: return null
            return parseGpuFrequencyNumber(n, explicit.groupValues.getOrNull(2).orEmpty())
        }
        val nums = Regex("([0-9]{4,10})").findAll(line)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .filter { it in 20_000L..2_000_000_000L }
            .toList()
        return nums.maxOrNull()?.let { parseGpuFrequencyNumber(it, "") }
    }

    private fun parseGpuFrequency(text: String): String? {
        val explicit = Regex("""(?i)(?:freq|frequency)?\s*[:=]?\s*([0-9]{1,10})(?:\s*(mhz|khz|hz))?""").find(text.trim())
        val n = explicit?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: Regex("([0-9]{1,10})").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return null
        val unit = explicit?.groupValues?.getOrNull(2).orEmpty()
        return parseGpuFrequencyNumber(n, unit)
    }

    private fun parseGpuFrequencyNumber(n: Long, unitHint: String): String? {
        if (n == 0L) return "休眠"
        val unit = unitHint.lowercase()
        val mhz = when {
            unit == "mhz" -> n.toDouble()
            unit == "khz" -> n / 1_000.0
            unit == "hz" -> n / 1_000_000.0
            n >= 20_000_000L -> n / 1_000_000.0
            n >= 20_000L -> n / 1_000.0
            else -> n.toDouble()
        }
        return if (mhz in 1.0..5000.0) "${"%.0f".format(java.util.Locale.US, mhz)} MHz" else null
    }

    private fun section(text: String, marker: String, nextMarkers: List<String>): String {
        val start = text.indexOf(marker)
        if (start < 0) return ""
        val bodyStart = start + marker.length
        val ends = nextMarkers.map { text.indexOf(it, bodyStart) }.filter { it >= 0 }
        val end = ends.minOrNull() ?: text.length
        return text.substring(bodyStart, end)
    }

    /** Battery temperature only. Do not substitute the hottest thermal zone. */
    fun readBatteryTemperature(): String {
        try {
            val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (raw != Int.MIN_VALUE) {
                val c = raw / 10.0
                if (c in 0.0..80.0) return "${"%.1f".format(java.util.Locale.US, c)}°C"
            }
        } catch (_: Throwable) {}

        val r = runRead("dumpsys battery 2>/dev/null | grep -i 'temperature' | head -n1", 1800)
        if (r.ok) {
            val raw = Regex("(-?[0-9]+)").find(r.out)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (raw != null) {
                val c = if (kotlin.math.abs(raw) > 100) raw / 10.0 else raw.toDouble()
                if (c in 0.0..80.0) return "${"%.1f".format(java.util.Locale.US, c)}°C"
            }
        }
        return "N/A"
    }

    /** Raw read-only probe used by diagnostics when GPU frequency still cannot be parsed. */
    fun gpuProbeReport(): String {
        val d = '$'
        val cmd = """
            echo '[source-cache]'
            echo '${lastGpuSource}'
            echo '[ged]'
            for f in /sys/kernel/ged/hal/current_freqency /sys/kernel/ged/hal/current_frequency /sys/kernel/ged/hal/gpu_freq /sys/kernel/ged/gpu_freq; do [ -e "${d}f" ] && { ls -l "${d}f" 2>/dev/null; cat "${d}f" 2>/dev/null | head -n2; }; done
            echo '[gpufreqv2-status]'
            if [ -e /proc/gpufreqv2/gpufreq_status ]; then ls -l /proc/gpufreqv2/gpufreq_status 2>/dev/null; cat /proc/gpufreqv2/gpufreq_status 2>/dev/null | head -n80; else echo missing; fi
            echo '[gpufreqv2-stack-table]'
            [ -r /proc/gpufreqv2/stack_working_opp_table ] && cat /proc/gpufreqv2/stack_working_opp_table 2>/dev/null | head -n40
            echo '[gpufreqv2-gpu-table]'
            [ -r /proc/gpufreqv2/gpu_working_opp_table ] && cat /proc/gpufreqv2/gpu_working_opp_table 2>/dev/null | head -n40
            echo '[devfreq-candidates]'
            for x in /sys/class/devfreq/*; do [ -e "${d}x" ] || continue; target=${d}(readlink -f "${d}x" 2>/dev/null); n=${d}(cat "${d}x/name" 2>/dev/null); c=${d}(cat "${d}x/cur_freq" 2>/dev/null); printf '%s -> %s name=%s cur=%s\n' "${d}x" "${d}target" "${d}n" "${d}c"; done
            echo '[mali-devfreq]'
            for f in /sys/class/misc/mali*/device/devfreq/*/cur_freq; do [ -e "${d}f" ] && echo "${d}f = ${d}(cat "${d}f" 2>/dev/null)"; done
        """.trimIndent()
        val r = runRead(cmd, 3800)
        return (r.out + if (r.err.isNotBlank()) "\nERR=${r.err}" else "").trim().ifBlank { "no gpu probe output" }.take(6000)
    }

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun readFirstUseful(command: String, timeoutMs: Long): String? {
        val r = runRead(command, timeoutMs)
        return if (r.ok && r.out.isNotBlank()) r.out.trim() else null
    }

    private fun runRead(command: String, timeoutMs: Long): ShellEngine.Result {
        val attempts = mutableListOf<ShellEngine.Result>()
        if (shell.hasShizukuPermission()) {
            val r = shell.exec(command, ShellEngine.Mode.SHIZUKU, timeoutMs)
            attempts += r
            if (r.ok && r.out.isNotBlank()) return r
        }
        val local = shell.exec(command, ShellEngine.Mode.LOCAL, timeoutMs)
        attempts += local
        if (local.ok && local.out.isNotBlank()) return local
        if (shell.hasRoot()) {
            val root = shell.exec(command, ShellEngine.Mode.ROOT, timeoutMs)
            attempts += root
            if (root.ok && root.out.isNotBlank()) return root
        }
        return attempts.firstOrNull { it.ok } ?: attempts.lastOrNull()
            ?: ShellEngine.Result(127, "", "no probe", ShellEngine.Mode.LOCAL)
    }
}
