package com.oai.perfpilot

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class SystemToolsActivity : Activity() {
    private lateinit var tools: SystemToolsController
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var result: TextView
    private lateinit var packageInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiKit.applyWindow(this)
        tools = SystemToolsController(this, ShellEngine(this))
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@SystemToolsActivity, 16), UiKit.dp(this@SystemToolsActivity, 18), UiKit.dp(this@SystemToolsActivity, 16), UiKit.dp(this@SystemToolsActivity, 36))
        }
        root.addView(UiKit.row(this, UiKit.smallButton(this, "← 返回") { finish() }, UiKit.chip(this, "原版兼容工具", true)))
        root.addView(UiKit.sectionTitle(this, "系统工具", "复刻原软件中实用的系统侧功能，但全部使用可恢复方式。危险温控绕过不会提供。"))

        root.addView(toolCard("Joyose / PowerKeeper 云控", "查看或切换性能云控组件。这里使用明确组件启停，不猜 ROM 的 Binder transaction ID。",
            UiKit.row(this,
                UiKit.smallButton(this, "查看状态") { runTask("云控状态") { tools.cloudControlStatus() } },
                UiKit.smallButton(this, "禁用云控", true) { runTask("禁用云控") { tools.setCloudControlDisabled(true) } },
                UiKit.smallButton(this, "恢复云控") { runTask("恢复云控") { tools.setCloudControlDisabled(false) } }
            )))
        root.addView(UiKit.gap(this, 10))

        val bg = UiKit.card(this, 14)
        bg.addView(UiKit.text(this, "后台保活", 15f, UiKit.TEXT, true))
        bg.addView(UiKit.text(this, "输入游戏/应用包名，加入 Xiaomi MILLET 不限制列表。不会像原脚本那样全局改一大串内存属性。", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@SystemToolsActivity, 6), 0, 0) })
        packageInput = EditText(this).apply {
            hint = "例如 com.tencent.tmgp.sgame"
            textSize = 13f
            setSingleLine(true)
            background = UiKit.rounded(UiKit.SURFACE_2, 12, this@SystemToolsActivity)
            setPadding(UiKit.dp(this@SystemToolsActivity, 12), UiKit.dp(this@SystemToolsActivity, 10), UiKit.dp(this@SystemToolsActivity, 12), UiKit.dp(this@SystemToolsActivity, 10))
        }
        bg.addView(UiKit.gap(this, 10)); bg.addView(packageInput); bg.addView(UiKit.gap(this, 10))
        bg.addView(UiKit.row(this,
            UiKit.smallButton(this, "加入保活", true) { runTask("后台保活") { tools.setBackgroundProtection(packageInput.text.toString().trim(), true) } },
            UiKit.smallButton(this, "移除") { runTask("移除保活") { tools.setBackgroundProtection(packageInput.text.toString().trim(), false) } },
            UiKit.smallButton(this, "恢复原值") { runTask("恢复后台设置") { tools.restoreBackgroundProtection() } }
        ))
        root.addView(bg)
        root.addView(UiKit.gap(this, 10))

        root.addView(toolCard("触感增强", "使用原软件同类 sys.haptic 属性，但不重启 zygote；修改前自动备份。",
            UiKit.row(this,
                UiKit.smallButton(this, "启用", true) { runTask("触感增强") { tools.setHapticBoost(true) } },
                UiKit.smallButton(this, "恢复") { runTask("恢复触感") { tools.setHapticBoost(false) } }
            )))
        root.addView(UiKit.gap(this, 10))
        root.addView(toolCard("SurfaceFlinger / 显示诊断", "查看 SurfaceFlinger 可读显示信息，便于判断实际刷新率与帧呈现。",
            UiKit.button(this, "读取显示摘要") { runTask("SurfaceFlinger") { tools.surfaceFlingerReport() } }))

        root.addView(UiKit.sectionTitle(this, "执行结果"))
        result = UiKit.text(this, "等待操作。", 11.5f, UiKit.MUTED)
        root.addView(UiKit.card(this, 14).apply { addView(result) })
        root.addView(UiKit.gap(this, 14))
        root.addView(UiKit.card(this, 14, 0xFFFFF7E8.toInt(), 18).apply {
            addView(UiKit.text(this@SystemToolsActivity, "温控安全边界", 13f, UiKit.WARNING, true))
            addView(UiKit.text(this@SystemToolsActivity, "原 APK 中把 thermal trip point 提到极高温度、停止温控等做法不会加入本版。其余功能优先做到可验证、可恢复。", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@SystemToolsActivity, 6), 0, 0) })
        })
        scroll.addView(root)
        return scroll
    }

    private fun toolCard(title: String, sub: String, action: android.view.View): LinearLayout = UiKit.card(this, 14).apply {
        addView(UiKit.text(this@SystemToolsActivity, title, 15f, UiKit.TEXT, true))
        addView(UiKit.text(this@SystemToolsActivity, sub, 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@SystemToolsActivity, 6), 0, 0) })
        addView(UiKit.gap(this@SystemToolsActivity, 10))
        addView(action)
    }

    private fun runTask(name: String, block: () -> String) {
        if (!::result.isInitialized || worker.isShutdown) return
        result.text = "$name：执行中…"
        worker.execute {
            val text = try { block() } catch (t: Throwable) { "${t.javaClass.simpleName}: ${t.message}" }
            runOnUiThread { if (!isFinishing && !isDestroyed) result.text = "$name\n$text" }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
