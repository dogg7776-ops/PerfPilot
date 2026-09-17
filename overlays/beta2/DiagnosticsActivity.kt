package com.oai.perfpilot

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class DiagnosticsActivity : Activity() {
    private lateinit var shell: ShellEngine
    private lateinit var perf: PerfController
    private lateinit var metrics: RuntimeMetricsReader
    private lateinit var displayCtl: DisplayController
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var box: LinearLayout
    private lateinit var reportView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            UiKit.applyWindow(this)
            shell = ShellEngine(this)
            perf = PerfController(this, shell)
            metrics = RuntimeMetricsReader(this, shell)
            displayCtl = DisplayController(this, shell)
            setContentView(buildUi())
            runTests()
        } catch (t: Throwable) { showFallback(t) }
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@DiagnosticsActivity, 16), UiKit.dp(this@DiagnosticsActivity, 18), UiKit.dp(this@DiagnosticsActivity, 16), UiKit.dp(this@DiagnosticsActivity, 32))
        }
        root.addView(UiKit.row(this, UiKit.smallButton(this, "← 返回") { finish() }, UiKit.smallButton(this, "重新检测", true) { runTests() }))
        root.addView(UiKit.sectionTitle(this, "性能接口诊断", "这里保留原始读取结果。某项 N/A 时直接看具体路径/权限/返回格式，不再靠猜。"))
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(box)
        root.addView(UiKit.sectionTitle(this, "完整报告"))
        reportView = UiKit.text(this, "", 10.5f, UiKit.MUTED)
        root.addView(UiKit.card(this, 12).apply { addView(reportView) })
        root.addView(UiKit.gap(this, 10))
        root.addView(UiKit.button(this, "复制完整诊断报告") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PerfPilot diagnostics", reportView.text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        })
        scroll.addView(root)
        return scroll
    }

    private fun runTests() {
        if (!::box.isInitialized || worker.isShutdown) return
        box.removeAllViews()
        box.addView(UiKit.card(this, 14).apply { addView(UiKit.text(this@DiagnosticsActivity, "正在检测真实接口…", 13f, UiKit.MUTED)) })
        worker.execute {
            val items = mutableListOf<PerfController.Diagnostic>()
            try {
                items += perf.diagnostics()
                val gpu = metrics.readGpuDetailed()
                items += PerfController.Diagnostic(
                    "GPU 实时状态",
                    if (gpu.display != "N/A") PerfController.Diagnostic.State.OK else PerfController.Diagnostic.State.WARN,
                    "当前=${gpu.display}\n来源=${gpu.source}\n${if (gpu.display == "N/A") metrics.gpuProbeReport() else gpu.raw.take(1600)}"
                )
                val temp = metrics.readBatteryTemperature()
                items += PerfController.Diagnostic("电池温度", if (temp != "N/A") PerfController.Diagnostic.State.OK else PerfController.Diagnostic.State.WARN, temp)
                val fps = metrics.readForegroundFps()
                items += PerfController.Diagnostic("前台 SurfaceFlinger FPS", if (fps != "N/A") PerfController.Diagnostic.State.OK else PerfController.Diagnostic.State.WARN, fps)
                val dm = if (shell.hasShizukuPermission()) ShellEngine.Mode.SHIZUKU else shell.bestMode()
                val displayRaw = shell.exec("cmd display get-user-preferred-display-mode 0 2>&1", dm, 2200).let { (it.out + it.err).trim() }
                items += PerfController.Diagnostic(
                    "物理显示模式",
                    if (displayCtl.supportedRates().isNotEmpty()) PerfController.Diagnostic.State.OK else PerfController.Diagnostic.State.WARN,
                    displayCtl.describe() + "\ncmd display：\n" + displayRaw
                )
                items += PerfController.Diagnostic(
                    "性能悬浮窗",
                    PerfController.Diagnostic.State.OK,
                    if (OverlayService.isRunning(this)) "当前运行中，可在首页、悬浮窗 × 或通知栏关闭" else "当前已关闭"
                )
                val report = buildString {
                    append("PerfPilot v2 original-compatible diagnostics\n")
                    append("Shizuku server: ${shell.shizukuServerSummary()}\n")
                    items.forEach { append("[${it.state}] ${it.name}: ${it.detail}\n\n") }
                }
                runOnUiThread { render(items, report) }
            } catch (t: Throwable) {
                runOnUiThread { showDiagnosticError(t) }
            }
        }
    }

    private fun render(items: List<PerfController.Diagnostic>, report: String) {
        if (isFinishing || isDestroyed) return
        box.removeAllViews()
        items.forEachIndexed { i, d ->
            if (i > 0) box.addView(UiKit.gap(this, 8))
            val color = when (d.state) {
                PerfController.Diagnostic.State.OK -> UiKit.PRIMARY
                PerfController.Diagnostic.State.WARN -> UiKit.WARNING
                PerfController.Diagnostic.State.FAIL -> UiKit.DANGER
            }
            val icon = when (d.state) {
                PerfController.Diagnostic.State.OK -> "✓"
                PerfController.Diagnostic.State.WARN -> "!"
                PerfController.Diagnostic.State.FAIL -> "✕"
            }
            box.addView(UiKit.card(this, 14).apply {
                addView(UiKit.text(this@DiagnosticsActivity, "$icon ${d.name}", 14f, color, true))
                addView(UiKit.text(this@DiagnosticsActivity, d.detail, 11f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@DiagnosticsActivity, 6), 0, 0) })
            })
        }
        reportView.text = report
    }

    private fun showDiagnosticError(t: Throwable) {
        if (!::box.isInitialized) return
        box.removeAllViews()
        box.addView(UiKit.card(this, 14, 0xFFFFEEEE.toInt(), 18).apply {
            addView(UiKit.text(this@DiagnosticsActivity, "诊断异常已拦截", 14f, UiKit.DANGER, true))
            addView(UiKit.text(this@DiagnosticsActivity, "${t.javaClass.simpleName}: ${t.message ?: "unknown"}", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@DiagnosticsActivity, 6), 0, 0) })
        })
        if (::reportView.isInitialized) reportView.text = "DIAGNOSTIC_ERROR ${t.javaClass.name}: ${t.message}"
    }

    private fun showFallback(t: Throwable) {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 60, 36, 36)
            setBackgroundColor(UiKit.BG)
            addView(UiKit.text(this@DiagnosticsActivity, "诊断页启动保护", 22f, UiKit.DANGER, true))
            addView(UiKit.text(this@DiagnosticsActivity, "${t.javaClass.simpleName}: ${t.message ?: "unknown"}", 12f, UiKit.MUTED).apply { setPadding(0, 18, 0, 0) })
        })
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
