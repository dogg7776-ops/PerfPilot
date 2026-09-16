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
import android.view.Choreographer
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var shell: ShellEngine
    private lateinit var perf: PerfController
    private lateinit var metricsReader: RuntimeMetricsReader
    private val worker = Executors.newSingleThreadExecutor()
    private val metricsWorker = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var deviceValue: TextView
    private lateinit var shizukuValue: TextView
    private lateinit var perfValue: TextView
    private lateinit var cpuValue: TextView
    private lateinit var gpuValue: TextView
    private lateinit var tempValue: TextView
    private lateinit var fpsValue: TextView
    private lateinit var logText: TextView
    private var shizukuListenerRegistered = false
    private var binderListenersRegistered = false
    @Volatile private var metricsRunning = false
    @Volatile private var metricsRound = 0L

    private var fpsWindowStartNs = 0L
    private var fpsFrames = 0
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!metricsRunning) return
            if (fpsWindowStartNs == 0L) {
                fpsWindowStartNs = frameTimeNanos
                fpsFrames = 0
            }
            fpsFrames++
            val elapsed = frameTimeNanos - fpsWindowStartNs
            if (elapsed >= 1_000_000_000L) {
                val fps = fpsFrames * 1_000_000_000.0 / elapsed
                if (::fpsValue.isInitialized) fpsValue.text = if (fps in 1.0..300.0) "${"%.1f".format(java.util.Locale.US, fps)}" else "N/A"
                fpsWindowStartNs = frameTimeNanos
                fpsFrames = 0
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        shell.invalidateModeCache()
        toast(if (grant == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权" else "Shizuku 未授权")
        refreshStatus()
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiKit.applyWindow(this)
        shell = ShellEngine(this)
        perf = PerfController(this, shell)
        metricsReader = RuntimeMetricsReader(this, shell)
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            shizukuListenerRegistered = true
            Shizuku.addBinderReceivedListener(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            binderListenersRegistered = true
        } catch (_: Throwable) {}
        try {
            setContentView(buildUi())
            refreshStatus()
        } catch (t: Throwable) {
            showFallback(t)
        }
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 18), UiKit.dp(this@MainActivity, 16), UiKit.dp(this@MainActivity, 34))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(UiKit.text(this@MainActivity, "PerfPilot", 28f, UiKit.TEXT, true))
            addView(UiKit.text(this@MainActivity, "MTK / Xiaomi 性能调度重构版", 12.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@MainActivity, 5), 0, 0) })
        })
        root.addView(UiKit.gap(this, 16))

        val status = UiKit.card(this, 15)
        status.addView(UiKit.text(this, "设备与连接", 13f, UiKit.TEXT, true))
        status.addView(UiKit.gap(this, 10))
        deviceValue = UiKit.text(this, "设备：检测中…", 12f, UiKit.MUTED)
        status.addView(deviceValue)
        status.addView(UiKit.gap(this, 8))
        shizukuValue = UiKit.text(this, "Shizuku：检测中…", 13f, UiKit.MUTED, true).apply {
            background = UiKit.ripple(this@MainActivity, UiKit.SURFACE_3, 12)
            setPadding(UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 12))
            setOnClickListener { requestShizuku() }
        }
        perfValue = UiKit.text(this, "MTK perfmanager：检测中…", 13f, UiKit.MUTED, true).apply { setPadding(0, UiKit.dp(this@MainActivity, 10), 0, 0) }
        status.addView(shizukuValue)
        status.addView(perfValue)
        root.addView(status)

        root.addView(UiKit.sectionTitle(this, "实时状态", "CPU / GPU / 电池温度独立并行读取；界面 FPS 由 Android VSync 实测，不拿刷新率冒充。"))
        val cpuTile = metric("CPU", "检测中…"); cpuValue = cpuTile.second
        val gpuTile = metric("GPU", "检测中…"); gpuValue = gpuTile.second
        val tempTile = metric("电池温度", "检测中…"); tempValue = tempTile.second
        val fpsTile = metric("界面 FPS", "采样中…"); fpsValue = fpsTile.second
        root.addView(UiKit.row(this, cpuTile.first, gpuTile.first))
        root.addView(UiKit.gap(this, 8))
        root.addView(UiKit.row(this, tempTile.first, fpsTile.first))

        root.addView(UiKit.sectionTitle(this, "快捷操作"))
        root.addView(UiKit.row(this, UiKit.button(this, "165 Hz", true) { runTask("165Hz") { perf.setRefreshRate(165) } }, UiKit.button(this, "120 Hz") { runTask("120Hz") { perf.setRefreshRate(120) } }))
        root.addView(UiKit.gap(this, 8))
        root.addView(UiKit.row(this, UiKit.button(this, "游戏模式", true) { runTask("游戏模式") { perf.safeGameProfile() } }, UiKit.button(this, "性能悬浮窗") { toggleOverlay() }))

        root.addView(UiKit.sectionTitle(this, "工具"))
        root.addView(tool("底层性能参数", "FPSGO / FBT / XGF / GED / UClamp / C2PS / PowerHAL") { safeOpen(AdvancedTuningActivity::class.java, "底层性能参数") })
        root.addView(UiKit.gap(this, 8))
        root.addView(tool("游戏独立配置", "按应用保存刷新率、FPSGO 与 CPU 策略") { safeOpen(GameProfilesActivity::class.java, "游戏独立配置") })
        root.addView(UiKit.gap(this, 8))
        root.addView(tool("性能接口诊断", "检查 Shizuku UID、perfmanager、PowerHAL、FPSGO、GED") { safeOpen(DiagnosticsActivity::class.java, "性能接口诊断") })
        root.addView(UiKit.gap(this, 8))
        root.addView(tool("CPU Governor", "优先 Shizuku；无权限时不强制 Root") { governorDialog() })
        root.addView(UiKit.gap(this, 8))
        root.addView(tool("全部恢复", "恢复设置、sysfs 备份并释放 PowerHAL handle") { runTask("恢复") { perf.restoreAll() } })

        root.addView(UiKit.sectionTitle(this, "执行结果"))
        logText = UiKit.text(this, "等待操作。", 11.5f, UiKit.MUTED)
        root.addView(UiKit.card(this, 14).apply { addView(logText) })
        root.addView(UiKit.gap(this, 14))
        root.addView(UiKit.card(this, 14, 0xFFFFF7E8.toInt(), 18).apply {
            addView(UiKit.text(this@MainActivity, "安全说明", 13f, UiKit.WARNING, true))
            addView(UiKit.text(this@MainActivity, "本版不会停止温控服务，也不会把温控 trip point 改到危险温度；原软件的这类功能不复刻。", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@MainActivity, 6), 0, 0) })
        })
        scroll.addView(root)
        return scroll
    }

    private fun metric(label: String, value: String): Pair<LinearLayout, TextView> {
        val valueView = UiKit.text(this, value, 15f, UiKit.TEXT, true)
        val card = UiKit.card(this, 12, UiKit.SURFACE, 16).apply {
            addView(UiKit.text(this@MainActivity, label, 10.5f, UiKit.MUTED, true))
            addView(valueView.apply { setPadding(0, UiKit.dp(this@MainActivity, 5), 0, 0) })
        }
        return card to valueView
    }

    private fun tool(title: String, sub: String, click: () -> Unit) = UiKit.card(this, 14).apply {
        isClickable = true
        background = UiKit.ripple(this@MainActivity, UiKit.SURFACE, 18)
        addView(UiKit.text(this@MainActivity, title, 14.5f, UiKit.TEXT, true))
        addView(UiKit.text(this@MainActivity, "$sub  ›", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@MainActivity, 5), 0, 0) })
        setOnClickListener { click() }
    }

    private fun safeOpen(target: Class<out Activity>, label: String) {
        try {
            startActivity(Intent(this, target))
        } catch (t: Throwable) {
            toast("$label 打开失败：${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        }
    }

    private fun refreshStatus() {
        if (!::shizukuValue.isInitialized || worker.isShutdown) return
        worker.execute {
            try {
                val binder = shell.hasShizukuBinder()
                val perm = shell.hasShizukuPermission()
                val execProbe = if (perm) shell.exec("id", ShellEngine.Mode.SHIZUKU, 2600) else null
                val shizukuReady = execProbe?.ok == true && execProbe.out.contains("uid=2000")
                val p = if (shizukuReady) shell.exec("service check perfmanager 2>&1", ShellEngine.Mode.SHIZUKU, 2500) else null
                val snap = perf.snapshot()
                main.post {
                    if (isFinishing) return@post
                    deviceValue.text = "${snap.profile.title}\n${snap.identity.model} · ${snap.identity.soc.ifBlank { snap.identity.boardPlatform }}"
                    deviceValue.setTextColor(if (snap.profile.id == DeviceProfiles.K90_MAX_DIMENSITY_9500.id) UiKit.PRIMARY else UiKit.MUTED)
                    shizukuValue.text = when {
                        shizukuReady -> "Shizuku：● 已授权且 Shell 通道可用"
                        perm -> "Shizuku：! 已授权，但执行通道异常（点诊断查看）"
                        binder -> "Shizuku：○ 已运行，点此授权"
                        else -> "Shizuku：✕ 未连接"
                    }
                    shizukuValue.setTextColor(when { shizukuReady -> UiKit.PRIMARY; binder -> UiKit.WARNING; else -> UiKit.DANGER })
                    val found = p?.out?.contains("found", true) == true || p?.out?.contains("IBinder", true) == true
                    perfValue.setTextColor(if (found) UiKit.PRIMARY else UiKit.MUTED)
                    perfValue.text = "MTK perfmanager：${if (found) "● 可用" else "○ 未检测到"}"
                }
            } catch (_: Throwable) {}
        }
    }

    private fun requestShizuku() {
        try {
            if (shell.hasShizukuBinder() && !shell.hasShizukuPermission()) shell.requestShizukuPermission(5001) else refreshStatus()
        } catch (t: Throwable) {
            toast(t.message ?: "Shizuku 调用失败")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        metricsRunning = true
        metricsRound++
        fpsWindowStartNs = 0L
        fpsFrames = 0
        Choreographer.getInstance().postFrameCallback(frameCallback)
        metricsLoop(metricsRound)
    }

    override fun onPause() {
        metricsRunning = false
        metricsRound++
        try { Choreographer.getInstance().removeFrameCallback(frameCallback) } catch (_: Throwable) {}
        super.onPause()
    }

    private fun metricsLoop(round: Long) {
        if (!metricsRunning || metricsWorker.isShutdown || round != metricsRound) return
        runMetric(round, { metricsReader.readCpu() }) { cpuValue.text = it }
        runMetric(round, { metricsReader.readGpu() }) { gpuValue.text = it }
        runMetric(round, { metricsReader.readBatteryTemperature() }) { tempValue.text = it }
        main.postDelayed({ metricsLoop(round) }, 4000)
    }

    private fun runMetric(round: Long, read: () -> String, apply: (String) -> Unit) {
        metricsWorker.execute {
            val value = try { read() } catch (_: Throwable) { "N/A" }
            main.post {
                if (!metricsRunning || isFinishing || isDestroyed || round != metricsRound) return@post
                apply(value)
            }
        }
    }

    private fun runTask(name: String, block: () -> String) {
        logText.text = "$name：执行中…"
        worker.execute {
            val s = try { block() } catch (t: Throwable) { "${t.javaClass.simpleName}: ${t.message}" }
            main.post { if (!isFinishing) logText.text = "$name\n$s" }
        }
    }

    private fun governorDialog() {
        val arr = arrayOf("schedutil", "performance", "powersave")
        android.app.AlertDialog.Builder(this).setTitle("CPU Governor").setItems(arr) { _, i -> runTask("CPU Governor") { perf.setCpuGovernor(arr[i]) } }.setNegativeButton("取消", null).show()
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("请允许悬浮窗后返回")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 6002)
        startForegroundService(Intent(this, OverlayService::class.java))
        toast("已启动悬浮窗")
    }

    private fun showFallback(t: Throwable) {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 60, 36, 36)
            setBackgroundColor(UiKit.BG)
            addView(UiKit.text(this@MainActivity, "PerfPilot 启动保护", 24f, UiKit.DANGER, true))
            addView(UiKit.text(this@MainActivity, "${t.javaClass.simpleName}: ${t.message}", 13f, UiKit.MUTED).apply { setPadding(0, 20, 0, 0) })
        })
    }

    override fun onDestroy() {
        metricsRunning = false
        if (shizukuListenerRegistered) try { Shizuku.removeRequestPermissionResultListener(permissionListener) } catch (_: Throwable) {}
        if (binderListenersRegistered) {
            try { Shizuku.removeBinderReceivedListener(binderReceived) } catch (_: Throwable) {}
            try { Shizuku.removeBinderDeadListener(binderDead) } catch (_: Throwable) {}
        }
        worker.shutdownNow()
        metricsWorker.shutdownNow()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
