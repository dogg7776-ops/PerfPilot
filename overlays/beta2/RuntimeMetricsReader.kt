package com.oai.perfpilot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import kotlin.math.roundToInt

/**
 * Truth-first read-only telemetry for K90 Max / MT6993.
 * CPU, GPU and battery temperature are independent probes. If a value cannot be
 * verified, return N/A rather than substituting another sensor or invented data.
 */
class RuntimeMetricsReader(context: Context, private val shell: ShellEngine) {
    private val appContext = context.applicationContext

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

    fun readGpu(): String {
        val cmd = """
            for f in /sys/kernel/ged/hal/current_freqency /sys/kernel/ged/hal/current_frequency /sys/kernel/ged/hal/gpu_freq /sys/kernel/ged/gpu_freq; do
              [ -r "$f" ] || continue
              v=$(cat "$f" 2>/dev/null | head -n1)
              [ -n "$v" ] && { echo "DIRECT=$v"; exit 0; }
            done
            for d in /sys/class/devfreq/*; do
              [ -d "$d" ] || continue
              n=$(cat "$d/name" 2>/dev/null); b=$(basename "$d")
              case "$n $b" in *gpu*|*GPU*|*mali*|*MALI*)
                v=$(cat "$d/cur_freq" 2>/dev/null)
                [ -n "$v" ] && { echo "DEVFREQ=$v"; exit 0; }
              ;; esac
            done
            if [ -r /proc/gpufreqv2/gpufreq_status ]; then
              grep -Ei 'STACK[- ]?OPP|GPU[- ]?OPP|real[ _-]*freq|cur[^[:alnum:]]*freq|freq[^[:alnum:]]*cur|fgpu' /proc/gpufreqv2/gpufreq_status 2>/dev/null | head -n 12
              exit 0
            fi
            if [ -r /proc/gpufreq/gpufreq_var_dump ]; then
              grep -Ei 'real[ _-]*freq|gpu_loading|g_cur_opp_idx' /proc/gpufreq/gpufreq_var_dump 2>/dev/null | head -n 8
              exit 0
            fi
            exit 1
        """.trimIndent()
        val raw = readFirstUseful(cmd, 2800) ?: return "N/A"

        val tagged = Regex("(?im)^(?:DIRECT|DEVFREQ)=\\s*([0-9]+)").find(raw)
            ?.groupValues?.getOrNull(1)?.toLongOrNull()
        val freqTagged = Regex("(?i)(?:freq(?:uency)?|fgpu)[^0-9]{0,24}([0-9]+)")
            .findAll(raw).mapNotNull { it.groupValues.getOrNull(1)?.toLongOrNull() }
            .firstOrNull { it == 0L || it in 10_000L..2_000_000_000L }
        val stackFallback = raw.lineSequence()
            .filter { it.contains("STACK", true) || it.contains("GPU", true) }
            .flatMap { Regex("([0-9]+)").findAll(it).map { m -> m.groupValues[1].toLongOrNull() } }
            .filterNotNull()
            .firstOrNull { it in 10_000L..2_000_000_000L }
        val n = tagged ?: freqTagged ?: stackFallback ?: return "N/A"
        if (n == 0L) return "休眠"
        val mhz = when {
            n >= 20_000_000L -> n / 1_000_000.0
            n >= 20_000L -> n / 1_000.0
            else -> n.toDouble()
        }
        return if (mhz in 1.0..5000.0) "${"%.0f".format(java.util.Locale.US, mhz)} MHz" else "N/A"
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
        val cmd = """
            echo '[ged]'
            for f in /sys/kernel/ged/hal/current_freqency /sys/kernel/ged/hal/current_frequency /sys/kernel/ged/hal/gpu_freq /sys/kernel/ged/gpu_freq; do [ -e "$f" ] && { ls -l "$f" 2>/dev/null; cat "$f" 2>/dev/null | head -n2; }; done
            echo '[gpufreqv2]'
            if [ -e /proc/gpufreqv2/gpufreq_status ]; then ls -l /proc/gpufreqv2/gpufreq_status 2>/dev/null; grep -Ei 'STACK[- ]?OPP|GPU[- ]?OPP|freq|fgpu' /proc/gpufreqv2/gpufreq_status 2>/dev/null | head -n20; else echo missing; fi
            echo '[devfreq]'
            for d in /sys/class/devfreq/*; do [ -d "$d" ] || continue; n=$(cat "$d/name" 2>/dev/null); case "$n $(basename "$d")" in *gpu*|*GPU*|*mali*|*MALI*) echo "$d name=$n cur=$(cat "$d/cur_freq" 2>/dev/null)";; esac; done
        """.trimIndent()
        val r = runRead(cmd, 3000)
        return (r.out + if (r.err.isNotBlank()) "\nERR=${r.err}" else "").trim().ifBlank { "no gpu probe output" }.take(3000)
    }

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
