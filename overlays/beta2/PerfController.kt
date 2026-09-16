package com.oai.perfpilot

import android.content.Context
import java.util.Locale

/**
 * High-level facade. Device detection, node resolution and MTK binder calls live in separate classes;
 * this class only coordinates them and keeps backup/restore policy in one place.
 */
class PerfController(private val context: Context, private val shell: ShellEngine) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val applied = context.getSharedPreferences("applied_values", Context.MODE_PRIVATE)
    private val perfManager = PerfManagerClient(context, shell)

    private val identity: DeviceIdentity by lazy { readIdentity() }
    private val profile: DeviceProfile by lazy { DeviceProfiles.resolve(identity) }
    private val nodes: KernelNodeResolver by lazy { KernelNodeResolver(shell, profile) }

    data class DeviceSnapshot(
        val identity: DeviceIdentity,
        val profile: DeviceProfile,
        val summary: String,
        val mtkFpsgo: Boolean,
        val ged: Boolean,
        val writableHint: String
    )

    data class LiveMetrics(val cpu: String, val gpu: String, val temp: String, val fps: String, val foregroundPackage: String) {
        fun asOverlayText(): String = "CPU  $cpu\nGPU  $gpu\nTEMP $temp\nFPS  $fps" + if (foregroundPackage.isBlank()) "" else "\n$foregroundPackage"
    }

    data class ParamRead(val ok: Boolean, val value: String?, val source: String, val detail: String, val path: String? = null)
    data class ParamWrite(val ok: Boolean, val source: String, val detail: String, val handle: Int? = null)
    data class Diagnostic(val name: String, val state: State, val detail: String) { enum class State { OK, WARN, FAIL } }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun privilegedMode(): ShellEngine.Mode = if (shell.hasShizukuPermission()) ShellEngine.Mode.SHIZUKU else shell.bestMode()

    private fun readIdentity(): DeviceIdentity {
        val d = '$'
        val cmd = """
            echo MODEL=${d}(getprop ro.product.model)
            echo PRODUCT=${d}(getprop ro.product.device)
            echo SOC=${d}(getprop ro.soc.model)
            echo HARDWARE=${d}(getprop ro.hardware)
            echo PLATFORM=${d}(getprop ro.board.platform)
            echo SDK=${d}(getprop ro.build.version.sdk)
            echo RELEASE=${d}(getprop ro.build.version.release)
            echo ROM=${d}(getprop ro.mi.os.version.name)
        """.trimIndent()
        val r = shell.exec(cmd, privilegedMode(), 3000)
        val map = r.out.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0) line.substring(0, i) to line.substring(i + 1).trim() else null
        }.toMap()
        return DeviceIdentity(
            model = map["MODEL"].orEmpty(),
            product = map["PRODUCT"].orEmpty(),
            soc = map["SOC"].orEmpty(),
            hardware = map["HARDWARE"].orEmpty(),
            boardPlatform = map["PLATFORM"].orEmpty(),
            sdk = map["SDK"]?.toIntOrNull() ?: 0,
            release = map["RELEASE"].orEmpty(),
            romVersion = map["ROM"].orEmpty()
        )
    }

    fun deviceIdentity(): DeviceIdentity = identity
    fun deviceProfile(): DeviceProfile = profile
    fun supportedRefreshRates(): List<Int> = profile.refreshRates

    fun snapshot(): DeviceSnapshot {
        val rootReport = nodes.rootsReport()
        val summary = buildString {
            append(profile.title).append('\n')
            append("设备：").append(identity.model.ifBlank { "未知" }).append('\n')
            append("SoC：").append(identity.soc.ifBlank { identity.boardPlatform.ifBlank { "未知" } }).append('\n')
            if (identity.romVersion.isNotBlank()) append("系统：").append(identity.romVersion).append(" / Android ").append(identity.release).append('\n')
            append("适配：").append(profile.notes)
        }
        return DeviceSnapshot(
            identity = identity,
            profile = profile,
            summary = summary,
            mtkFpsgo = rootReport.lineSequence().any { it.contains("fpsgo", true) },
            ged = rootReport.lineSequence().any { it.contains("ged", true) },
            writableHint = when (privilegedMode()) {
                ShellEngine.Mode.SHIZUKU -> "Shizuku UID 2000：PowerHAL 优先，节点按实际权限验证"
                ShellEngine.Mode.ROOT -> "Root 回退模式"
                ShellEngine.Mode.LOCAL -> "普通模式：只开放无需特权的功能"
            }
        )
    }

    fun liveMetrics(): LiveMetrics {
        val d = '$'
        val cmd = """
            cpu=""; for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -r "${d}p/scaling_cur_freq" ] || continue; cpu="${d}cpu${d}(basename ${d}p):${d}(cat ${d}p/scaling_cur_freq 2>/dev/null) "; done
            gpu="N/A"; for f in /sys/kernel/ged/hal/current_freqency /sys/kernel/ged/hal/gpu_freq /sys/kernel/ged/gpu_freq; do [ -r "${d}f" ] && gpu=${d}(cat "${d}f" 2>/dev/null) && break; done
            temp="N/A"; for z in /sys/class/thermal/thermal_zone*; do [ -r "${d}z/temp" ] || continue; t=${d}(cat "${d}z/temp" 2>/dev/null); [ -n "${d}t" ] && temp="${d}t" && break; done
            fps="N/A"; sf=${d}(dumpsys SurfaceFlinger --latency 2>/dev/null | head -n 1); [ -n "${d}sf" ] && fps="SF"
            pkg=${d}(dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n1 | sed -n 's/.* \([A-Za-z0-9._]*\)\/.*/\1/p')
            echo CPU=${d}cpu; echo GPU=${d}gpu; echo TEMP=${d}temp; echo FPS=${d}fps; echo PKG=${d}pkg
        """.trimIndent()
        val r = shell.exec(cmd, privilegedMode(), 4500)
        val map = r.out.lineSequence().mapNotNull { line -> val i = line.indexOf('='); if (i > 0) line.substring(0, i) to line.substring(i + 1) else null }.toMap()
        return LiveMetrics(
            map["CPU"]?.trim().orEmpty().ifBlank { "N/A" },
            map["GPU"].orEmpty().ifBlank { "N/A" },
            formatTemp(map["TEMP"]),
            map["FPS"].orEmpty().ifBlank { "N/A" },
            map["PKG"].orEmpty()
        )
    }

    private fun formatTemp(raw: String?): String {
        val n = raw?.trim()?.toLongOrNull() ?: return raw.orEmpty().ifBlank { "N/A" }
        return if (n > 1000) String.format(Locale.US, "%.1f°C", n / 1000.0) else "$n°C"
    }

    fun diagnostics(): List<Diagnostic> {
        val list = mutableListOf<Diagnostic>()
        val exactK90 = profile.id == DeviceProfiles.K90_MAX_DIMENSITY_9500.id
        list += Diagnostic(
            "目标机识别",
            if (exactK90) Diagnostic.State.OK else Diagnostic.State.WARN,
            "${identity.compact.ifBlank { "属性读取失败" }}\n匹配配置：${profile.title}\nplatform=${identity.boardPlatform.ifBlank { "N/A" }}"
        )

        val binder = shell.hasShizukuBinder()
        val perm = shell.hasShizukuPermission()
        list += Diagnostic("Shizuku Binder", if (binder) Diagnostic.State.OK else Diagnostic.State.FAIL, if (binder) "服务已运行" else "未连接到 Shizuku")
        list += Diagnostic("Shizuku 授权", if (perm) Diagnostic.State.OK else Diagnostic.State.FAIL, if (perm) "应用已授权" else "请在 Shizuku 中授权 PerfPilot")
        if (perm) {
            val id = shell.exec("id", ShellEngine.Mode.SHIZUKU)
            list += Diagnostic("特权 Shell UID", if (id.ok && id.out.contains("uid=2000")) Diagnostic.State.OK else Diagnostic.State.WARN, (id.out + id.err).trim().ifBlank { "无法读取 id" })
        }

        list += Diagnostic(
            "MTK perfmanager / PowerHAL",
            if (perfManager.isAvailable()) Diagnostic.State.OK else Diagnostic.State.WARN,
            perfManager.describeService().ifBlank { "未检测到服务；会退回节点探测" }
        )

        val joy = shell.exec("service check xiaomi.joyose 2>&1", privilegedMode())
        list += Diagnostic("Xiaomi Joyose", if (joy.out.contains("found", true) || joy.out.contains("IBinder", true)) Diagnostic.State.OK else Diagnostic.State.WARN, joy.out.trim().ifBlank { "服务未发现" })

        val roots = nodes.rootsReport()
        list += Diagnostic("K90 Max MTK 节点根目录", if (roots.isNotBlank()) Diagnostic.State.OK else Diagnostic.State.WARN, roots.ifBlank { "FPSGO/GED 根目录未暴露给 shell；优先使用 PowerHAL" })

        val topology = shell.exec(
            "for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d \"\$p\" ] || continue; echo \"$(basename \"\$p\") cpus=$(cat \"\$p/related_cpus\" 2>/dev/null) max=$(cat \"\$p/cpuinfo_max_freq\" 2>/dev/null) gov=$(cat \"\$p/scaling_governor\" 2>/dev/null)\"; done",
            privilegedMode(), 3500
        )
        list += Diagnostic("CPUFreq 拓扑", if (topology.out.isNotBlank()) Diagnostic.State.OK else Diagnostic.State.WARN, topology.out.trim().ifBlank { topology.err.trim().ifBlank { "无法读取 policy 拓扑" } })

        val refresh = shell.exec("settings get system peak_refresh_rate; settings get secure user_refresh_rate; settings get secure miui_refresh_rate", privilegedMode(), 2200)
        list += Diagnostic("刷新率配置", Diagnostic.State.OK, "目标档位=${profile.refreshRates.joinToString("/")} Hz\n当前设置：${refresh.out.trim().ifBlank { "N/A" }}")

        val root = shell.hasRoot()
        list += Diagnostic("Root（仅回退）", if (root) Diagnostic.State.OK else Diagnostic.State.WARN, if (root) "可用；仍优先 Shizuku/PowerHAL" else "未获取；正常，K90 Max 适配以无 Root 为主")
        return list
    }

    private fun readNode(path: String): ParamRead {
        val modes = buildList {
            if (shell.hasShizukuPermission()) add(ShellEngine.Mode.SHIZUKU)
            if (shell.hasRoot()) add(ShellEngine.Mode.ROOT)
            if (isEmpty()) add(ShellEngine.Mode.LOCAL)
        }
        val errors = mutableListOf<String>()
        for (m in modes) {
            val r = shell.exec("if [ ! -e ${q(path)} ]; then exit 2; fi; cat ${q(path)}", m, 3200)
            if (r.ok && r.out.isNotBlank()) return ParamRead(true, r.out.trim().lineSequence().firstOrNull(), "$m/sysfs", "读取成功", path)
            errors += "$m exit=${r.code}: ${(r.err.ifBlank { r.out }).trim().ifBlank { "无返回" }}"
        }
        return ParamRead(false, null, "sysfs", errors.joinToString(" | "), path)
    }

    fun readParam(param: PerfParam): ParamRead {
        val resolved = nodes.resolve(param)
        val failures = mutableListOf<String>()
        for (node in resolved) {
            val r = readNode(node.path)
            if (r.ok) return r.copy(source = "${r.source} · ${node.source}")
            failures += "${node.path}: ${r.detail}"
        }

        val saved = applied.getString(param.key, null)
        if (param.resourceId != null && perfManager.isAvailable()) {
            return ParamRead(
                ok = saved != null,
                value = saved,
                source = "MTK PowerHAL",
                detail = if (saved != null) "厂商资源通常没有通用读取事务；显示 PerfPilot 最近一次已提交值" else "资源通道可用，但没有安全的通用读取接口。写入时会要求返回有效 handle。",
                path = null
            )
        }
        return ParamRead(
            ok = false,
            value = saved,
            source = if (saved != null) "缓存" else "不可用",
            detail = failures.take(3).joinToString("\n").ifBlank { "K90 Max 候选节点未发现，且 perfmanager/PowerHAL 当前不可用" },
            path = null
        )
    }

    fun writeParam(param: PerfParam, value: Int): ParamWrite {
        if (!param.writable) return ParamWrite(false, "安全策略", "此项目只读。PerfPilot 不修改热保护阈值。")
        if (param.min != null && value < param.min) return ParamWrite(false, "校验", "$value 小于最小值 ${param.min}")
        if (param.max != null && value > param.max) return ParamWrite(false, "校验", "$value 大于最大值 ${param.max}")

        // K90 Max/MTK: vendor service first. It is less dependent on sysfs SELinux visibility.
        if (param.resourceId != null && perfManager.isAvailable()) {
            val vendor = perfManager.apply(param.key, param.resourceId, value)
            if (vendor.ok) {
                applied.edit().putString(param.key, value.toString()).apply()
                return ParamWrite(true, "MTK perfmanager", vendor.detail, vendor.handle)
            }
        }

        val failures = mutableListOf<String>()
        for (node in nodes.resolve(param)) {
            backupFile(node.path)
            val modes = buildList {
                if (shell.hasShizukuPermission()) add(ShellEngine.Mode.SHIZUKU)
                if (shell.hasRoot()) add(ShellEngine.Mode.ROOT)
            }
            for (m in modes) {
                val wr = shell.exec("echo ${q(value.toString())} > ${q(node.path)}", m, 3200)
                if (!wr.ok) {
                    failures += "$m ${node.path}: ${wr.err.trim().ifBlank { "exit=${wr.code}" }}"
                    continue
                }
                val verify = readNode(node.path)
                if (verify.ok && verify.value == value.toString()) {
                    applied.edit().putString(param.key, value.toString()).apply()
                    return ParamWrite(true, "$m/sysfs", "写入并回读验证成功：${node.path}")
                }
                failures += "$m ${node.path}: 命令成功，但回读未确认生效"
            }
        }

        return ParamWrite(false, "无可用通道", failures.take(3).joinToString("\n").ifBlank { "PowerHAL 未接受资源，且没有可验证写入的 K90 Max 节点。" })
    }

    private fun backupSetting(namespace: String, key: String) {
        val prefKey = "setting_${namespace}_$key"
        if (prefs.contains(prefKey)) return
        val r = shell.exec("settings get $namespace ${q(key)}", privilegedMode())
        if (r.ok) prefs.edit().putString(prefKey, r.out.trim()).apply()
    }

    private fun putSetting(namespace: String, key: String, value: String): ShellEngine.Result {
        backupSetting(namespace, key)
        return shell.exec("settings put $namespace ${q(key)} ${q(value)}", privilegedMode())
    }

    fun setRefreshRate(hz: Int): String {
        if (hz <= 0) return "刷新率：保持当前设置"
        if (hz !in profile.refreshRates) return "刷新率：$hz Hz 不在 ${profile.title} 的目标档位 ${profile.refreshRates.joinToString("/")} 中，拒绝写入。"
        return listOf(
            putSetting("system", "peak_refresh_rate", hz.toString()),
            putSetting("secure", "user_refresh_rate", hz.toString()),
            putSetting("secure", "miui_refresh_rate", hz.toString())
        ).joinToString("\n\n") { it.pretty() }
    }

    fun setAnimationScale(scale: Float): String {
        val v = String.format(Locale.US, "%.2f", scale)
        return listOf(
            putSetting("global", "window_animation_scale", v),
            putSetting("global", "transition_animation_scale", v),
            putSetting("global", "animator_duration_scale", v)
        ).joinToString("\n\n") { it.pretty() }
    }

    fun restoreSettings(): String {
        val all = prefs.all.filterKeys { it.startsWith("setting_") }
        if (all.isEmpty()) return "没有已备份的系统设置。"
        val lines = mutableListOf<String>()
        for ((k, vv) in all) {
            val rest = k.removePrefix("setting_")
            val i = rest.indexOf('_')
            if (i <= 0) continue
            val ns = rest.substring(0, i)
            val key = rest.substring(i + 1)
            val old = vv as? String ?: continue
            val cmd = if (old == "null" || old.isBlank()) "settings delete $ns ${q(key)}" else "settings put $ns ${q(key)} ${q(old)}"
            lines += shell.exec(cmd, privilegedMode()).pretty()
        }
        prefs.edit().also { e -> all.keys.forEach(e::remove) }.apply()
        return lines.joinToString("\n\n")
    }

    private fun backupFile(path: String) {
        val k = "file_$path"
        if (prefs.contains(k)) return
        val r = shell.exec("cat ${q(path)} 2>/dev/null", privilegedMode())
        if (r.ok && r.out.isNotBlank()) prefs.edit().putString(k, r.out.trim()).apply()
    }

    fun restoreKernelNodes(): String {
        val all = prefs.all.filterKeys { it.startsWith("file_") }
        if (all.isEmpty()) return "没有已备份的内核参数。"
        val rs = mutableListOf<String>()
        for ((k, vv) in all) {
            val path = k.removePrefix("file_")
            val old = vv as? String ?: continue
            var result: ShellEngine.Result? = null
            if (shell.hasShizukuPermission()) result = shell.exec("echo ${q(old)} > ${q(path)}", ShellEngine.Mode.SHIZUKU)
            if ((result == null || !result.ok) && shell.hasRoot()) result = shell.exec("echo ${q(old)} > ${q(path)}", ShellEngine.Mode.ROOT)
            rs += result?.pretty() ?: "无法恢复 $path"
        }
        prefs.edit().also { e -> all.keys.forEach(e::remove) }.apply()
        nodes.invalidate()
        return rs.joinToString("\n\n")
    }

    fun setCpuGovernor(governor: String): String {
        if (governor == "unchanged") return "CPU governor：保持当前设置"
        val d = '$'
        val cmd = """rc=0; for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d "${d}p" ] || continue; g="${d}p/scaling_governor"; av=${d}(cat "${d}p/scaling_available_governors" 2>/dev/null); case " ${d}av " in *" $governor "*) echo $governor > "${d}g" || rc=1 ;; *) echo "skip ${d}(basename ${d}p): $governor unavailable" ;; esac; done; exit ${d}rc"""
        if (shell.hasShizukuPermission()) {
            val r = shell.exec(cmd, ShellEngine.Mode.SHIZUKU)
            if (r.ok) return r.pretty()
        }
        if (shell.hasRoot()) return shell.exec(cmd, ShellEngine.Mode.ROOT).pretty()
        return "CPU governor：K90 Max 的 shell 权限不能直接写 cpufreq；请优先使用 PowerHAL/FPSGO 策略。"
    }

    fun enableMtkFpsgo(): String {
        val primary = PerfCatalog.find("fpsgo_enable") ?: return "参数表缺失"
        val a = writeParam(primary, 1)
        val b = PerfCatalog.find("fpsgo_force_onoff")?.let { writeParam(it, 1) }
        return "[${a.source}] ${a.detail}" + if (b != null) "\n[${b.source}] ${b.detail}" else ""
    }

    fun setPowerHalGameMode(enabled: Boolean): String {
        val p = PerfCatalog.find("game_mode") ?: return "游戏模式资源缺失"
        val r = writeParam(p, if (enabled) 1 else 0)
        return "[${r.source}] ${r.detail}"
    }

    /** Kept for compatibility, but intentionally not surfaced as a recommended K90 Max action. */
    fun disableXiaomiCloudOverrides(): String = "K90 Max 稳定性模式：不建议禁用 Joyose / PowerKeeper 云控组件，此操作已停用。"
    fun restoreXiaomiCloudOverrides(): String = "无需恢复：1.0 beta2 不再修改 Joyose / PowerKeeper 组件启用状态。"

    fun safeGameProfile(): String = buildString {
        val targetHz = if (165 in profile.refreshRates) 165 else profile.refreshRates.maxOrNull() ?: 120
        append("刷新率：\n").append(setRefreshRate(targetHz))
        append("\n\nPowerHAL 游戏模式：\n").append(setPowerHalGameMode(true))
        append("\n\nFPSGO：\n").append(enableMtkFpsgo())
    }

    fun applyGameProfile(game: GameProfile): String = buildString {
        append("应用：${game.label}\n")
        if (game.refreshRate > 0) append("\n刷新率：\n${setRefreshRate(game.refreshRate)}\n")
        if (game.enableFpsgo) append("\nFPSGO：\n${enableMtkFpsgo()}\n")
        if (game.cpuGovernor != "unchanged") append("\nCPU：\n${setCpuGovernor(game.cpuGovernor)}\n")
        append("\nPowerHAL 游戏模式：\n${setPowerHalGameMode(true)}")
    }

    fun restoreAll(): String = listOf(restoreSettings(), restoreKernelNodes(), perfManager.releaseAll()).joinToString("\n\n")
}
