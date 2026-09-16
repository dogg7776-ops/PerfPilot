from pathlib import Path

p = Path('buildsrc/PerfPilot/app/src/main/java/com/oai/perfpilot/MainActivity.kt')
s = p.read_text(encoding='utf-8')

s = s.replace(
    '    private var startOverlayAfterPermission = false\n    @Volatile private var metricsEnabled = false\n',
    '    private var startOverlayAfterPermission = false\n    private var shizukuListenerRegistered = false\n    @Volatile private var metricsEnabled = false\n'
)

s = s.replace('''    override fun onCreate(savedInstanceState: Bundle?) {
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
''')

s = s.replace('''    override fun onResume() {
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
''')

s = s.replace('''    override fun onDestroy() {
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
''')

s = s.replace(
    '        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.ifBlank { Build.HARDWARE } else Build.HARDWARE\n',
    '        val soc = try { if (Build.VERSION.SDK_INT >= 31) (Build.SOC_MODEL ?: Build.HARDWARE).ifBlank { Build.HARDWARE } else Build.HARDWARE } catch (_: Throwable) { Build.HARDWARE ?: "未知" }\n'
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
if marker not in s:
    raise SystemExit('toast marker not found')
s = s.replace(marker, fallback + marker)
p.write_text(s, encoding='utf-8')

g = Path('buildsrc/PerfPilot/app/build.gradle.kts')
t = g.read_text(encoding='utf-8')
t = t.replace('versionCode = 3', 'versionCode = 4')
t = t.replace('versionName = "0.3.0"', 'versionName = "0.3.1"')
g.write_text(t, encoding='utf-8')
