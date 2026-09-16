from pathlib import Path


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'patch target not found: {label}')
    return text.replace(old, new, 1)

# Main page crash guards
p = Path('buildsrc/PerfPilot/app/src/main/java/com/oai/perfpilot/MainActivity.kt')
s = p.read_text(encoding='utf-8')

s = replace_required(
    s,
    '    private var startOverlayAfterPermission = false\n    @Volatile private var metricsEnabled = false\n',
    '    private var startOverlayAfterPermission = false\n    private var shizukuListenerRegistered = false\n    @Volatile private var metricsEnabled = false\n',
    'MainActivity fields'
)

s = replace_required(s, '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiKit.applyWindow(this)
        shell = ShellEngine(this)
        perf = PerfController(this, shell)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContentView(buildUi())
        refreshStatus()
    }
''', '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { UiKit.applyWindow(this) } catch (_: Throwable) { }
        shell = ShellEngine(this)
        perf = PerfController(this, shell)
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            shizukuListenerRegistered = true
        } catch (_: Throwable) {
            shizukuListenerRegistered = false
        }
        try {
            setContentView(buildUi())
            refreshStatus()
        } catch (t: Throwable) {
            showLaunchFallback(t)
        }
    }
''', 'MainActivity onCreate')

s = replace_required(s, '''    override fun onResume() {
        super.onResume()
        if (startOverlayAfterPermission && Settings.canDrawOverlays(this)) {
            startOverlayAfterPermission = false
            startOverlay()
        }
        refreshStatus()
        startMetricsLoop()
    }
''', '''    override fun onResume() {
        super.onResume()
        try {
            if (startOverlayAfterPermission && Settings.canDrawOverlays(this)) {
                startOverlayAfterPermission = false
                startOverlay()
            }
            if (::shizukuValue.isInitialized) refreshStatus()
            if (::cpuValue.isInitialized) main.postDelayed({ startMetricsLoop() }, 350)
        } catch (_: Throwable) { }
    }
''', 'MainActivity onResume')

s = replace_required(s, '''    override fun onDestroy() {
        metricsEnabled = false
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        worker.shutdownNow()
        super.onDestroy()
    }
''', '''    override fun onDestroy() {
        metricsEnabled = false
        if (shizukuListenerRegistered) {
            try { Shizuku.removeRequestPermissionResultListener(permissionListener) } catch (_: Throwable) { }
        }
        worker.shutdownNow()
        super.onDestroy()
    }
''', 'MainActivity onDestroy')

s = s.replace(
    '        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.ifBlank { Build.HARDWARE } else Build.HARDWARE\n',
    '        val soc = try { if (Build.VERSION.SDK_INT >= 31) (Build.SOC_MODEL ?: Build.HARDWARE).ifBlank { Build.HARDWARE } else Build.HARDWARE } catch (_: Throwable) { Build.HARDWARE ?: "未知" }\n',
    1
)

marker = '    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()\n'
fallback = '''    private fun showLaunchFallback(t: Throwable) {
        val message = "PerfPilot 启动保护已接管\\n\\n${t.javaClass.simpleName}: ${t.message ?: "未知错误"}\\n\\n请截图这一页发给我，我可以继续定位。"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@MainActivity, 20), UiKit.dp(this@MainActivity, 28), UiKit.dp(this@MainActivity, 20), UiKit.dp(this@MainActivity, 28))
            setBackgroundColor(UiKit.BG)
            addView(UiKit.text(this@MainActivity, "PerfPilot", 26f, UiKit.TEXT, true))
            addView(UiKit.text(this@MainActivity, "安全启动模式", 16f, UiKit.WARNING, true).apply { setPadding(0, UiKit.dp(this@MainActivity, 12), 0, 0) })
            addView(UiKit.text(this@MainActivity, message, 13f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@MainActivity, 14), 0, 0) })
        }
        setContentView(box)
    }

'''
s = replace_required(s, marker, fallback + marker, 'MainActivity fallback marker')
p.write_text(s, encoding='utf-8')

# Game profiles page: v0.3.1 did not guard this Activity, so any UI/init exception closed it immediately.
gp = Path('buildsrc/PerfPilot/app/src/main/java/com/oai/perfpilot/GameProfilesActivity.kt')
g = gp.read_text(encoding='utf-8')
g = replace_required(g, '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiKit.applyWindow(this)
        shell = ShellEngine(this)
        perf = PerfController(this, shell)
        store = GameProfileStore(this)
        setContentView(buildUi())
        renderProfiles()
    }
''', '''    override fun onCreate(savedInstanceState: Bundle?) {
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
''', 'GameProfilesActivity onCreate')

gp_fallback = '''    private fun showLaunchFallback(t: Throwable) {
        val message = "游戏独立配置页启动失败\\n\\n${t.javaClass.simpleName}: ${t.message ?: "未知错误"}\\n\\n这个页面已被安全模式接管，不会再直接闪退。请截图此页给我。"
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

'''
g = replace_required(g, marker, gp_fallback + marker, 'GameProfilesActivity fallback marker')
gp.write_text(g, encoding='utf-8')

# Advanced page: guard both initial UI creation and every background probe. An uncaught worker-thread
# exception can terminate the Android process just like a main-thread exception.
ap = Path('buildsrc/PerfPilot/app/src/main/java/com/oai/perfpilot/AdvancedTuningActivity.kt')
a = ap.read_text(encoding='utf-8')
a = replace_required(a, '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiKit.applyWindow(this)
        shell = ShellEngine(this)
        perf = PerfController(this, shell)
        setContentView(buildUi())
        renderParams()
    }
''', '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            try { UiKit.applyWindow(this) } catch (_: Throwable) { }
            shell = ShellEngine(this)
            perf = PerfController(this, shell)
            setContentView(buildUi())
            renderParams()
        } catch (t: Throwable) {
            showLaunchFallback(t)
        }
    }
''', 'AdvancedTuningActivity onCreate')

a = replace_required(a, '''    private fun loadCurrent(spec: PerfController.KernelParamSpec, view: TextView, onReady: (Int?, Boolean) -> Unit) {
        worker.execute {
            val current = perf.readKernelParam(spec.path)?.toIntOrNull()
            val rootAvailable = shell.hasRoot()
            main.post {
                view.text = current?.toString() ?: "节点不存在 / 不可读"
                view.setTextColor(if (current != null) UiKit.PRIMARY else UiKit.MUTED)
                onReady(current, rootAvailable)
            }
        }
    }
''', '''    private fun loadCurrent(spec: PerfController.KernelParamSpec, view: TextView, onReady: (Int?, Boolean) -> Unit) {
        worker.execute {
            try {
                val current = perf.readKernelParam(spec.path)?.toIntOrNull()
                val rootAvailable = shell.hasRoot()
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    view.text = current?.toString() ?: "节点不存在 / 不可读"
                    view.setTextColor(if (current != null) UiKit.PRIMARY else UiKit.MUTED)
                    onReady(current, rootAvailable)
                }
            } catch (t: Throwable) {
                main.post {
                    if (!isFinishing && !isDestroyed) {
                        view.text = "读取失败：${t.javaClass.simpleName}"
                        view.setTextColor(UiKit.WARNING)
                        onReady(null, false)
                    }
                }
            }
        }
    }
''', 'AdvancedTuningActivity loadCurrent')

adv_fallback = '''    private fun showLaunchFallback(t: Throwable) {
        val message = "FPSGO / GED 页面启动失败\\n\\n${t.javaClass.simpleName}: ${t.message ?: "未知错误"}\\n\\n这个页面已被安全模式接管，不会再直接闪退。请截图此页给我。"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@AdvancedTuningActivity, 20), UiKit.dp(this@AdvancedTuningActivity, 28), UiKit.dp(this@AdvancedTuningActivity, 20), UiKit.dp(this@AdvancedTuningActivity, 28))
            setBackgroundColor(UiKit.BG)
            addView(UiKit.text(this@AdvancedTuningActivity, "FPSGO / GED 参数", 24f, UiKit.TEXT, true))
            addView(UiKit.text(this@AdvancedTuningActivity, "安全模式", 16f, UiKit.WARNING, true).apply { setPadding(0, UiKit.dp(this@AdvancedTuningActivity, 12), 0, 0) })
            addView(UiKit.text(this@AdvancedTuningActivity, message, 13f, UiKit.MUTED).apply { setPadding(0, UiKit.dp(this@AdvancedTuningActivity, 14), 0, 0) })
            addView(UiKit.button(this@AdvancedTuningActivity, "返回首页", false) { finish() }.apply { setPadding(0, UiKit.dp(this@AdvancedTuningActivity, 12), 0, 0) })
        }
        setContentView(box)
    }

'''
a = replace_required(a, marker, adv_fallback + marker, 'AdvancedTuningActivity fallback marker')
ap.write_text(a, encoding='utf-8')

# Version
gradle = Path('buildsrc/PerfPilot/app/build.gradle.kts')
t = gradle.read_text(encoding='utf-8')
t = t.replace('versionCode = 3', 'versionCode = 5', 1)
t = t.replace('versionName = "0.3.0"', 'versionName = "0.3.2"', 1)
gradle.write_text(t, encoding='utf-8')
