package com.oai.perfpilot

import android.content.Context

/** Small wrapper around MediaTek's perfmanager binder service. */
class PerfManagerClient(context: Context, private val shell: ShellEngine) {
    data class ApplyResult(val ok: Boolean, val detail: String, val handle: Int? = null)

    private val handles = context.getSharedPreferences("perf_handles", Context.MODE_PRIVATE)

    fun isAvailable(): Boolean {
        if (!shell.hasShizukuPermission()) return false
        val r = shell.exec("service check perfmanager 2>/dev/null", ShellEngine.Mode.SHIZUKU, 2200)
        return r.out.contains("found", true) || r.out.contains("IBinder", true)
    }

    fun describeService(): String = shell.exec(
        "service check perfmanager 2>&1; service list 2>/dev/null | grep -Ei 'perfmanager|powerhal|power_hal|powerhalmgr' | head -n 12",
        if (shell.hasShizukuPermission()) ShellEngine.Mode.SHIZUKU else shell.bestMode(),
        3000
    ).let { (it.out + "\n" + it.err).trim() }

    fun apply(key: String, resourceId: Int, value: Int): ApplyResult {
        if (!shell.hasShizukuPermission()) return ApplyResult(false, "Shizuku 未授权")
        if (!isAvailable()) return ApplyResult(false, "perfmanager 服务不可用")
        release(key)
        val cmd = "service call perfmanager 1 i32 0 i32 0 i32 2 i32 $resourceId i32 $value"
        val r = shell.exec(cmd, ShellEngine.Mode.SHIZUKU, 5000)
        if (!r.ok) return ApplyResult(false, r.pretty())
        val handle = parseHandle(r.out)
            ?: return ApplyResult(false, "Binder 调用返回 exit=0，但没有解析到有效 handle；不把它误报为已生效。\n${r.out.trim()}")
        handles.edit().putInt(key, handle).apply()
        return ApplyResult(true, "资源 0x${resourceId.toUInt().toString(16).padStart(8, '0')} 已提交，handle=0x${handle.toUInt().toString(16)}", handle)
    }

    fun release(key: String): Boolean {
        if (!handles.contains(key)) return true
        val handle = handles.getInt(key, 0)
        if (handle == 0) {
            handles.edit().remove(key).apply()
            return true
        }
        if (!shell.hasShizukuPermission()) return false
        val r = shell.exec("service call perfmanager 3 i32 $handle", ShellEngine.Mode.SHIZUKU, 4000)
        if (r.ok) handles.edit().remove(key).apply()
        return r.ok
    }

    fun releaseAll(): String {
        val keys = handles.all.keys.toList()
        if (keys.isEmpty()) return "没有 PerfManager handle 需要释放。"
        var ok = 0
        var fail = 0
        keys.forEach { if (release(it)) ok++ else fail++ }
        return "释放 PowerHAL handle：成功 $ok，失败 $fail"
    }

    private fun parseHandle(text: String): Int? {
        val words = Regex("(?i)(?<![0-9a-f])[0-9a-f]{8}(?![0-9a-f])").findAll(text).map { it.value }.toList()
        return words.asReversed().firstNotNullOfOrNull { word ->
            val value = word.toLongOrNull(16) ?: return@firstNotNullOfOrNull null
            if (value == 0L || value == 0xffffffffL) null else value.toInt()
        }
    }
}
