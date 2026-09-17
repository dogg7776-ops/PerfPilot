package com.oai.perfpilot

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Refresh-rate controller that prefers Android's DisplayManager shell API and
 * keeps Xiaomi/HyperOS settings as a compatibility fallback. Every write is
 * backed up and followed by readback. No thermal limiter is bypassed here.
 */
class DisplayController(context: Context, private val shell: ShellEngine) {
    data class ApplyResult(
        val ok: Boolean,
        val requestedHz: Int,
        val activeHz: Float?,
        val supported: List<Int>,
        val detail: String
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("display_controller_backup", Context.MODE_PRIVATE)

    private fun mode(): ShellEngine.Mode = when {
        shell.hasShizukuPermission() -> ShellEngine.Mode.SHIZUKU
        shell.hasRoot() -> ShellEngine.Mode.ROOT
        else -> ShellEngine.Mode.LOCAL
    }

    private fun display(): Display? = try {
        appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
    } catch (_: Throwable) { null }

    fun supportedRates(): List<Int> = try {
        display()?.supportedModes.orEmpty()
            .map { it.refreshRate.roundToInt() }
            .filter { it in 30..300 }
            .distinct()
            .sorted()
    } catch (_: Throwable) { emptyList() }

    fun currentRefreshRate(): Float? = try { display()?.refreshRate?.takeIf { it > 0f } } catch (_: Throwable) { null }

    private fun physicalSize(): Pair<Int, Int>? {
        val d = display() ?: return null
        val m = d.mode
        if (m.physicalWidth > 0 && m.physicalHeight > 0) return m.physicalWidth to m.physicalHeight
        val r = shell.exec("wm size", mode(), 1800)
        val hit = Regex("(\\d{3,5})x(\\d{3,5})").find(r.out + "\n" + r.err) ?: return null
        return hit.groupValues[1].toIntOrNull()?.let { w -> hit.groupValues[2].toIntOrNull()?.let { h -> w to h } }
    }

    private fun backupSetting(namespace: String, key: String) {
        val prefKey = "setting_${namespace}_$key"
        if (prefs.contains(prefKey)) return
        val r = shell.exec("settings get $namespace '$key'", mode(), 1800)
        if (r.ok) prefs.edit().putString(prefKey, r.out.trim()).apply()
    }

    private fun put(namespace: String, key: String, value: String): ShellEngine.Result {
        backupSetting(namespace, key)
        return shell.exec("settings put $namespace '$key' '$value'", mode(), 2200)
    }

    private fun backupPreferredMode() {
        if (prefs.contains("preferred_mode")) return
        val r = shell.exec("cmd display get-user-preferred-display-mode 0 2>&1", mode(), 2200)
        prefs.edit().putString("preferred_mode", (r.out + r.err).trim()).apply()
    }

    fun apply(hz: Int): ApplyResult {
        val supported = supportedRates()
        if (hz !in 30..300) return ApplyResult(false, hz, currentRefreshRate(), supported, "刷新率数值非法：$hz")
        if (supported.isNotEmpty() && supported.none { abs(it - hz) <= 1 }) {
            return ApplyResult(false, hz, currentRefreshRate(), supported, "屏幕没有报告 $hz Hz 模式；可用：${supported.joinToString("/")} Hz")
        }
        if (mode() == ShellEngine.Mode.LOCAL) {
            return ApplyResult(false, hz, currentRefreshRate(), supported, "需要 Shizuku Shell 或 Root 才能修改刷新率")
        }

        backupPreferredMode()
        listOf(
            "system" to "peak_refresh_rate",
            "system" to "min_refresh_rate",
            "secure" to "miui_refresh_rate",
            "secure" to "user_refresh_rate"
        ).forEach { backupSetting(it.first, it.second) }

        val notes = mutableListOf<String>()
        val size = physicalSize()
        if (size != null) {
            val (w, h) = size
            var cmd = shell.exec("cmd display set-user-preferred-display-mode $w $h $hz 0 2>&1", mode(), 2600)
            if (!cmd.ok) cmd = shell.exec("cmd display set-user-preferred-display-mode $w $h $hz 2>&1", mode(), 2600)
            notes += "DisplayManager: ${if (cmd.ok) "已提交" else "失败 exit=${cmd.code}"}${if (cmd.err.isNotBlank()) " · ${cmd.err.trim().take(140)}" else ""}"
        } else {
            notes += "DisplayManager: 未读取到物理分辨率"
        }

        val writes = listOf(
            put("secure", "miui_refresh_rate", hz.toString()),
            put("secure", "user_refresh_rate", hz.toString()),
            put("system", "peak_refresh_rate", hz.toString()),
            put("system", "min_refresh_rate", "0")
        )
        notes += "HyperOS settings: ${writes.count { it.ok }}/${writes.size} 写入成功"

        shell.exec("sleep 0.35", mode(), 900)
        val preferred = shell.exec("cmd display get-user-preferred-display-mode 0 2>&1", mode(), 2200)
        val settings = shell.exec(
            "printf 'miui='; settings get secure miui_refresh_rate; printf 'user='; settings get secure user_refresh_rate; printf 'peak='; settings get system peak_refresh_rate; printf 'min='; settings get system min_refresh_rate",
            mode(), 2200
        )
        val active = currentRefreshRate()
        val preferredMentions = Regex("(^|[^0-9])${hz}(?:\\.0+)?([^0-9]|$)").containsMatchIn(preferred.out + preferred.err)
        val settingsMentions = settings.out.lineSequence().any { it.substringAfter('=', "").trim().toFloatOrNull()?.let { v -> abs(v - hz) < 1f } == true }
        val activeMatches = active?.let { abs(it - hz) < 1.5f } == true
        val ok = preferredMentions || settingsMentions || activeMatches

        notes += "首选模式回读：${(preferred.out + preferred.err).trim().ifBlank { "无返回" }.take(220)}"
        notes += "设置回读：${settings.out.trim().replace('\n', ' ').take(220)}"
        notes += "当前物理刷新率：${active?.let { "%.1f Hz".format(java.util.Locale.US, it) } ?: "未知"}"
        if (ok && !activeMatches) notes += "系统已接受刷新率策略，但当前画面仍可能因应用策略/息屏/省电/温控而暂时运行在其他档位。"

        return ApplyResult(ok, hz, active, supported, notes.joinToString("\n"))
    }

    fun restore(): String {
        val lines = mutableListOf<String>()
        val preferred = prefs.getString("preferred_mode", null)
        val preferredNums = preferred?.let { Regex("(\\d{3,5})\\s*[x, ]\\s*(\\d{3,5}).*?([0-9]{2,3}(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE).find(it) }
        if (preferredNums != null) {
            val w = preferredNums.groupValues[1]
            val h = preferredNums.groupValues[2]
            val hz = preferredNums.groupValues[3]
            lines += shell.exec("cmd display set-user-preferred-display-mode $w $h $hz 0 2>&1", mode(), 2600).pretty()
        } else {
            lines += shell.exec("cmd display clear-user-preferred-display-mode 0 2>&1", mode(), 2200).pretty()
        }

        val all = prefs.all.filterKeys { it.startsWith("setting_") }
        for ((k, v) in all) {
            val rest = k.removePrefix("setting_")
            val split = rest.indexOf('_')
            if (split <= 0) continue
            val ns = rest.substring(0, split)
            val key = rest.substring(split + 1)
            val old = v as? String ?: continue
            val cmd = if (old == "null" || old.isBlank()) "settings delete $ns '$key'" else "settings put $ns '$key' '$old'"
            lines += shell.exec(cmd, mode(), 2200).pretty()
        }
        prefs.edit().clear().apply()
        return lines.joinToString("\n\n")
    }

    fun describe(): String = buildString {
        append("支持档位：").append(supportedRates().joinToString("/").ifBlank { "未读取" }).append(" Hz\n")
        append("当前：").append(currentRefreshRate()?.let { "%.1f Hz".format(java.util.Locale.US, it) } ?: "未知")
    }
}
