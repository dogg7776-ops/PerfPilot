from pathlib import Path
import re, sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('buildsrc/PerfPilot')
java = root / 'app/src/main/java/com/oai/perfpilot'

def sub(path, pattern, repl, label):
    p = Path(path); s = p.read_text(encoding='utf-8')
    n, c = re.subn(pattern, lambda _m: repl, s, count=1, flags=re.S)
    if c != 1: raise SystemExit(f'{label}: expected 1 match, got {c}')
    p.write_text(n, encoding='utf-8'); print('patched', label)

# version
gradle = root/'app/build.gradle.kts'; s = gradle.read_text(encoding='utf-8')
s = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = 17', s, count=1)
s = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "1.0.0-beta8-k90-usability"', s, count=1)
gradle.write_text(s, encoding='utf-8')

# refresh rate: Xiaomi keys + min clamp + actual supported Display.Mode via cmd display
perf = java/'PerfController.kt'
refresh = r'''    private fun closestDisplayMode(hz: Int): android.view.Display.Mode? {
        return try {
            val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            val display = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return null
            val current = display.mode
            val same = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            val best = (if (same.isNotEmpty()) same else display.supportedModes.toList()).minByOrNull { kotlin.math.abs(it.refreshRate - hz) } ?: return null
            best.takeIf { kotlin.math.abs(it.refreshRate - hz) <= 5f }
        } catch (_: Throwable) { null }
    }

    private fun backupPreferredDisplayMode() {
        if (prefs.contains("display_user_preferred_mode")) return
        val r = shell.exec("cmd display get-user-preferred-display-mode 2>/dev/null", privilegedMode(), 2200)
        if (r.ok) prefs.edit().putString("display_user_preferred_mode", r.out.trim()).apply()
    }

    fun setRefreshRate(hz: Int): String {
        if (hz <= 0) return "刷新率：保持当前设置"
        if (hz !in profile.refreshRates) return "刷新率：$hz Hz 不在 ${profile.title} 的目标档位 ${profile.refreshRates.joinToString("/")} 中，拒绝写入。"
        backupPreferredDisplayMode()
        val rs = mutableListOf<ShellEngine.Result>()
        rs += putSetting("secure", "miui_refresh_rate", hz.toString())
        rs += putSetting("secure", "user_refresh_rate", hz.toString())
        rs += putSetting("system", "peak_refresh_rate", String.format(Locale.US, "%.1f", hz.toFloat()))
        rs += putSetting("system", "min_refresh_rate", "0.0")
        val mode = closestDisplayMode(hz)
        if (mode != null) {
            val rate = String.format(Locale.US, "%.3f", mode.refreshRate)
            rs += shell.exec("cmd display set-user-preferred-display-mode ${mode.physicalWidth} ${mode.physicalHeight} $rate", privilegedMode(), 2800)
        }
        try { Thread.sleep(450) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        val check = shell.exec(
            "echo miui=$(settings get secure miui_refresh_rate 2>/dev/null); echo user=$(settings get secure user_refresh_rate 2>/dev/null); " +
            "echo peak=$(settings get system peak_refresh_rate 2>/dev/null); echo min=$(settings get system min_refresh_rate 2>/dev/null); " +
            "cmd display get-user-preferred-display-mode 2>/dev/null; dumpsys display 2>/dev/null | grep -m1 'mActiveSfDisplayMode'",
            privilegedMode(), 3200)
        return buildString {
            append("目标 ").append(hz).append(" Hz")
            if (mode != null) append("；匹配物理模式 ").append(String.format(Locale.US, "%.2f", mode.refreshRate)).append(" Hz")
            append("\n")
            rs.forEach { append(if (it.ok) "✓ " else "✕ ").append(it.mode).append(" exit=").append(it.code).append("\n") }
            append("回读：\n").append((check.out + check.err).trim().ifBlank { "无返回" })
        }
    }
'''
sub(perf, r'    fun setRefreshRate\(hz: Int\): String \{.*?\n    \}\n\n    fun setAnimationScale', refresh+'\n    fun setAnimationScale', 'refresh control')

restore = r'''    fun restoreSettings(): String {
        val all = prefs.all.filterKeys { it.startsWith("setting_") }
        val lines = mutableListOf<String>()
        for ((k, vv) in all) {
            val rest = k.removePrefix("setting_"); val i = rest.indexOf('_'); if (i <= 0) continue
            val ns = rest.substring(0, i); val key = rest.substring(i + 1); val old = vv as? String ?: continue
            val cmd = if (old == "null" || old.isBlank()) "settings delete $ns ${q(key)}" else "settings put $ns ${q(key)} ${q(old)}"
            lines += shell.exec(cmd, privilegedMode()).pretty()
        }
        prefs.edit().also { e -> all.keys.forEach(e::remove) }.apply()
        prefs.getString("display_user_preferred_mode", null)?.let { old ->
            val m = Regex("""User preferred display mode:\s+(\d+)\s+(\d+)\s+([0-9.]+)""").find(old)
            val r = if (old.contains("null", true)) shell.exec("cmd display clear-user-preferred-display-mode", privilegedMode(), 2600)
                    else if (m != null) shell.exec("cmd display set-user-preferred-display-mode ${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}", privilegedMode(), 2600) else null
            if (r != null) lines += "Display mode: ${r.pretty()}"
            prefs.edit().remove("display_user_preferred_mode").apply()
        }
        return lines.joinToString("\n\n").ifBlank { "没有已备份的系统设置。" }
    }
'''
sub(perf, r'    fun restoreSettings\(\): String \{.*?\n    \}\n\n    private fun backupFile', restore+'\n    private fun backupFile', 'refresh restore')

# GPU fallback: real GED utilization if current frequency remains hidden
runtime = java/'RuntimeMetricsReader.kt'; s = runtime.read_text(encoding='utf-8')
old = '''        readLegacyGpuProc()?.let { return it }\n        lastGpuSource = "unresolved"\n        return "N/A"\n    }'''
new = '''        readLegacyGpuProc()?.let { return it }\n        readGpuUtilization()?.let { lastGpuSource = "ged-utilization"; return "$it% 负载" }\n        lastGpuSource = "unresolved"\n        return "N/A"\n    }'''
if old not in s: raise SystemExit('GPU tail not found')
s = s.replace(old, new, 1)
marker = '    private fun readLegacyGpuProc(): String? {'
method = r'''    private fun readGpuUtilization(): Int? {
        val d = '$'
        val cmd = """
            for f in /sys/kernel/ged/hal/gpu_utilization /sys/kernel/debug/ged/hal/gpu_utilization /d/ged/hal/gpu_utilization /sys/module/ged/parameters/gpu_loading; do
              [ -r "${d}f" ] || continue; v=${d}(cat "${d}f" 2>/dev/null | head -n1)
              [ -n "${d}v" ] && { echo "${d}v"; exit 0; }
            done; exit 1
        """.trimIndent()
        val r = runRead(cmd, 1800); if (!r.ok) return null
        return Regex("(^|\\s)([0-9]{1,3})(?=\\s|$)").find(r.out)?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..100 }
    }

'''
if marker not in s: raise SystemExit('GPU insertion point not found')
s = s.replace(marker, method+marker, 1)
runtime.write_text(s, encoding='utf-8')
print('patched GPU utilization fallback')

# overlay: make X receive clicks and home button able to stop the live service
overlay = java/'OverlayService.kt'; s = overlay.read_text(encoding='utf-8')
s = s.replace('private const val NOTIFICATION_ID = 817\n    }', 'private const val NOTIFICATION_ID = 817\n        @Volatile private var active = false\n        fun isRunning(): Boolean = active\n    }', 1)
s = s.replace('if (intent?.action == ACTION_STOP) {\n            stopSelf()', 'if (intent?.action == ACTION_STOP) {\n            active = false\n            stopSelf()', 1)
s = s.replace('addOverlay()\n            running = true', 'addOverlay()\n            active = true\n            running = true', 1)
s = s.replace('override fun onDestroy() {\n        running = false', 'override fun onDestroy() {\n        active = false\n        running = false', 1)
s = s.replace('setOnClickListener { stopSelf() }', 'setOnClickListener { active = false; stopSelf() }', 1)
s = s.replace('overlay.setOnTouchListener { _, event ->', 'metricsText.setOnTouchListener { _, event ->', 1)
overlay.write_text(s, encoding='utf-8')
if 'fun isRunning(): Boolean = active' not in s or 'metricsText.setOnTouchListener' not in s: raise SystemExit('overlay patch failed')
print('patched overlay close/toggle state')

main = java/'MainActivity.kt'
toggle = r'''    private fun toggleOverlay() {
        if (OverlayService.isRunning()) {
            try { startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)) }
            catch (_: Throwable) { stopService(Intent(this, OverlayService::class.java)) }
            toast("已关闭性能悬浮窗")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("请允许悬浮窗后返回"); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 6002)
        val i = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        toast("已启动性能悬浮窗；再次点击或点 × 可关闭")
    }
'''
sub(main, r'    private fun toggleOverlay\(\) \{.*?\n    \}\n\n    private fun showFallback', toggle+'\n    private fun showFallback', 'home overlay toggle')
print('beta8 patch complete')
