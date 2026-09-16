package com.oai.perfpilot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

class AdvancedTuningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            UiKit.applyWindow(this)
            setContentView(buildUi())
        } catch (t: Throwable) {
            showFallback(t)
        }
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@AdvancedTuningActivity, 16), UiKit.dp(this@AdvancedTuningActivity, 18), UiKit.dp(this@AdvancedTuningActivity, 16), UiKit.dp(this@AdvancedTuningActivity, 32))
        }
        root.addView(UiKit.row(this, UiKit.smallButton(this, "← 返回") { finish() }, UiKit.chip(this, "K90 Max / MT6993", true)))
        root.addView(UiKit.sectionTitle(this, "底层性能参数", "只展示实用入口；具体参数在进入分类后再加载，避免启动页一次初始化全部底层对象。"))
        root.addView(UiKit.card(this, 14, 0xFFFFF7E8.toInt(), 18).apply {
            addView(UiKit.text(this@AdvancedTuningActivity, "安全边界", 13f, UiKit.WARNING, true))
            addView(UiKit.text(this@AdvancedTuningActivity, "热保护相关项目只读，不停止温控服务，也不修改危险 trip point。", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@AdvancedTuningActivity, 6), 0, 0) })
        })

        val categories = listOf("FPSGO / CFP", "FBT / XGF", "GED GPU", "调度 / UClamp")
        categories.forEach { category ->
            root.addView(UiKit.gap(this, 10))
            root.addView(UiKit.card(this, 15).apply {
                isClickable = true
                background = UiKit.ripple(this@AdvancedTuningActivity, UiKit.SURFACE, 18)
                addView(UiKit.text(this@AdvancedTuningActivity, category, 15f, UiKit.TEXT, true))
                addView(UiKit.text(this@AdvancedTuningActivity, "点击查看当前设备可用参数  ›", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@AdvancedTuningActivity, 6), 0, 0) })
                setOnClickListener { safeOpen(ParameterActivity::class.java, category, category) }
            })
        }
        root.addView(UiKit.gap(this, 14))
        root.addView(UiKit.button(this, "性能接口诊断", true) { safeOpen(DiagnosticsActivity::class.java, "性能接口诊断") })
        scroll.addView(root)
        return scroll
    }

    private fun safeOpen(target: Class<out Activity>, label: String, category: String? = null) {
        try {
            val intent = Intent(this, target)
            if (category != null) intent.putExtra("category", category)
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "$label 打开失败：${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFallback(t: Throwable) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 60, 36, 36)
            setBackgroundColor(UiKit.BG)
            addView(UiKit.text(this@AdvancedTuningActivity, "底层参数页启动保护", 22f, UiKit.DANGER, true))
            addView(UiKit.text(this@AdvancedTuningActivity, "${t.javaClass.simpleName}: ${t.message ?: "unknown"}", 12.5f, UiKit.MUTED).apply { setPadding(0, 18, 0, 0) })
            addView(UiKit.gap(this@AdvancedTuningActivity, 16))
            addView(UiKit.button(this@AdvancedTuningActivity, "返回") { finish() })
        }
        setContentView(root)
    }
}
