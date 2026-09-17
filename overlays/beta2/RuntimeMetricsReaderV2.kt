package com.oai.perfpilot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import kotlin.math.roundToInt

/**
 * Read-only telemetry. Never fabricates values: if current GPU frequency cannot
 * be verified, it falls back to verified GPU utilization; otherwise returns N/A.
 */
class RuntimeMetricsReaderV2(context: Context, private val shell: ShellEngine) {
    data class GpuReading(val display: String, val source: String, val raw: String = "")

    private val appContext = context.applicationContext
    @Volatile private var lastGpuSource: String = "none"
    @Volatile private var cachedGpuPath: String? = null

    private fun modes(): List<ShellEngine.Mode> = buildList {
        if (shell.hasShizukuPermission()) add(ShellEngine.Mode.SHIZUKU)
        if (shell.hasRoot()) add(ShellEngine.Mode.ROOT)
        add(ShellEngine.Mode.LOCAL)
    }.distinct()

    private fun runRead(command: String, timeoutMs: Long = 2600): ShellEngine.Result {
        var last: ShellEngine.Result? = null
        for (m in modes()) {
            val r = shell.exec(command, m, timeoutMs)
            last = r
            if (r.ok && r.out.isNotBlank()) return r
        }
        return last ?: ShellEngine.Result(127, "", "no probe", ShellEngine.Mode.LOCAL)
    }

    fun readCpu(): String {
        val usage = sampleProcStatLocal() ?: sampleProcStatShell()
        val d = '$'
        val r = runRead(
            "for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -r \"${d}p/scaling_cur_freq\" ] || continue; f=${d}(cat \"${d}p/scaling_cur_freq\" 2>/dev/null); printf '%s=%s ' \"${d}(basename \"${d}p\")\" \"${d}f\"; done",
            1900
        )
        val clocks = r.out.trim().split(Regex("\\s+")).mapNotNull { token ->
            val p = token.split('=', limit = 2)
            val khz = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            "${p[0]} ${"%.2f".format(java.util.Locale.US, khz / 1_000_000.0)}G"
        }.take(3).joinToString(" · ")
        return listOfNotNull(usage?.let { "$it%" }, clocks.ifBlank { null }).joinToString(" · ").ifBlank { "N/A" }
    }

    private fun sampleProcStatLocal(): Int? {
        fun sample(): Pair<Long, Long>? {
            val line = File("/proc/stat").useLines { seq -> seq.firstOrNull { it.startsWith("cpu ") } } ?: return null
            return parseCpuLine(line)
        }
        return try {
            val a = sample() ?: return null
            Thread.sleep(140)
            val b = sample() ?: return null
            cpuUsage(a, b)
        } catch (_: Throwable) { null }
    }

    private fun sampleProcStatShell(): Int? {
        val r = runRead("head -n1 /proc/stat; sleep 0.16; head -n1 /proc/stat", 1400)
        val lines = r.out.lineSequence().filter { it.startsWith("cpu ") }.toList()
        if (lines.size < 2) return null
        return cpuUsage(parseCpuLine(lines[0]) ?: return null, parseCpuLine(lines[1]) ?: return null)
    }

    private fun parseCpuLine(line: String): Pair<Long, Long>? {
        val n = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (n.size < 4) return null
        return n.sum() to (n.getOrElse(3) { 0L } + n.getOrElse(4) { 0L })
    }

    private fun cpuUsage(a: Pair<Long, Long>, b: Pair<Long, Long>): Int? {
        val total = b.first - a.first
        val idle = b.second - a.second
        if (total <= 0) return null
        return (((total - idle) * 100.0) / total).roundToInt().coerceIn(0, 100)
    }

    fun readGpu(): String = readGpuDetailed().display

    fun readGpuDetailed(): GpuReading {
        cachedGpuPath?.let { path ->
            val r = runRead("cat '$path' 2>/dev/null | head -n1", 1200)
            parseFrequency(r.out)?.let { hz ->
                lastGpuSource = path
                return GpuReading(hz, path, r.out.trim())
            }
            cachedGpuPath = null
        }
        probeDirectGpuNodes()?.let { return it }
        probeGpuFreqV2()?.let { return it }
        probeLegacyGpuFreq()?.let { return it }
        probeGpuUtilization()?.let { return it }
        lastGpuSource = "unresolved"
        return GpuReading("N/A", "unresolved", gpuProbeReport())
    }

    private fun probeDirectGpuNodes(): GpuReading? {
        val d = '$'
        val cmd = """
            for f in \
              /sys/kernel/debug/ged/hal/current_freqency \
              /d/ged/hal/current_freqency \
              /sys/kernel/ged/hal/current_freqency \
              /sys/kernel/debug/ged/hal/current_frequency \
              /sys/kernel/ged/hal/current_frequency \
              /sys/kernel/debug/ged/hal/gpu_freq \
              /sys/kernel/ged/hal/gpu_freq \
              /sys/kernel/ged/gpu_freq \
              /sys/class/misc/mali*/device/devfreq/*/cur_freq \
              /sys/devices/platform/*mali*/devfreq/*/cur_freq \
              /sys/devices/platform/*gpu*/devfreq/*/cur_freq \
              /sys/class/devfreq/*/cur_freq; do
              [ -r "${d}f" ] || continue
              real=${d}(readlink -f "${d}f" 2>/dev/null)
              name=${d}(cat "${d}(dirname "${d}f")/name" 2>/dev/null | head -n1)
              case "${d}f ${d}real ${d}name" in
                *gpu*|*GPU*|*mali*|*MALI*|*mfg*|*MFG*|*ged*|*GED*)
                  v=${d}(cat "${d}f" 2>/dev/null | head -n1)
                  [ -n "${d}v" ] && { printf '%s|%s\n' "${d}f" "${d}v"; exit 0; }
                ;;
              esac
            done
            exit 1
        """.trimIndent()
        val r = runRead(cmd, 2300)
        val line = r.out.lineSequence().firstOrNull { it.contains('|') } ?: return null
        val p = line.split('|', limit = 2)
        val shown = parseFrequency(p.getOrNull(1).orEmpty()) ?: return null
        cachedGpuPath = p[0]
        lastGpuSource = p[0]
        return GpuReading(shown, p[0], line)
    }

    private fun probeGpuFreqV2(): GpuReading? {
        val r = runRead(
            "if [ -r /proc/gpufreqv2/gpufreq_status ]; then cat /proc/gpufreqv2/gpufreq_status; echo __STACK__; cat /proc/gpufreqv2/stack_working_opp_table 2>/dev/null; echo __GPU__; cat /proc/gpufreqv2/gpu_working_opp_table 2>/dev/null; else exit 1; fi",
            2600
        )
        if (!r.ok || r.out.isBlank()) return null
        val status = r.out.substringBefore("__STACK__")
        val explicitPatterns = listOf(
            Regex("(?i)(?:real[ _-]*freq|cur(?:rent)?[ _-]*(?:gpu[ _-]*)?freq|cur_fgpu|fmeter_freq|frequency)\\s*[:=]?\\s*([0-9]{3,10})(?:\\s*(mhz|khz|hz))?"),
            Regex("(?i)(?:STACK|GPU)[ -]?OPP.*?(?:freq|frequency)\\s*[:=]?\\s*([0-9]{3,10})(?:\\s*(mhz|khz|hz))?")
        )
        for (re in explicitPatterns) {
            val m = re.find(status) ?: continue
            parseFrequencyNumber(m.groupValues[1].toLongOrNull() ?: continue, m.groupValues.getOrElse(2) { "" })?.let {
                lastGpuSource = "/proc/gpufreqv2/gpufreq_status"
                return GpuReading(it, lastGpuSource, status.take(1800))
            }
        }
        val stackLine = status.lineSequence().firstOrNull { it.contains("STACK", true) && it.contains("OPP", true) }
        if (stackLine != null) {
            val candidates = Regex("([0-9]{4,10})").findAll(stackLine)
                .mapNotNull { it.groupValues[1].toLongOrNull() }
                .filter { it in 100_000L..2_000_000_000L }.toList()
            candidates.maxOrNull()?.let { n ->
                parseFrequencyNumber(n, "")?.let {
                    lastGpuSource = "gpufreqv2 STACK OPP"
                    return GpuReading(it, lastGpuSource, stackLine)
                }
            }
        }
        val idx = Regex("(?i)STACK[ -]?OPP[^0-9-]{0,20}(-?[0-9]{1,3})").find(status)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (idx != null && idx >= 0) {
            val table = r.out.substringAfter("__STACK__", "").substringBefore("__GPU__")
            val line = table.lineSequence().firstOrNull { Regex("^\\s*\\[?\\s*$idx\\s*(?:\\]|:|\\))").containsMatchIn(it) }
            if (line != null) {
                val n = Regex("([0-9]{5,10})").findAll(line).mapNotNull { it.groupValues[1].toLongOrNull() }
                    .filter { it in 100_000L..2_000_000_000L }.maxOrNull()
                if (n != null) parseFrequencyNumber(n, "")?.let {
                    lastGpuSource = "gpufreqv2 OPP[$idx]"
                    return GpuReading(it, lastGpuSource, line)
                }
            }
        }
        return null
    }

    private fun probeLegacyGpuFreq(): GpuReading? {
        val r = runRead(
            "if [ -r /proc/gpufreq/gpufreq_var_dump ]; then grep -Ei 'real[ _-]*freq|gpu_loading|g_cur_opp_idx' /proc/gpufreq/gpufreq_var_dump | head -n20; elif [ -r /proc/mali/frequency ]; then cat /proc/mali/frequency; else exit 1; fi",
            1900
        )
        if (!r.ok || r.out.isBlank()) return null
        val m = Regex("(?i)real[ _-]*freq\\s*=\\s*([0-9]{3,10})").find(r.out)
        if (m != null) parseFrequencyNumber(m.groupValues[1].toLong(), "")?.let {
            lastGpuSource = "legacy gpufreq"
            return GpuReading(it, lastGpuSource, r.out.take(900))
        }
        parseFrequency(r.out)?.let {
            lastGpuSource = "legacy gpu"
            return GpuReading(it, lastGpuSource, r.out.take(900))
        }
        return null
    }

    private fun probeGpuUtilization(): GpuReading? {
        val d = '$'
        val r = runRead(
            "for f in /sys/kernel/debug/ged/hal/gpu_utilization /d/ged/hal/gpu_utilization /sys/kernel/ged/hal/gpu_utilization /sys/module/ged/parameters/gpu_loading; do [ -r \"${d}f\" ] || continue; echo \"${d}f|${d}(cat \"${d}f\" 2>/dev/null | head -n1)\"; exit 0; done; exit 1",
            1800
        )
        if (!r.ok || r.out.isBlank()) return null
        val line = r.out.lineSequence().firstOrNull() ?: return null
        val p = line.split('|', limit = 2)
        val nums = Regex("-?[0-9]+(?:\\.[0-9]+)?").findAll(p.getOrElse(1) { "" }).mapNotNull { it.value.toDoubleOrNull() }.toList()
        val util = nums.firstOrNull { it in 0.0..100.0 } ?: return null
        lastGpuSource = p[0]
        return GpuReading("负载 ${"%.0f".format(java.util.Locale.US, util)}%", p[0], line)
    }

    private fun parseFrequency(text: String): String? {
        val m = Regex("(?i)([0-9]{3,10})(?:\\s*(mhz|khz|hz))?").find(text.trim()) ?: return null
        return parseFrequencyNumber(m.groupValues[1].toLongOrNull() ?: return null, m.groupValues.getOrElse(2) { "" })
    }

    private fun parseFrequencyNumber(n: Long, unit: String): String? {
        if (n == 0L) return "休眠"
        val mhz = when (unit.lowercase()) {
            "mhz" -> n.toDouble()
            "khz" -> n / 1_000.0
            "hz" -> n / 1_000_000.0
            else -> when {
                n >= 20_000_000L -> n / 1_000_000.0
                n >= 20_000L -> n / 1_000.0
                else -> n.toDouble()
            }
        }
        return if (mhz in 1.0..5000.0) "${"%.0f".format(java.util.Locale.US, mhz)} MHz" else null
    }

    fun readBatteryTemperature(): String {
        try {
            val i = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val raw = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (raw != Int.MIN_VALUE) {
                val c = raw / 10.0
                if (c in 0.0..80.0) return "${"%.1f".format(java.util.Locale.US, c)}°C"
            }
        } catch (_: Throwable) {}
        return "N/A"
    }

    fun readForegroundFps(): String {
        val d = '$'
        val cmd = """
            pkg=${d}(dumpsys activity activities 2>/dev/null | sed -n 's/.*mResumedActivity:.* \\([^/ ]*\\)\\/.*/\\1/p' | head -n1)
            [ -n "${d}pkg" ] || pkg=${d}(dumpsys window 2>/dev/null | sed -n 's/.*mCurrentFocus=.* \\([^/ ]*\\)\\/.*/\\1/p' | head -n1)
            layer=${d}(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F "${d}pkg" | grep -v 'Background for' | head -n1)
            [ -n "${d}layer" ] || exit 1
            dumpsys SurfaceFlinger --latency "${d}layer" 2>/dev/null
        """.trimIndent()
        val r = runRead(cmd, 3000)
        if (!r.ok || r.out.isBlank()) return "N/A"
        val stamps = r.out.lineSequence().drop(1).mapNotNull { line ->
            line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        }.toList().takeLast(120)
        if (stamps.size < 3) return "N/A"
        val diffs = stamps.zipWithNext { a, b -> b - a }.filter { it in 1_000_000L..100_000_000L }
        if (diffs.size < 2) return "N/A"
        val fps = 1_000_000_000.0 / diffs.average()
        return if (fps in 1.0..300.0) "${"%.1f".format(java.util.Locale.US, fps)}" else "N/A"
    }

    fun gpuProbeReport(): String {
        val d = '$'
        val r = runRead(
            """
            echo '[source] $lastGpuSource'
            echo '[ged]'
            for f in /sys/kernel/debug/ged/hal/current_freqency /d/ged/hal/current_freqency /sys/kernel/ged/hal/current_freqency /sys/kernel/debug/ged/hal/gpu_utilization /d/ged/hal/gpu_utilization /sys/kernel/ged/hal/gpu_utilization /sys/module/ged/parameters/gpu_loading; do [ -e "${d}f" ] && { echo "${d}f"; cat "${d}f" 2>&1 | head -n3; }; done
            echo '[gpufreqv2]'
            if [ -e /proc/gpufreqv2/gpufreq_status ]; then cat /proc/gpufreqv2/gpufreq_status 2>&1 | head -n80; else echo missing; fi
            echo '[stack table]'
            cat /proc/gpufreqv2/stack_working_opp_table 2>&1 | head -n40
            echo '[devfreq candidates]'
            for f in /sys/class/devfreq/*/cur_freq; do [ -e "${d}f" ] || continue; echo "${d}f=${d}(cat "${d}f" 2>&1 | head -n1)"; done | head -n30
            """.trimIndent(),
            3300
        )
        return (r.out + if (r.err.isNotBlank()) "\nERR=${r.err}" else "").trim().take(5000)
    }
}
