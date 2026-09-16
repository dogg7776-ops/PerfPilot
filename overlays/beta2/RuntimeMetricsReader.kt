package com.oai.perfpilot

import java.io.File
import kotlin.math.roundToInt

/** Read-only runtime telemetry; every probe has its own fallback chain. */
class RuntimeMetricsReader(private val shell: ShellEngine) {
    data class Snapshot(val cpu: String, val gpu: String, val temp: String, val fps: String)

    fun read(): Snapshot = Snapshot(
        cpu = readCpu(),
        gpu = readGpu(),
        temp = readTemperature(),
        fps = readFps()
    )

    private fun readCpu(): String {
        val usage = try { sampleProcStatLocal() } catch (_: Throwable) { null }
            ?: sampleProcStatShell()
        val freq = readFirstUseful(
            "for p in /sys/devices/system/cpu/cpufreq/policy*; do f=\$(cat \"\$p/scaling_cur_freq\" 2>/dev/null); [ -n \"\$f\" ] && printf '%s=%s ' \"\$(basename \"\$p\")\" \"\$f\"; done",
            2600
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
        Thread.sleep(180)
        val b = sample() ?: return null
        return usageFrom(a, b)
    }

    private fun sampleProcStatShell(): Int? {
        val r = runRead("head -n 1 /proc/stat 2>/dev/null; sleep 0.20; head -n 1 /proc/stat 2>/dev/null", 1800)
        if (!r.ok) return null
        val lines = r.out.lineSequence().filter { it.startsWith("cpu ") }.toList()
        if (lines.size < 2) return null
        val a = parseCpuLine(lines[0]) ?: return null
        val b = parseCpuLine(lines[1]) ?: return null
        return usageFrom(a, b)
    }

    private fun readGpu(): String {
        val cmd = "for f in /sys/kernel/ged/hal/current_freqency /sys/kernel/ged/hal/current_frequency /sys/kernel/ged/hal/gpu_freq /sys/kernel/ged/gpu_freq; do [ -r \"\$f\" ] || continue; v=\$(cat \"\$f\" 2>/dev/null | head -n1); [ -n \"\$v\" ] && { echo \"VALUE=\$v\"; exit 0; }; done; " +
            "for d in /sys/class/devfreq/*; do [ -d \"\$d\" ] || continue; n=\$(cat \"\$d/name\" 2>/dev/null); b=\$(basename \"\$d\"); case \"\$n \$b\" in *gpu*|*GPU*|*mali*|*MALI*) v=\$(cat \"\$d/cur_freq\" 2>/dev/null); [ -n \"\$v\" ] && { echo \"VALUE=\$v\"; exit 0; };; esac; done; " +
            "for f in /proc/gpufreqv2/gpufreq_status /proc/gpufreq/gpufreq_var_dump; do [ -r \"\$f\" ] || continue; line=\$(grep -Ei 'cur.*gpu|gpu.*cur|cur.*freq|freq.*cur|fgpu' \"\$f\" 2>/dev/null | head -n1); [ -n \"\$line\" ] && { echo \"STATUS=\$line\"; exit 0; }; done; exit 1"
        val raw = readFirstUseful(cmd, 3000) ?: return "N/A"
        val preferred = Regex("(?i)(?:fgpu|gpu[^0-9]{0,20}freq|cur[^0-9]{0,20}freq)[^0-9]{0,20}([0-9]{3,10})").find(raw)?.groupValues?.getOrNull(1)
        val n = (preferred ?: Regex("([0-9]{3,10})").find(raw)?.groupValues?.getOrNull(1))?.toLongOrNull()
            ?: return raw.substringAfter('=').trim().take(24).ifBlank { "N/A" }
        val mhz = when {
            n >= 100_000_000L -> n / 1_000_000.0
            n >= 100_000L -> n / 1_000.0
            else -> n.toDouble()
        }
        return if (mhz in 1.0..5000.0) "${"%.0f".format(java.util.Locale.US, mhz)} MHz" else "N/A"
    }

    private fun readTemperature(): String {
        val thermal = runRead("dumpsys thermalservice 2>/dev/null", 3000)
        if (thermal.ok && thermal.out.isNotBlank()) {
            val values = Regex("(?i)(?:mValue|value)\\s*=\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                .findAll(thermal.out)
                .mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() }
                .filter { it in 10.0..125.0 }
                .toList()
            if (values.isNotEmpty()) return "${"%.1f".format(java.util.Locale.US, values.maxOrNull()!!)}°C"
        }

        val raw = readFirstUseful(
            "best=''; for z in /sys/class/thermal/thermal_zone*; do [ -r \"\$z/temp\" ] || continue; t=\$(cat \"\$z/temp\" 2>/dev/null); case \"\$t\" in ''|*[!0-9-]*) continue;; esac; if [ -z \"\$best\" ] || [ \"\$t\" -gt \"\$best\" ]; then best=\$t; fi; done; [ -n \"\$best\" ] && echo \"\$best\"",
            2600
        ) ?: return "N/A"
        val n = raw.trim().lineSequence().firstOrNull()?.toLongOrNull() ?: return "N/A"
        val c = if (kotlin.math.abs(n) > 1000) n / 1000.0 else n.toDouble()
        return if (c in -20.0..150.0) "${"%.1f".format(java.util.Locale.US, c)}°C" else "N/A"
    }

    private fun readFps(): String {
        val cmd = "pkg=\$(dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n1 | sed -n 's/.* \\([A-Za-z0-9._]*\\)\\/.*/\\1/p'); " +
            "layer=''; [ -n \"\$pkg\" ] && layer=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F \"\$pkg\" | head -n1); " +
            "if [ -n \"\$layer\" ]; then dumpsys SurfaceFlinger --latency \"\$layer\" 2>/dev/null; else dumpsys SurfaceFlinger --latency 2>/dev/null; fi"
        val r = runRead(cmd, 3400)
        if (!r.ok || r.out.isBlank()) return "N/A"
        val stamps: List<Long> = r.out.lineSequence().drop(1).mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            cols.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        }.toList().takeLast(120)
        if (stamps.size < 3) return "N/A"
        val diffs = stamps.zipWithNext { a: Long, b: Long -> b - a }
            .filter { d -> d in 1_000_000L..100_000_000L }
        if (diffs.size < 2) return "N/A"
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
        return attempts.firstOrNull { it.ok } ?: attempts.lastOrNull()
            ?: ShellEngine.Result(127, "", "no probe", ShellEngine.Mode.LOCAL)
    }
}
