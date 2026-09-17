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
        } catch (t: Throwable) { showFallback(t) }
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@AdvancedTuningActivity, 16), UiKit.dp(this@AdvancedTuningActivity, 18), UiKit.dp(this@AdvancedTuningActivity, 16), UiKit.dp(this@AdvancedTuningActivity, 34))
        }
        root.addView(UiKit.row(this, UiKit.smallButton(this, "← 返回") { finish() }, UiKit.chip(this, "MTK 原版参数体系", true)))
        root.addView(UiKit.sectionTitle(this, "底层性能参数", "按原 APK 的功能域重新整理：FPSGO、FBT/XGF、GED、UClamp、DRAM、触控/启动、C2PS 与热感知。"))
        root.addView(UiKit.card(this, 14, 0xFFFFF7E8.toInt(), 18).apply {
            addView(UiKit.text(this@AdvancedTuningActivity, "真实接口优先", 13f, UiKit.WARNING, true))
            addView(UiKit.text(this@AdvancedTuningActivity, "参数写入优先走 MTK perfmanager/PowerHAL；存在可读 sysfs 时才直接读写并回读验证。热保护相关项目只读。", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@AdvancedTuningActivity, 6), 0, 0) })
        })
        PerfCatalog.categories.forEach { category ->
            root.addView(UiKit.gap(this, 10))
            root.addView(UiKit.card(this, 15).apply {
                isClickable = true
                background = UiKit.ripple(this@AdvancedTuningActivity, UiKit.SURFACE, 18)
                val count = PerfCatalog.inCategory(category).size
                addView(UiKit.text(this@AdvancedTuningActivity, category, 15f, UiKit.TEXT, true))
                addView(UiKit.text(this@AdvancedTuningActivity, "$count 个参数 · 点击检测本机可用通道  ›", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@AdvancedTuningActivity, 6), 0, 0) })
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
            val i = Intent(this, target)
            if (category != null) i.putExtra("category", category)
            startActivity(i)
        } catch (t: Throwable) {
            Toast.makeText(this, "$label 打开失败：${t.javaClass.simpleName}: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFallback(t: Throwable) {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 60, 36, 36)
            setBackgroundColor(UiKit.BG)
            addView(UiKit.text(this@AdvancedTuningActivity, "底层参数页启动保护", 22f, UiKit.DANGER, true))
            addView(UiKit.text(this@AdvancedTuningActivity, "${t.javaClass.simpleName}: ${t.message ?: "unknown"}", 12.5f, UiKit.MUTED).apply { setPadding(0, 18, 0, 0) })
            addView(UiKit.gap(this@AdvancedTuningActivity, 16))
            addView(UiKit.button(this@AdvancedTuningActivity, "返回") { finish() })
        })
    }
}
