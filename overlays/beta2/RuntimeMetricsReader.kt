package com.oai.perfpilot

import java.io.File
import kotlin.math.roundToInt

/**
 * Read-only runtime telemetry for the home screen.
 * Each probe is independent: a failure in Shizuku must not blank every tile.
 */
class RuntimeMetricsReader(private val shell: ShellEngine) {
    data class Snapshot(val cpu: String, val gpu: String, val temp: String, val fps: String)

    fun read(): Snapshot = Snapshot(
        cpu = readCpu(),
        gpu = readGpu(),
        temp = readTemperature(),
        fps = readFps()
    )

    private fun readCpu(): String {
        val usage = try { sampleProcStat() } catch (_: Throwable) { null }
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
        return listOfNotNull(usage?.let { "$it%" }, prettyFreq.ifBlank { null }).joinToString(" · ").ifBlank { "N/A" }
    }

    private fun sampleProcStat(): Int? {
        fun sample(): Pair<Long, Long>? {
            val line = File("/proc/stat").useLines { seq -> seq.firstOrNull { it.startsWith("cpu ") } } ?: return null
            val nums = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 4) return null
            val total = nums.sum()
            val idle = nums.getOrElse(3) { 0L } + nums.getOrElse(4) { 0L }
            return total to idle
        }
        val a = sample() ?: return null
        Thread.sleep(180)
        val b = sample() ?: return null
        val dt = b.first - a.first
        val di = b.second - a.second
        if (dt <= 0) return null
        return ((dt - di).toDouble() * 100.0 / dt).roundToInt().coerceIn(0, 100)
    }

    private fun readGpu(): String {
        val raw = readFirstUseful(
            "for f in /sys/kernel/ged/hal/current_freqency /sys/kernel/ged/hal/current_frequency /sys/kernel/ged/hal/gpu_freq /sys/kernel/ged/gpu_freq; do [ -r \"\$f\" ] || continue; v=\$(cat \"\$f\" 2>/dev/null | head -n1); [ -n \"\$v\" ] && { echo \"\$v\"; exit 0; }; done; exit 1",
            2200
        ) ?: return "N/A"
        val n = Regex("(\\d{3,})").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return raw.trim().take(24)
        return when {
            n >= 10_000_000L -> "${n / 1_000_000} MHz"
            n >= 10_000L -> "${n / 1_000} MHz"
            else -> "$n MHz"
        }
    }

    private fun readTemperature(): String {
        val raw = readFirstUseful(
            "best=''; for z in /sys/class/thermal/thermal_zone*; do [ -r \"\$z/temp\" ] || continue; t=\$(cat \"\$z/temp\" 2>/dev/null); case \"\$t\" in ''|*[!0-9-]*) continue;; esac; if [ -z \"\$best\" ] || [ \"\$t\" -gt \"\$best\" ]; then best=\$t; fi; done; [ -n \"\$best\" ] && echo \"\$best\"",
            2200
        ) ?: return "N/A"
        val n = raw.trim().lineSequence().firstOrNull()?.toLongOrNull() ?: return "N/A"
        val c = if (n > 1000) n / 1000.0 else n.toDouble()
        return if (c in -20.0..150.0) "${"%.1f".format(java.util.Locale.US, c)}°C" else "N/A"
    }

    private fun readFps(): String {
        val r = runRead("dumpsys SurfaceFlinger --latency 2>/dev/null", 2600)
        if (!r.ok || r.out.isBlank()) return "N/A"
        val stamps: List<Long> = r.out.lineSequence().drop(1).mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            cols.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        }.toList().takeLast(90)
        if (stamps.size < 3) return "N/A"
        val diffs: List<Long> = stamps.zipWithNext { a: Long, b: Long -> b - a }.filter { d -> d in 1_000_000L..100_000_000L }
        if (diffs.isEmpty()) return "N/A"
        val avg = diffs.average()
        val fps = 1_000_000_000.0 / avg
        return if (fps in 1.0..240.0) "${"%.1f".format(java.util.Locale.US, fps)}" else "N/A"
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
        return attempts.firstOrNull { it.ok } ?: attempts.lastOrNull() ?: ShellEngine.Result(127, "", "no probe", ShellEngine.Mode.LOCAL)
    }
}
