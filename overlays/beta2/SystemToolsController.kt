package com.oai.perfpilot

import android.content.Context

/**
 * Reimplements useful system-side tools observed in the original APK using
 * reversible operations. Thermal protection disabling is intentionally omitted.
 */
class SystemToolsController(context: Context, private val shell: ShellEngine) {
    private val prefs = context.applicationContext.getSharedPreferences("system_tools_backup", Context.MODE_PRIVATE)

    private fun mode(): ShellEngine.Mode = when {
        shell.hasShizukuPermission() -> ShellEngine.Mode.SHIZUKU
        shell.hasRoot() -> ShellEngine.Mode.ROOT
        else -> ShellEngine.Mode.LOCAL
    }

    private val cloudComponents = listOf(
        "com.miui.powerkeeper/com.miui.powerkeeper.cloudcontrol.CloudUpdateReceiver",
        "com.miui.powerkeeper/com.miui.powerkeeper.cloudcontrol.CloudUpdateJobService",
        "com.xiaomi.joyose/com.xiaomi.joyose.cloud.CloudServerReceiver",
        "com.xiaomi.joyose/com.xiaomi.joyose.JoyoseJobScheduleService",
        "com.xiaomi.joyose/com.xiaomi.joyose.smartop.smartp.SmartPAlarmReceiver",
        "com.xiaomi.joyose/com.xiaomi.joyose.smartop.gamebooster.receiver.BoostRequestReceiver",
        "com.xiaomi.joyose/com.xiaomi.joyose.smartop.provider.GameInfoProvider",
        "com.xiaomi.joyose/com.xiaomi.joyose.gameInfo.GameInfoService"
    )

    fun cloudControlStatus(): String {
        val lines = mutableListOf<String>()
        cloudComponents.forEach { c ->
            val r = shell.exec("cmd package get-component-enabled-setting '$c' 2>&1", mode(), 1800)
            lines += "$c -> ${(r.out + r.err).trim().ifBlank { "unknown" }}"
        }
        return lines.joinToString("\n")
    }

    fun setCloudControlDisabled(disabled: Boolean): String {
        if (mode() == ShellEngine.Mode.LOCAL) return "需要 Shizuku Shell 或 Root。"
        var ok = 0
        var fail = 0
        val detail = mutableListOf<String>()
        cloudComponents.forEach { c ->
            val cmd = if (disabled) "pm disable-user --user 0 '$c' 2>&1" else "pm enable --user 0 '$c' 2>&1"
            var r = shell.exec(cmd, mode(), 2600)
            if (!disabled && !r.ok) r = shell.exec("pm enable '$c' 2>&1", mode(), 2600)
            val good = r.ok || r.out.contains("new state", true) || r.out.contains("enabled", true) || r.out.contains("disabled", true)
            if (good) ok++ else fail++
            detail += "${if (good) "✓" else "!"} $c ${(r.out + r.err).trim().take(120)}"
        }
        return "云控组件${if (disabled) "禁用" else "恢复"}：成功 $ok，失败 $fail\n${detail.joinToString("\n")}"
    }

    /** Safer replacement for the original APK's very broad memory-property script. */
    fun setBackgroundProtection(packageName: String, enabled: Boolean): String {
        if (packageName.isBlank() || !Regex("[A-Za-z0-9_.]+").matches(packageName)) return "包名无效。"
        if (mode() == ShellEngine.Mode.LOCAL) return "需要 Shizuku Shell 或 Root。"
        val key = "MILLET_NO_RESTRICT_APP"
        if (!prefs.contains("millet_old")) {
            val old = shell.exec("settings get system $key", mode(), 1800)
            if (old.ok) prefs.edit().putString("millet_old", old.out.trim()).apply()
        }
        val current = shell.exec("settings get system $key", mode(), 1800).out.trim().takeIf { it != "null" }.orEmpty()
        val set = current.split(',', ';', ':', ' ').map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
        if (enabled) set += packageName else set -= packageName
        val value = set.joinToString(",")
        val cmd = if (value.isBlank()) "settings delete system $key" else "settings put system $key '$value'"
        val r = shell.exec(cmd, mode(), 2200)
        return "${if (r.ok) "已更新" else "更新失败"}后台不限制列表：$value\n${r.pretty()}"
    }

    fun restoreBackgroundProtection(): String {
        val old = prefs.getString("millet_old", null) ?: return "没有后台保活备份。"
        val cmd = if (old == "null" || old.isBlank()) "settings delete system MILLET_NO_RESTRICT_APP" else "settings put system MILLET_NO_RESTRICT_APP '$old'"
        val r = shell.exec(cmd, mode(), 2200)
        if (r.ok) prefs.edit().remove("millet_old").apply()
        return r.pretty()
    }

    fun setHapticBoost(enabled: Boolean): String {
        if (mode() == ShellEngine.Mode.LOCAL) return "需要 Shizuku Shell 或 Root。"
        val props = linkedMapOf(
            "sys.haptic.dynamiceffect" to "true",
            "sys.haptic.dynamiceffect.richtap" to "true",
            "sys.haptic.infinitelevel" to "true",
            "sys.haptic.intensityforkeyboard" to "true",
            "sys.haptic.media" to "true",
            "sys.haptic.motor" to "linear",
            "sys.haptic.tap.normal" to "1,2",
            "sys.haptic.tap.light" to "1,2",
            "sys.haptic.long.press" to "1,2"
        )
        if (enabled) {
            props.keys.forEach { key ->
                val pref = "haptic_$key"
                if (!prefs.contains(pref)) {
                    val old = shell.exec("getprop '$key'", mode(), 1200)
                    if (old.ok) prefs.edit().putString(pref, old.out.trim()).apply()
                }
            }
            val rs = props.map { (k, v) -> shell.exec("setprop '$k' '$v'", mode(), 1600) }
            return "触感增强：${rs.count { it.ok }}/${rs.size} 项已提交。无需重启 zygote。"
        }
        val backups = prefs.all.filterKeys { it.startsWith("haptic_") }
        if (backups.isEmpty()) return "没有触感属性备份。"
        var ok = 0
        backups.forEach { (k, v) ->
            val prop = k.removePrefix("haptic_")
            val old = v as? String ?: ""
            if (shell.exec("setprop '$prop' '$old'", mode(), 1600).ok) ok++
        }
        prefs.edit().also { e -> backups.keys.forEach(e::remove) }.apply()
        return "触感属性恢复：$ok/${backups.size}"
    }

    fun surfaceFlingerReport(): String {
        val r = shell.exec(
            "dumpsys SurfaceFlinger 2>/dev/null | grep -E 'GLES:|refresh-rate|refreshRate|Display.*Hz|activeMode' | head -n 30",
            mode(), 2800
        )
        return r.out.trim().ifBlank { r.err.trim().ifBlank { "SurfaceFlinger 没有返回可读摘要。" } }
    }

    fun restoreAll(): String = listOf(
        restoreBackgroundProtection(),
        setHapticBoost(false),
        "云控组件不会自动恢复；请在系统工具页明确点击“恢复云控”，避免替用户改动系统策略。"
    ).joinToString("\n\n")
}
