package com.oai.perfpilot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var shell: ShellEngine
    private lateinit var perf: PerfController
    private lateinit var displayCtl: DisplayController
    private lateinit var metrics: RuntimeMetricsReader
    private val worker = Executors.newSingleThreadExecutor()
    private val metricPool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var deviceValue: TextView
    private lateinit var shizukuValue: TextView
    private lateinit var perfValue: TextView
    private lateinit var displayValue: TextView
    private lateinit var cpuValue: TextView
    private lateinit var gpuValue: TextView
    private lateinit var tempValue: TextView
    private lateinit var fpsValue: TextView
    private lateinit var overlayButton: TextView
    private lateinit var result: TextView
    @Volatile private var live = false
    @Volatile private var round = 0L
    private var permissionListenerRegistered = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        shell.invalidateModeCache()
        toast(if (grant == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权" else "Shizuku 未授权")
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiKit.applyWindow(this)
        shell = ShellEngine(this)
        perf = PerfController(this, shell)
        displayCtl = DisplayController(this, shell)
        metrics = RuntimeMetricsReader(this, shell)
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            permissionListenerRegistered = true
        } catch (_: Throwable) {}
        try {
            setContentView(buildUi())
            refreshStatus()
        } catch (t: Throwable) { showFallback(t) }
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 18), UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 36))
        }
        root.addView(UiKit.text(this, "PerfPilot", 28f, UiKit.TEXT, true))
        root.addView(UiKit.text(this, "原版功能兼容重构 · MTK / Xiaomi", 12.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@MainActivity, 5), 0, 0) })
        root.addView(UiKit.gap(this, 16))

        val status = UiKit.card(this, 15)
        status.addView(UiKit.text(this, "设备与底层通道", 13f, UiKit.TEXT, true))
        status.addView(UiKit.gap(this, 10))
        deviceValue = UiKit.text(this, "设备：检测中…", 12f, UiKit.MUTED)
        shizukuValue = UiKit.text(this, "Shizuku：检测中…", 13f, UiKit.MUTED, true).apply {
            background = UiKit.ripple(this@MainActivity, UiKit.SURFACE_3, 12)
            setPadding(UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 12))
            setOnClickListener { requestShizuku() }
        }
        perfValue = UiKit.text(this, "MTK perfmanager：检测中…", 12.5f, UiKit.MUTED, true)
        displayValue = UiKit.text(this, "显示模式：检测中…", 12f, UiKit.MUTED)
        status.addView(deviceValue); status.addView(UiKit.gap(this, 9)); status.addView(shizukuValue)
        status.addView(UiKit.gap(this, 9)); status.addView(perfValue); status.addView(UiKit.gap(this, 7)); status.addView(displayValue)
        root.addView(status)

        root.addView(UiKit.sectionTitle(this, "实时状态", "全部读取真实系统接口；读不到就显示 N/A，不用估算值冒充。"))
        val c = metric("CPU"); cpuValue = c.second
        val g = metric("GPU"); gpuValue = g.second
        val t = metric("电池温度"); tempValue = t.second
        val f = metric("前台 FPS"); fpsValue = f.second
        root.addView(UiKit.row(this, c.first, g.first)); root.addView(UiKit.gap(this, 8)); root.addView(UiKit.row(this, t.first, f.first))

        root.addView(UiKit.sectionTitle(this, "快捷操作"))
        root.addView(UiKit.row(this,
            UiKit.button(this, "刷新率") { chooseRefreshRate() },
            UiKit.button(this, "游戏模式", true) { applyGameMode() }
        ))
        root.addView(UiKit.gap(this, 8))
        overlayButton = UiKit.button(this, "性能悬浮窗") { toggleOverlay() }
        root.addView(overlayButton)

        root.addView(UiKit.sectionTitle(this, "功能"))
        root.addView(tool("底层性能参数", "FPSGO / FBT / XGF / GED / UClamp / DRAM / C2PS / Touch") { open(AdvancedTuningActivity::class.java) })
        root.addView(UiKit.gap(this, 8))
        root.addView(tool("游戏独立配置", "按应用保存刷新率、FPSGO、CPU 策略") { open(GameProfilesActivity::class.java) })
        root.addView(UiKit.gap(this, 8))
        root.addView(tool("系统工具", "Joyose / PowerKeeper 云控、后台保活、触感、SurfaceFlinger") { open(SystemToolsActivity::class.java) })
        root.addView(UiKit.gap(this, 8))
        root.addView(tool("性能接口诊断", "查看真实 GPUFreq、显示模式、Shizuku、PowerHAL、CPUFreq") { open(DiagnosticsActivity::class.java) })
        root.addView(UiKit.gap(this, 8))
        root.addView(tool("CPU Governor", "只写本机真正支持的 governor") { governorDialog() })
        root.addView(UiKit.gap(this, 8))
        root.addView(tool("全部恢复", "恢复刷新率、settings、sysfs 备份并释放 PowerHAL handle") { restoreAll() })

        root.addView(UiKit.sectionTitle(this, "执行结果"))
        result = UiKit.text(this, "等待操作。", 11.5f, UiKit.MUTED)
        root.addView(UiKit.card(this, 14).apply { addView(result) })
        root.addView(UiKit.gap(this, 14))
        root.addView(UiKit.card(this, 14, 0xFFFFF7E8.toInt(), 18).apply {
            addView(UiKit.text(this@MainActivity, "安全说明", 13f, UiKit.WARNING, true))
            addView(UiKit.text(this@MainActivity, "原 APK 中关闭/绕过温控、把 thermal trip point 提到危险温度的功能不会复刻。其他功能按原行为做兼容，并要求可验证、可恢复。", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@MainActivity, 6), 0, 0) })
        })
        scroll.addView(root)
        return scroll
    }

    private fun metric(label: String): Pair<LinearLayout, TextView> {
        val v = UiKit.text(this, "检测中…", 15f, UiKit.TEXT, true)
        val card = UiKit.card(this, 12).apply {
            addView(UiKit.text(this@MainActivity, label, 10.5f, UiKit.MUTED, true))
            addView(v.apply { setPadding(0, UiKit.dp(this@MainActivity, 5), 0, 0) })
        }
        return card to v
    }

    private fun tool(title: String, sub: String, click: () -> Unit): LinearLayout = UiKit.card(this, 14).apply {
        isClickable = true
        background = UiKit.ripple(this@MainActivity, UiKit.SURFACE, 18)
        addView(UiKit.text(this@MainActivity, title, 14.5f, UiKit.TEXT, true))
        addView(UiKit.text(this@MainActivity, "$sub  ›", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@MainActivity, 5), 0, 0) })
        setOnClickListener { click() }
    }

    private fun refreshStatus() {
        if (!::shizukuValue.isInitialized || worker.isShutdown) return
        worker.execute {
            val snap = runCatching { perf.snapshot() }.getOrNull()
            val binder = shell.hasShizukuBinder()
            val perm = shell.hasShizukuPermission()
            val id = if (perm) shell.exec("id", ShellEngine.Mode.SHIZUKU, 2400) else null
            val shizukuReady = id?.ok == true && id.out.contains("uid=2000")
            val pm = if (shizukuReady) shell.exec("service check perfmanager 2>&1", ShellEngine.Mode.SHIZUKU, 2200) else null
            val perfReady = pm?.out?.let { it.contains("found", true) || it.contains("IBinder", true) } == true
            val disp = displayCtl.describe()
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (snap != null) deviceValue.text = "${snap.profile.title}\n${snap.identity.model} · ${snap.identity.soc.ifBlank { snap.identity.boardPlatform }}"
                shizukuValue.text = when {
                    shizukuReady -> "Shizuku：● Shell UID 2000 可用"
                    perm -> "Shizuku：! 已授权，但 Shell 执行失败"
                    binder -> "Shizuku：○ 已运行，点此授权"
                    else -> "Shizuku：✕ 未连接"
                }
                shizukuValue.setTextColor(if (shizukuReady) UiKit.PRIMARY else if (binder) UiKit.WARNING else UiKit.DANGER)
                perfValue.text = "MTK perfmanager：${if (perfReady) "● 可用" else "○ 未检测到"}"
                perfValue.setTextColor(if (perfReady) UiKit.PRIMARY else UiKit.MUTED)
                displayValue.text = disp
                updateOverlayButton()
            }
        }
    }

    private fun requestShizuku() {
        try {
            if (shell.hasShizukuBinder() && !shell.hasShizukuPermission()) shell.requestShizukuPermission(5001) else refreshStatus()
        } catch (t: Throwable) { toast(t.message ?: "Shizuku 调用失败") }
    }

    private fun chooseRefreshRate() {
        val rates = displayCtl.supportedRates()
        if (rates.isEmpty()) { toast("没有从屏幕读取到可用刷新率，请先看性能接口诊断"); return }
        val labels = rates.map { "$it Hz" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("真实屏幕刷新率")
            .setItems(labels) { _, which -> runTask("切换 ${rates[which]} Hz") { displayCtl.apply(rates[which]).detail } }
            .setNegativeButton("取消", null).show()
    }

    private fun applyGameMode() {
        runTask("游戏模式") {
            val maxHz = displayCtl.supportedRates().maxOrNull()
            buildString {
                if (maxHz != null) append("刷新率：\n").append(displayCtl.apply(maxHz).detail).append("\n\n")
                append("PowerHAL：\n").append(perf.setPowerHalGameMode(true)).append("\n\n")
                append("FPSGO：\n").append(perf.enableMtkFpsgo())
            }
        }
    }

    private fun toggleOverlay() {
        if (OverlayService.isRunning(this)) {
            OverlayService.stop(this)
            result.text = "性能悬浮窗：正在关闭…"
            main.postDelayed({ updateOverlayButton() }, 500)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("请允许悬浮窗权限后返回，再点一次")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 6002)
        }
        try {
            OverlayService.start(this)
            result.text = "性能悬浮窗：已请求启动；可点悬浮窗 ×、通知栏“关闭悬浮窗”或再次点此关闭。"
            main.postDelayed({ updateOverlayButton() }, 500)
        } catch (t: Throwable) { result.text = "悬浮窗启动失败：${t.message}" }
    }

    private fun updateOverlayButton() {
        if (::overlayButton.isInitialized) overlayButton.text = if (OverlayService.isRunning(this)) "关闭性能悬浮窗" else "开启性能悬浮窗"
    }

    private fun governorDialog() {
        val arr = arrayOf("schedutil", "performance", "powersave")
        android.app.AlertDialog.Builder(this).setTitle("CPU Governor").setItems(arr) { _, i -> runTask("CPU Governor") { perf.setCpuGovernor(arr[i]) } }.setNegativeButton("取消", null).show()
    }

    private fun restoreAll() {
        runTask("全部恢复") {
            val a = displayCtl.restore()
            val b = perf.restoreAll()
            val c = SystemToolsController(this, shell).restoreAll()
            "$a\n\n$b\n\n$c"
        }
    }

    private fun open(c: Class<out Activity>) { try { startActivity(Intent(this, c)) } catch (t: Throwable) { toast("页面打开失败：${t.message}") } }

    private fun runTask(name: String, block: () -> String) {
        if (!::result.isInitialized || worker.isShutdown) return
        result.text = "$name：执行中…"
        worker.execute {
            val text = try { block() } catch (t: Throwable) { "${t.javaClass.simpleName}: ${t.message}" }
            main.post {
                if (!isFinishing && !isDestroyed) {
                    result.text = "$name\n$text"
                    displayValue.text = displayCtl.describe()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        live = true
        round++
        metricsLoop(round)
        updateOverlayButton()
    }

    override fun onPause() {
        live = false
        round++
        super.onPause()
    }

    private fun metricsLoop(g: Long) {
        if (!live || metricPool.isShutdown || g != round) return
        runMetric(g, { metrics.readCpu() }) { cpuValue.text = it }
        runMetric(g, { metrics.readGpu() }) { gpuValue.text = it }
        runMetric(g, { metrics.readBatteryTemperature() }) { tempValue.text = it }
        runMetric(g, { metrics.readForegroundFps() }) { fpsValue.text = it }
        main.postDelayed({ metricsLoop(g) }, 3000L)
    }

    private fun runMetric(g: Long, read: () -> String, apply: (String) -> Unit) {
        metricPool.execute {
            val value = try { read() } catch (_: Throwable) { "N/A" }
            main.post { if (live && g == round && !isFinishing && !isDestroyed) apply(value) }
        }
    }

    private fun showFallback(t: Throwable) {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 60, 36, 36)
            setBackgroundColor(UiKit.BG)
            addView(UiKit.text(this@MainActivity, "PerfPilot 启动保护", 23f, UiKit.DANGER, true))
            addView(UiKit.text(this@MainActivity, "${t.javaClass.simpleName}: ${t.message}", 12f, UiKit.MUTED).apply { setPadding(0, 18, 0, 0) })
        })
    }

    override fun onDestroy() {
        live = false
        if (permissionListenerRegistered) try { Shizuku.removeRequestPermissionResultListener(permissionListener) } catch (_: Throwable) {}
        worker.shutdownNow(); metricPool.shutdownNow()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
