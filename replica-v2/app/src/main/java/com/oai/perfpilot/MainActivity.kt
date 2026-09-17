package com.oai.perfpilot

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), ShizukuShell.Listener {
    private lateinit var shell: ShizukuShell
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell = ShizukuShell(this)
        setContentView(buildUi())
        shell.start(this)
    }

    override fun onDestroy() {
        shell.stop(this)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onStateChanged() = runOnUiThread {
        val state = when {
            !shell.binderAlive() -> "Shizuku：未连接"
            !shell.hasPermission() -> "Shizuku：已运行，未授权"
            !shell.isReady() -> "Shizuku：已授权，Shell 服务连接中"
            else -> "Shizuku：已连接 · UID ${shell.remoteUid() ?: "?"}"
        }
        statusText.text = state
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
            setBackgroundColor(Color.rgb(17, 19, 24))
        }

        root.addView(TextView(this).apply {
            text = "PerfPilot 2"
            textSize = 28f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "HyperOS / MediaTek · Shizuku Shell 调度实验版"
            textSize = 14f
            setTextColor(Color.rgb(180, 184, 194))
            setPadding(0, dp(2), 0, dp(12))
        })

        statusText = TextView(this).apply {
            text = "Shizuku：检测中"
            textSize = 15f
            setTextColor(Color.rgb(138, 180, 248))
        }
        root.addView(statusText)
        root.addView(rowButtons(
            button("连接 / 授权") {
                when {
                    !shell.binderAlive() -> toast("请先启动 Shizuku")
                    !shell.hasPermission() -> shell.requestPermission()
                    else -> shell.bind()
                }
            },
            button("验证 UID") { runCommand("id; id -u; getprop ro.product.model; getprop ro.build.version.release") }
        ))

        root.addView(section("设备与权限",
            action("完整设备探测", "确认机型、SoC、Android、HyperOS、Shell UID") {
                runCommand("""
                    echo "uid=$(id -u)"
                    id
                    echo "model=$(getprop ro.product.model)"
                    echo "device=$(getprop ro.product.device)"
                    echo "soc=$(getprop ro.soc.model)"
                    echo "sdk=$(getprop ro.build.version.sdk)"
                    echo "android=$(getprop ro.build.version.release)"
                    echo "miui=$(getprop ro.miui.ui.version.name)"
                    echo "hyperos=$(getprop ro.mi.os.version.name)"
                    for p in /sys/kernel/fpsgo/common/fpsgo_enable /sys/kernel/ged/hal/dvfs_margin_value /dev/cpuctl/top-app/cpu.uclamp.min; do
                      [ -e "${'$'}p" ] && echo "NODE ${'$'}p = $(cat "${'$'}p" 2>&1)"
                    done
                """.trimIndent())
            }
        ))

        root.addView(section("刷新率",
            action("60 Hz", "写入 MIUI / Android 刷新率设置并回读") { setRefreshRate(60) },
            action("90 Hz", "写入 MIUI / Android 刷新率设置并回读") { setRefreshRate(90) },
            action("120 Hz", "写入 MIUI / Android 刷新率设置并回读") { setRefreshRate(120) },
            action("读取刷新率设置", "不修改，只读取 secure/system 当前值") {
                runCommand("settings get secure miui_refresh_rate; settings get secure user_refresh_rate; settings get system peak_refresh_rate")
            }
        ))

        root.addView(section("MediaTek FPSGO",
            action("读取 FPSGO", "/sys/kernel/fpsgo/common 与关键 FBT 节点") {
                runCommand("""
                    for p in \
                    /sys/kernel/fpsgo/common/fpsgo_enable \
                    /sys/kernel/fpsgo/common/force_onoff \
                    /sys/module/mtk_fpsgo/parameters/cfp_onoff \
                    /sys/module/mtk_fpsgo/parameters/boost_affinity \
                    /sys/kernel/fpsgo/fbt/cpumask_heavy \
                    /sys/kernel/fpsgo/fbt/cpumask_second \
                    /sys/kernel/fpsgo/fbt/cpumask_others; do
                      if [ -e "${'$'}p" ]; then echo "${'$'}p = $(cat "${'$'}p" 2>&1)"; else echo "UNSUPPORTED ${'$'}p"; fi
                    done
                """.trimIndent())
            },
            action("开启 FPSGO", "写入 fpsgo_enable=1，并立即回读验证") {
                verifiedWrite("/sys/kernel/fpsgo/common/fpsgo_enable", "1")
            },
            action("关闭 FPSGO", "写入 fpsgo_enable=0，并立即回读验证") {
                verifiedWrite("/sys/kernel/fpsgo/common/fpsgo_enable", "0")
            },
            action("开启 CFP", "MediaTek CPU Frequency Policy") {
                verifiedWrite("/sys/module/mtk_fpsgo/parameters/cfp_onoff", "1")
            },
            action("关闭 CFP", "恢复 cfp_onoff=0") {
                verifiedWrite("/sys/module/mtk_fpsgo/parameters/cfp_onoff", "0")
            }
        ))

        root.addView(section("GPU / GED",
            action("读取 GED DVFS", "Margin / loading / workload / DCS 全部回读") {
                runCommand("""
                    for p in \
                    /sys/kernel/ged/hal/dvfs_margin_value \
                    /sys/kernel/ged/hal/timer_base_dvfs_margin \
                    /sys/kernel/ged/hal/loading_base_dvfs_step \
                    /sys/kernel/ged/hal/dvfs_loading_mode \
                    /sys/kernel/ged/hal/dvfs_workload_mode \
                    /sys/kernel/ged/hal/dcs_mode \
                    /sys/kernel/ged/hal/force_loading_base; do
                      if [ -e "${'$'}p" ]; then echo "${'$'}p = $(cat "${'$'}p" 2>&1)"; else echo "UNSUPPORTED ${'$'}p"; fi
                    done
                """.trimIndent())
            },
            action("DCS = 64", "对应原 APK 的 GPU DCS 可选值之一；写后回读") {
                verifiedWrite("/sys/kernel/ged/hal/dcs_mode", "64")
            },
            action("DCS = 0", "恢复 DCS 0；写后回读") {
                verifiedWrite("/sys/kernel/ged/hal/dcs_mode", "0")
            }
        ))

        root.addView(section("Joyose / PowerKeeper",
            action("停用性能云控组件", "复刻原 APK 的 package Binder 调用；按 Android 版本选择 transaction") {
                runCloudControl(disable = true)
            },
            action("恢复性能云控组件", "把原 APK 禁掉的组件恢复为默认状态") {
                runCloudControl(disable = false)
            },
            action("读取相关包状态", "检查 Joyose / PowerKeeper 是否存在") {
                runCommand("pm path com.xiaomi.joyose; pm path com.miui.powerkeeper; dumpsys package com.xiaomi.joyose | grep -E 'enabled=|CloudServerReceiver|GameInfoService|BoostRequestReceiver' | head -80")
            }
        ))

        root.addView(section("充电 / 触感",
            action("智能充电 ON", "调用 Xiaomi IMiCharge smart_chg=1；输出原始 Parcel 结果") { smartCharge("1") },
            action("智能充电 OFF", "调用 Xiaomi IMiCharge smart_chg=0；输出原始 Parcel 结果") { smartCharge("0") },
            action("增强触感属性", "来自原 APK vibrate_boost.sh；不自动重启 zygote") {
                runCommand("""
                    setprop sys.haptic.dynamiceffect true
                    setprop sys.haptic.dynamiceffect.richtap true
                    setprop sys.haptic.infinitelevel true
                    setprop sys.haptic.intensityforkeyboard true
                    setprop sys.haptic.motor linear
                    setprop sys.haptic.media true
                    setprop sys.haptic.tap.normal 1,2
                    setprop sys.haptic.long.press 1,2
                    echo "richtap=$(getprop sys.haptic.dynamiceffect.richtap)"
                    echo "motor=$(getprop sys.haptic.motor)"
                    echo "tap=$(getprop sys.haptic.tap.normal)"
                """.trimIndent())
            }
        ))

        root.addView(section("监控",
            action("SurfaceFlinger 延迟", "读取当前 SurfaceFlinger latency 数据") {
                runCommand("dumpsys SurfaceFlinger --latency 2>&1 | head -80")
            },
            action("温度节点", "只读取，不关闭温控、不修改 trip point") {
                runCommand("for z in /sys/class/thermal/thermal_zone*; do t=$(cat \"${'$'}z/type\" 2>/dev/null); v=$(cat \"${'$'}z/temp\" 2>/dev/null); [ -n \"${'$'}v\" ] && echo \"${'$'}t=${'$'}v\"; done | head -100")
            },
            action("Top-app uclamp", "读取 Android cgroup 调度夹值") {
                runCommand("for p in /dev/cpuctl/top-app/cpu.uclamp.min /dev/cpuctl/top-app/cpu.uclamp.max /dev/cpuctl/foreground/cpu.uclamp.min /dev/cpuctl/background/cpu.uclamp.min; do [ -e \"${'$'}p\" ] && echo \"${'$'}p=$(cat \"${'$'}p\" 2>&1)\"; done")
            }
        ))

        val commandBox = EditText(this).apply {
            hint = "输入 Shell 命令（通过 Shizuku UID 2000 执行）"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(false)
            minLines = 2
        }
        root.addView(section("命令实验室",
            commandBox,
            button("执行并显示退出码") {
                val cmd = commandBox.text.toString().trim()
                if (cmd.isNotEmpty()) runCommand(cmd)
            }
        ))

        logText = TextView(this).apply {
            text = "执行结果会显示在这里。每次写底层节点都尽量做回读验证。"
            textSize = 12f
            setTextColor(Color.rgb(210, 214, 224))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(section("执行日志", logText))

        return ScrollView(this).apply { addView(root) }
    }

    private fun setRefreshRate(hz: Int) {
        runCommand("""
            settings put secure miui_refresh_rate $hz
            settings put secure user_refresh_rate $hz
            settings put system peak_refresh_rate ${hz}.0
            echo "miui=$(settings get secure miui_refresh_rate)"
            echo "user=$(settings get secure user_refresh_rate)"
            echo "peak=$(settings get system peak_refresh_rate)"
        """.trimIndent())
    }

    private fun smartCharge(value: String) {
        runCommand("service call vendor.xiaomi.hardware.micharge.IMiCharge/default 43 s16 'smart_chg' s16 '$value'")
    }

    private fun runCloudControl(disable: Boolean) {
        val tx = when (Build.VERSION.SDK_INT) {
            34 -> 83
            35 -> 86
            else -> 87
        }
        val state = if (disable) 2 else 0
        val components = listOf(
            "com.miui.powerkeeper|com.miui.powerkeeper.cloudcontrol.CloudUpdateReceiver",
            "com.miui.powerkeeper|com.miui.powerkeeper.cloudcontrol.CloudUpdateJobService",
            "com.xiaomi.joyose|com.xiaomi.joyose.cloud.CloudServerReceiver",
            "com.xiaomi.joyose|com.xiaomi.joyose.JoyoseJobScheduleService",
            "com.xiaomi.joyose|com.xiaomi.joyose.smartop.smartp.SmartPAlarmReceiver",
            "com.xiaomi.joyose|com.xiaomi.joyose.smartop.gamebooster.receiver.BoostRequestReceiver",
            "com.xiaomi.joyose|com.xiaomi.joyose.smartop.provider.GameInfoProvider",
            "com.xiaomi.joyose|com.xiaomi.joyose.gameInfo.GameInfoService"
        )
        val cmd = buildString {
            append("echo sdk=").append(Build.VERSION.SDK_INT).append(" tx=").append(tx).append('\n')
            for (item in components) {
                val (pkg, cls) = item.split('|')
                append("echo '--- ").append(cls).append(" ---'\n")
                append("service call package ").append(tx)
                    .append(" i32 1 s16 ").append(pkg)
                    .append(" s16 ").append(cls)
                    .append(" i32 ").append(state)
                    .append(" i32 0 i32 0 s16 shell\n")
            }
        }
        runCommand(cmd)
    }

    private fun verifiedWrite(path: String, value: String) {
        runAsync("写入 $path") { shell.verifiedWrite(path, value) }
    }

    private fun runCommand(command: String) {
        runAsync(command.lineSequence().firstOrNull()?.take(72) ?: "Shell") { shell.run(command) }
    }

    private fun runAsync(label: String, block: () -> ShizukuShell.Result) {
        if (!shell.isReady()) {
            toast("Shizuku Shell 尚未连接")
            if (shell.hasPermission()) shell.bind()
            return
        }
        logText.text = "执行中：$label"
        worker.execute {
            val result = block()
            runOnUiThread {
                logText.text = "exit=${result.exitCode}\n${result.output.ifBlank { "(无输出)" }}"
                if (!result.ok) Snackbar.make(logText, "命令退出码 ${result.exitCode}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun section(title: String, vararg children: View): MaterialCardView {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        body.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        })
        children.forEach { child ->
            val parent = child.parent
            if (parent is android.view.ViewGroup) parent.removeView(child)
            body.addView(child)
        }
        return MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.rgb(29, 32, 39))
            strokeWidth = 1
            setStrokeColor(Color.rgb(58, 62, 72))
            addView(body)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, 0) }
        }
    }

    private fun action(title: String, subtitle: String, onClick: () -> Unit): View {
        val button = MaterialButton(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
            addView(button)
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.rgb(166, 170, 180))
                setPadding(dp(4), dp(2), dp(4), 0)
            })
        }
    }

    private fun button(textValue: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = textValue
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun rowButtons(vararg views: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { v ->
            addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(6), dp(4), 0) })
        }
    }

    private fun toast(text: String) = Snackbar.make(statusText, text, Snackbar.LENGTH_LONG).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
