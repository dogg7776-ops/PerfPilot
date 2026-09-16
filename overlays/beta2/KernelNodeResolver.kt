package com.oai.perfpilot

import android.os.SystemClock

/**
 * Resolves vendor kernel nodes at runtime. This keeps model-specific path drift out of UI/controller code.
 * It only searches a small allowlisted set of MTK roots and never writes by itself.
 */
class KernelNodeResolver(
    private val shell: ShellEngine,
    private val profile: DeviceProfile
) {
    data class ResolvedNode(val path: String, val source: String)

    private data class CacheEntry(val at: Long, val nodes: List<ResolvedNode>)
    private val cache = mutableMapOf<String, CacheEntry>()
    private val ttlMs = 15_000L

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun mode(): ShellEngine.Mode = if (shell.hasShizukuPermission()) ShellEngine.Mode.SHIZUKU else shell.bestMode()

    private val aliases: Map<String, List<String>> = mapOf(
        "fpsgo_enable" to listOf("fpsgo_enable", "enable"),
        "fpsgo_force_onoff" to listOf("force_onoff", "fpsgo_force_onoff"),
        "cfp_enable" to listOf("cfp_onoff", "cfp_enable"),
        "cfp_polling" to listOf("cfp_polling_ms", "cfp_polling"),
        "cfp_up_loading" to listOf("cfp_up_loading"),
        "cfp_down_loading" to listOf("cfp_down_loading"),
        "cfp_up_time" to listOf("cfp_up_time"),
        "cfp_down_time" to listOf("cfp_down_time"),
        "fstb_gpu_slowdown" to listOf("gpu_slowdown_check"),
        "ged_margin" to listOf("dvfs_margin_value", "dvfs_margin"),
        "ged_timer_margin" to listOf("timer_base_dvfs_margin"),
        "ged_loading_step" to listOf("loading_base_dvfs_step"),
        "ged_loading_mode" to listOf("dvfs_loading_mode"),
        "ged_workload_mode" to listOf("dvfs_workload_mode"),
        "gpu_dcs" to listOf("dcs_mode"),
        "ged_window" to listOf("loading_base_window_size"),
        "ged_stride" to listOf("loading_base_stride_size"),
        "ged_force_loading" to listOf("force_loading_base")
    )

    fun invalidate() = cache.clear()

    fun resolve(param: PerfParam): List<ResolvedNode> {
        val now = SystemClock.elapsedRealtime()
        cache[param.key]?.takeIf { now - it.at < ttlMs }?.let { return it.nodes }

        val out = LinkedHashMap<String, ResolvedNode>()
        param.paths.forEach { out[it] = ResolvedNode(it, "catalog") }

        val names = LinkedHashSet<String>()
        param.paths.mapNotNullTo(names) { it.substringAfterLast('/').takeIf(String::isNotBlank) }
        aliases[param.key].orEmpty().forEach(names::add)

        val roots = when (param.category) {
            "GED GPU" -> profile.gedRoots
            "FPSGO / CFP", "FBT / XGF" -> profile.fpsgoRoots
            else -> emptyList()
        }

        if (names.isNotEmpty() && roots.isNotEmpty()) {
            val nameExpr = names.joinToString(" -o ") { "-name ${q(it)}" }
            val rootExpr = roots.joinToString(" ") { q(it) }
            val cmd = "for r in $rootExpr; do [ -e \"\$r\" ] || continue; find \"\$r\" -maxdepth 5 -type f \\( $nameExpr \\) 2>/dev/null; done | head -n 16"
            val r = shell.exec(cmd, mode(), 4200)
            if (r.ok || r.out.isNotBlank()) {
                r.out.lineSequence().map(String::trim).filter { it.startsWith("/") }.forEach {
                    out[it] = ResolvedNode(it, if (profile.id.startsWith("redmi_k90_max")) "K90 Max probe" else "MTK probe")
                }
            }
        }

        val existing = out.values.filter { node ->
            shell.exec("[ -e ${q(node.path)} ] && echo 1 || echo 0", mode(), 1800).out.trim() == "1"
        }
        cache[param.key] = CacheEntry(now, existing)
        return existing
    }

    fun rootsReport(): String {
        val roots = (profile.fpsgoRoots + profile.gedRoots).distinct()
        val cmd = roots.joinToString("; ") { "[ -e ${q(it)} ] && echo ${q(it)}" }
        return shell.exec(cmd, mode(), 3200).out.trim()
    }
}
