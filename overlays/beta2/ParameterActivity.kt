package com.oai.perfpilot

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.concurrent.Executors

class ParameterActivity : Activity() {
    private lateinit var shell: ShellEngine
    private lateinit var perf: PerfController
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            UiKit.applyWindow(this)
            shell = ShellEngine(this)
            perf = PerfController(this, shell)
            val category = intent.getStringExtra("category") ?: "FPSGO / CFP"
            setContentView(buildUi(category))
        } catch (t: Throwable) {
            showFallback(t)
        }
    }

    private fun buildUi(category: String): ScrollView {
        val params = try { PerfCatalog.inCategory(category) } catch (_: Throwable) { emptyList() }
        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@ParameterActivity, 16), UiKit.dp(this@ParameterActivity, 18), UiKit.dp(this@ParameterActivity, 16), UiKit.dp(this@ParameterActivity, 32))
        }
        root.addView(UiKit.row(this, UiKit.smallButton(this, "← 返回") { finish() }, UiKit.chip(this, "${params.size} 项", true)))
        root.addView(UiKit.sectionTitle(this, category, "优先读取真实节点；节点未暴露时再尝试 MTK perfmanager / PowerHAL。"))
        if (params.isEmpty()) {
            root.addView(UiKit.card(this, 14).apply {
                addView(UiKit.text(this@ParameterActivity, "当前分类没有可加载参数", 14f, UiKit.WARNING, true))
                addView(UiKit.text(this@ParameterActivity, "这通常表示该分类在当前构建或设备上未适配。", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@ParameterActivity, 6), 0, 0) })
            })
        }
        params.forEachIndexed { index, param ->
            if (index > 0) root.addView(UiKit.gap(this, 10))
            root.addView(paramCard(param))
        }
        scroll.addView(root)
        return scroll
    }

    private fun paramCard(param: PerfParam): LinearLayout {
        val card = UiKit.card(this, 15, UiKit.SURFACE, 18)
        val title = UiKit.text(this, param.title, 15f, UiKit.TEXT, true)
        val idText = param.resourceId?.let { "0x${it.toUInt().toString(16).padStart(8, '0')}" } ?: "sysfs"
        val state = UiKit.text(this, "正在读取…", 12.5f, UiKit.MUTED)
        card.addView(UiKit.row(this, title, UiKit.chip(this, idText, param.resourceId != null)))
        card.addView(UiKit.text(this, param.description, 11.8f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@ParameterActivity, 8), 0, 0) })
        card.addView(state.apply { setPadding(0, UiKit.dp(this@ParameterActivity, 10), 0, 0) })

        if (param.writable) {
            val input = EditText(this).apply {
                hint = if (param.min != null && param.max != null) "${param.min} – ${param.max}" else "输入整数值"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                setTextColor(UiKit.TEXT)
                setHintTextColor(UiKit.DIM)
                textSize = 14f
                background = UiKit.rounded(UiKit.SURFACE_3, 12, this@ParameterActivity)
                setPadding(UiKit.dp(this@ParameterActivity, 12), 0, UiKit.dp(this@ParameterActivity, 12), 0)
            }
            val write = UiKit.smallButton(this, "写入", true) {
                val value = input.text.toString().trim().toIntOrNull()
                if (value == null) {
                    Toast.makeText(this, "请输入整数", Toast.LENGTH_SHORT).show()
                    return@smallButton
                }
                state.text = "正在写入…"
                worker.execute {
                    try {
                        val r = perf.writeParam(param, value)
                        main.post {
                            if (isFinishing || isDestroyed) return@post
                            state.setTextColor(if (r.ok) UiKit.PRIMARY else UiKit.DANGER)
                            state.text = "${if (r.ok) "✓" else "✕"} ${r.source}：${r.detail}"
                            if (r.ok) input.setText(value.toString())
                        }
                    } catch (t: Throwable) {
                        main.post {
                            if (!isFinishing && !isDestroyed) {
                                state.setTextColor(UiKit.DANGER)
                                state.text = "写入异常：${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
                            }
                        }
                    }
                }
            }
            card.addView(UiKit.gap(this, 10))
            card.addView(UiKit.row(this, input, write))
        } else {
            card.addView(UiKit.text(this, "只读：为安全起见不提供热保护阈值写入。", 11.5f, UiKit.WARNING, true).apply { setPadding(0, UiKit.dp(this@ParameterActivity, 10), 0, 0) })
        }

        worker.execute {
            try {
                val r = perf.readParam(param)
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    state.setTextColor(if (r.ok) UiKit.PRIMARY else UiKit.MUTED)
                    state.text = if (r.ok) "当前值：${r.value}  ·  ${r.source}${r.path?.let { "\n$it" } ?: ""}" else "${r.source}：${r.detail}"
                }
            } catch (t: Throwable) {
                main.post {
                    if (!isFinishing && !isDestroyed) {
                        state.setTextColor(UiKit.DANGER)
                        state.text = "读取异常：${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
                    }
                }
            }
        }
        return card
    }

    private fun showFallback(t: Throwable) {
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 60, 36, 36)
            setBackgroundColor(UiKit.BG)
            addView(UiKit.text(this@ParameterActivity, "参数页启动保护", 22f, UiKit.DANGER, true))
            addView(UiKit.text(this@ParameterActivity, "${t.javaClass.simpleName}: ${t.message ?: "unknown"}", 12.5f, UiKit.MUTED).apply { setPadding(0, 18, 0, 0) })
            addView(UiKit.gap(this@ParameterActivity, 16))
            addView(UiKit.button(this@ParameterActivity, "返回") { finish() })
        })
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
