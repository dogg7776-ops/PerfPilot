package com.oai.perfpilot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class GameProfilesActivity : Activity() {
    private lateinit var shell: ShellEngine
    private lateinit var perf: PerfController
    private lateinit var store: GameProfileStore
    private lateinit var listBox: LinearLayout
    private lateinit var selectedLabelView: TextView
    private lateinit var selectedPackageView: TextView
    private lateinit var selectedIconView: ImageView
    private lateinit var rateBox: LinearLayout
    private lateinit var governorBox: LinearLayout
    private lateinit var fpsgoSwitch: Switch

    private var selectedPackage = ""
    private var selectedLabel = ""
    private var selectedRate = 120
    private var selectedGovernor = "unchanged"

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            try { UiKit.applyWindow(this) } catch (_: Throwable) { }
            shell = ShellEngine(this)
            perf = PerfController(this, shell)
            store = GameProfileStore(this)
            setContentView(buildUi())
            renderProfiles()
        } catch (t: Throwable) {
            showLaunchFallback(t)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(UiKit.BG)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@GameProfilesActivity, 16), UiKit.dp(this@GameProfilesActivity, 12), UiKit.dp(this@GameProfilesActivity, 16), UiKit.dp(this@GameProfilesActivity, 28))
        }
        scroll.addView(root)

        root.addView(topBar())
        root.addView(UiKit.gap(this, 14))
        root.addView(selectedAppCard())
        root.addView(UiKit.gap(this, 10))
        root.addView(UiKit.button(this, "选择已安装的游戏 / App", false) { chooseLauncherApp() })

        root.addView(UiKit.sectionTitle(this, "独立配置说明"))
        root.addView(UiKit.card(this, 15, UiKit.PRIMARY_DARK, 18).apply {
            background = UiKit.rounded(UiKit.PRIMARY_DARK, 18, this@GameProfilesActivity, 0xFF246C58.toInt())
            addView(UiKit.text(this@GameProfilesActivity, "每个游戏保存自己的刷新率、FPSGO 与 CPU 调度策略。", 13f, UiKit.TEXT, true))
            addView(UiKit.text(this@GameProfilesActivity, "点击“应用并启动”时先套用配置，再打开游戏；离开后可一键恢复。", 12f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@GameProfilesActivity, 7), 0, 0) })
        })

        root.addView(UiKit.sectionTitle(this, "游戏性能配置", "所有高级写入都会先检测权限与节点"))
        val configCard = UiKit.card(this, 16, UiKit.SURFACE, 20)

        configCard.addView(UiKit.text(this, "屏幕刷新率", 15f, UiKit.TEXT, true))
        configCard.addView(UiKit.text(this, "只修改本 App 负责的系统刷新率设置", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@GameProfilesActivity, 4), 0, UiKit.dp(this@GameProfilesActivity, 10)) })
        rateBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        configCard.addView(rateBox)
        renderRateOptions()

        configCard.addView(UiKit.divider(this))
        configCard.addView(UiKit.text(this, "CPU Governor", 15f, UiKit.TEXT, true))
        configCard.addView(UiKit.text(this, "不支持的 governor 会自动跳过，不会强写。", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@GameProfilesActivity, 4), 0, UiKit.dp(this@GameProfilesActivity, 10)) })
        governorBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        configCard.addView(governorBox)
        renderGovernorOptions()

        configCard.addView(UiKit.divider(this))
        fpsgoSwitch = Switch(this).apply {
            text = "启动游戏前开启联发科 FPSGO"
            textSize = 14f
            setTextColor(UiKit.TEXT)
            isChecked = true
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                trackTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(UiKit.PRIMARY, UiKit.STROKE)
                )
                thumbTintList = android.content.res.ColorStateList.valueOf(0xFFF5F7F8.toInt())
            }
        }
        configCard.addView(fpsgoSwitch)
        configCard.addView(UiKit.text(this, "优先 Shizuku / 可回退 Root 且节点存在才会写入；否则只会给出提示。", 11.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@GameProfilesActivity, 5), 0, 0) })

        configCard.addView(UiKit.gap(this, 16))
        configCard.addView(UiKit.row(
            this,
            UiKit.button(this, "保存配置", false) { saveCurrent() },
            UiKit.button(this, "应用并启动", true) { saveAndLaunchCurrent() }
        ))
        configCard.addView(UiKit.gap(this, 8))
        configCard.addView(UiKit.button(this, "恢复 PerfPilot 改过的系统 / 内核参数", false) { restoreAll() })
        root.addView(configCard)

        root.addView(UiKit.sectionTitle(this, "已保存配置", "点一次就可以应用并启动"))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        return scroll
    }

    private fun topBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val back = UiKit.text(this@GameProfilesActivity, "‹", 36f, UiKit.TEXT).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        addView(back, LinearLayout.LayoutParams(UiKit.dp(this@GameProfilesActivity, 46), UiKit.dp(this@GameProfilesActivity, 48)))
        addView(LinearLayout(this@GameProfilesActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(UiKit.text(this@GameProfilesActivity, "游戏独立配置", 24f, UiKit.TEXT, true))
            addView(UiKit.text(this@GameProfilesActivity, "为每个游戏保存单独调优方案", 12.5f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@GameProfilesActivity, 4), 0, 0) })
        })
    }

    private fun selectedAppCard(): View {
        selectedIconView = ImageView(this).apply {
            setImageResource(android.R.drawable.sym_def_app_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiKit.rounded(UiKit.SURFACE_3, 16, this@GameProfilesActivity)
            setPadding(UiKit.dp(this@GameProfilesActivity, 8), UiKit.dp(this@GameProfilesActivity, 8), UiKit.dp(this@GameProfilesActivity, 8), UiKit.dp(this@GameProfilesActivity, 8))
        }
        selectedLabelView = UiKit.text(this, "尚未选择应用", 18f, UiKit.TEXT, true)
        selectedPackageView = UiKit.text(this, "从已安装的桌面应用中选择", 12f, UiKit.MUTED)
        return UiKit.card(this, 16, UiKit.SURFACE, 20).apply {
            val row = LinearLayout(this@GameProfilesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(selectedIconView, LinearLayout.LayoutParams(UiKit.dp(this@GameProfilesActivity, 62), UiKit.dp(this@GameProfilesActivity, 62)))
                addView(LinearLayout(this@GameProfilesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(UiKit.dp(this@GameProfilesActivity, 14), 0, 0, 0)
                    addView(selectedLabelView)
                    addView(selectedPackageView.apply { setPadding(0, UiKit.dp(this@GameProfilesActivity, 6), 0, 0) })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(row)
        }
    }

    private fun renderRateOptions() {
        rateBox.removeAllViews()
        val options = listOf(0 to "不修改") + perf.supportedRefreshRates().map { it to it.toString() }
        options.forEachIndexed { index, (value, label) ->
            val selected = selectedRate == value
            val chip = UiKit.text(this, if (value == 0) label else "$label Hz", 11.5f, if (selected) android.graphics.Color.WHITE else UiKit.MUTED, selected).apply {
                gravity = Gravity.CENTER
                minHeight = UiKit.dp(this@GameProfilesActivity, 42)
                background = UiKit.ripple(this@GameProfilesActivity, if (selected) UiKit.PRIMARY else UiKit.SURFACE_3, 13, if (selected) UiKit.PRIMARY else UiKit.STROKE)
                setOnClickListener { selectedRate = value; renderRateOptions() }
            }
            rateBox.addView(chip, LinearLayout.LayoutParams(0, UiKit.dp(this, 42), 1f).apply { if (index > 0) leftMargin = UiKit.dp(this@GameProfilesActivity, 6) })
        }
    }

    private fun renderGovernorOptions() {
        governorBox.removeAllViews()
        val options = listOf("unchanged" to "不修改", "schedutil" to "schedutil", "performance" to "performance")
        options.forEachIndexed { index, (value, label) ->
            val selected = selectedGovernor == value
            val chip = UiKit.text(this, label, 11.5f, if (selected) android.graphics.Color.WHITE else UiKit.MUTED, selected).apply {
                gravity = Gravity.CENTER
                minHeight = UiKit.dp(this@GameProfilesActivity, 42)
                background = UiKit.ripple(this@GameProfilesActivity, if (selected) UiKit.PRIMARY else UiKit.SURFACE_3, 13, if (selected) UiKit.PRIMARY else UiKit.STROKE)
                setOnClickListener { selectedGovernor = value; renderGovernorOptions() }
            }
            governorBox.addView(chip, LinearLayout.LayoutParams(0, UiKit.dp(this, 42), 1f).apply { if (index > 0) leftMargin = UiKit.dp(this@GameProfilesActivity, 6) })
        }
    }

    private fun chooseLauncherApp() {
        worker.execute {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(intent, 0)
                .map { ri ->
                    val label = ri.loadLabel(packageManager)?.toString()?.ifBlank { ri.activityInfo.packageName } ?: ri.activityInfo.packageName
                    label to ri.activityInfo.packageName
                }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
            main.post {
                if (apps.isEmpty()) {
                    toast("没有读取到可启动的应用")
                    return@post
                }
                val labels = apps.map { "${it.first}\n${it.second}" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("选择应用")
                    .setItems(labels) { _, which ->
                        selectedLabel = apps[which].first
                        selectedPackage = apps[which].second
                        selectedLabelView.text = selectedLabel
                        selectedPackageView.text = selectedPackage
                        selectedIconView.setImageDrawable(loadIcon(selectedPackage))
                        loadIfExisting(selectedPackage)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun loadIcon(pkg: String): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: Throwable) {
        getDrawable(android.R.drawable.sym_def_app_icon)
    }

    private fun loadIfExisting(pkg: String) {
        val p = store.all().firstOrNull { it.packageName == pkg } ?: return
        selectedRate = p.refreshRate
        selectedGovernor = p.cpuGovernor
        fpsgoSwitch.isChecked = p.enableFpsgo
        renderRateOptions()
        renderGovernorOptions()
    }

    private fun profileFromCurrent(): GameProfile? {
        if (selectedPackage.isBlank()) {
            toast("先选择一个应用")
            return null
        }
        return GameProfile(
            packageName = selectedPackage,
            label = selectedLabel.ifBlank { selectedPackage },
            refreshRate = selectedRate,
            enableFpsgo = fpsgoSwitch.isChecked,
            cpuGovernor = selectedGovernor
        )
    }

    private fun saveCurrent(): GameProfile? {
        val p = profileFromCurrent() ?: return null
        store.upsert(p)
        renderProfiles()
        toast("配置已保存")
        return p
    }

    private fun saveAndLaunchCurrent() {
        val p = saveCurrent() ?: return
        applyAndLaunch(p)
    }

    private fun renderProfiles() {
        listBox.removeAllViews()
        val profiles = store.all()
        if (profiles.isEmpty()) {
            listBox.addView(UiKit.card(this, 14, UiKit.SURFACE_3, 18).apply {
                addView(UiKit.text(this@GameProfilesActivity, "还没有保存游戏配置", 13f, UiKit.MUTED))
            })
            return
        }
        profiles.forEachIndexed { index, p ->
            if (index > 0) listBox.addView(UiKit.gap(this, 8))
            val box = UiKit.card(this, 14, UiKit.SURFACE_3, 18)
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@GameProfilesActivity).apply {
                    setImageDrawable(loadIcon(p.packageName))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, LinearLayout.LayoutParams(UiKit.dp(this@GameProfilesActivity, 48), UiKit.dp(this@GameProfilesActivity, 48)))
                addView(LinearLayout(this@GameProfilesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(UiKit.dp(this@GameProfilesActivity, 12), 0, 0, 0)
                    addView(UiKit.text(this@GameProfilesActivity, p.label, 15f, UiKit.TEXT, true))
                    addView(UiKit.text(this@GameProfilesActivity, p.packageName, 10.5f, UiKit.DIM).apply { setPadding(0, UiKit.dp(this@GameProfilesActivity, 5), 0, 0) })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(top)
            box.addView(UiKit.gap(this, 10))
            val tags = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(UiKit.chip(this@GameProfilesActivity, if (p.refreshRate == 0) "刷新率不改" else "${p.refreshRate} Hz", p.refreshRate > 0))
                addView(UiKit.chip(this@GameProfilesActivity, p.cpuGovernor, p.cpuGovernor != "unchanged").apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = UiKit.dp(this@GameProfilesActivity, 6) })
                addView(UiKit.chip(this@GameProfilesActivity, if (p.enableFpsgo) "FPSGO 开" else "FPSGO 不改", p.enableFpsgo).apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = UiKit.dp(this@GameProfilesActivity, 6) })
            }
            box.addView(tags)
            box.addView(UiKit.gap(this, 10))
            box.addView(UiKit.row(
                this,
                UiKit.smallButton(this, "应用并启动", true) { applyAndLaunch(p) },
                UiKit.smallButton(this, "删除") { store.delete(p.packageName); renderProfiles() }
            ))
            listBox.addView(box)
        }
    }

    private fun applyAndLaunch(p: GameProfile) {
        toast("正在应用 ${p.label} 配置")
        worker.execute {
            val result = perf.applyGameProfile(p)
            val launch = packageManager.getLaunchIntentForPackage(p.packageName)
            main.post {
                if (launch != null) {
                    startActivity(launch)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("配置已执行，但应用无法启动")
                        .setMessage(result)
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }
    }

    private fun restoreAll() {
        worker.execute {
            val a = perf.restoreSettings()
            val b = if (shell.hasPrivilegedWrite()) perf.restoreKernelNodes() else "内核参数：未授权 Shizuku 且无 Root，跳过"
            main.post {
                AlertDialog.Builder(this)
                    .setTitle("恢复结果")
                    .setMessage("$a\n\n$b")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun showLaunchFallback(t: Throwable) {
        val message = "游戏独立配置页启动失败\n\n${t.javaClass.simpleName}: ${t.message ?: "未知错误"}\n\n这个页面已被安全模式接管，不会再直接闪退。请截图此页给我。"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@GameProfilesActivity, 20), UiKit.dp(this@GameProfilesActivity, 28), UiKit.dp(this@GameProfilesActivity, 20), UiKit.dp(this@GameProfilesActivity, 28))
            setBackgroundColor(UiKit.BG)
            addView(UiKit.text(this@GameProfilesActivity, "游戏独立配置", 24f, UiKit.TEXT, true))
            addView(UiKit.text(this@GameProfilesActivity, "安全模式", 16f, UiKit.WARNING, true).apply { setPadding(0, UiKit.dp(this@GameProfilesActivity, 12), 0, 0) })
            addView(UiKit.text(this@GameProfilesActivity, message, 13f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@GameProfilesActivity, 14), 0, 0) })
            addView(UiKit.button(this@GameProfilesActivity, "返回首页", false) { finish() }.apply { setPadding(0, UiKit.dp(this@GameProfilesActivity, 12), 0, 0) })
        }
        setContentView(box)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
