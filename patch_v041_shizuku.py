from pathlib import Path


def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'patch target not found: {label}')
    return text.replace(old, new, 1)

root = Path('buildsrc/PerfPilot')

# 1) Register the Shizuku provider. The dependency only contributes permission/meta-data;
# the app still needs its own exported provider so the Shizuku server can deliver Binder.
manifest = root / 'app/src/main/AndroidManifest.xml'
m = manifest.read_text(encoding='utf-8')
provider = '''        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:multiprocess="false"
            android:enabled="true"
            android:exported="true"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
'''
if 'android:name="rikka.shizuku.ShizukuProvider"' not in m:
    m = replace_required(m, '    </application>\n', provider + '    </application>\n', 'manifest application close')
manifest.write_text(m, encoding='utf-8')

# 2) Listen for Binder arrival/death, because Binder delivery is asynchronous.
main = root / 'app/src/main/java/com/oai/perfpilot/MainActivity.kt'
s = main.read_text(encoding='utf-8')

s = replace_required(
    s,
    '''    private var shizukuListenerRegistered = false
    @Volatile private var metricsEnabled = false
''',
    '''    private var shizukuListenerRegistered = false
    private var shizukuBinderListenersRegistered = false
    @Volatile private var metricsEnabled = false
''',
    'main listener fields'
)

permission_block = '''    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == permCode) {
            shell.invalidateModeCache()
            toast(if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权" else "Shizuku 未授权")
            refreshStatus()
        }
    }
'''
listener_block = permission_block + '''
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (::shell.isInitialized) shell.invalidateModeCache()
        refreshStatus()
        main.postDelayed({ refreshStatus() }, 250)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (::shell.isInitialized) shell.invalidateModeCache()
        refreshStatus()
    }
'''
s = replace_required(s, permission_block, listener_block, 'binder lifecycle listeners')

register_old = '''        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            shizukuListenerRegistered = true
        } catch (_: Throwable) {
            shizukuListenerRegistered = false
        }
'''
register_new = register_old + '''        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            shizukuBinderListenersRegistered = true
        } catch (_: Throwable) {
            shizukuBinderListenersRegistered = false
        }
'''
s = replace_required(s, register_old, register_new, 'register binder listeners')

s = replace_required(
    s,
    '''            setContentView(buildUi())
            refreshStatus()
''',
    '''            setContentView(buildUi())
            refreshStatus()
            main.postDelayed({ refreshStatus() }, 600)
            main.postDelayed({ refreshStatus() }, 1600)
''',
    'delayed status refresh'
)

s = replace_required(
    s,
    '''        if (shizukuListenerRegistered) {
            try { Shizuku.removeRequestPermissionResultListener(permissionListener) } catch (_: Throwable) { }
        }
        worker.shutdownNow()
''',
    '''        if (shizukuListenerRegistered) {
            try { Shizuku.removeRequestPermissionResultListener(permissionListener) } catch (_: Throwable) { }
        }
        if (shizukuBinderListenersRegistered) {
            try { Shizuku.removeBinderReceivedListener(binderReceivedListener) } catch (_: Throwable) { }
            try { Shizuku.removeBinderDeadListener(binderDeadListener) } catch (_: Throwable) { }
        }
        worker.shutdownNow()
''',
    'remove binder listeners'
)

status_old = '''        val a = tile("Shizuku"); shizukuValue = a.second
        val b = tile("Root"); rootValue = b.second
        val c = tile("悬浮窗"); overlayValue = c.second
        return UiKit.row(this, a.first, b.first, c.first, gapDp = 7)
'''
status_new = '''        val a = tile("Shizuku"); shizukuValue = a.second
        a.first.setOnClickListener {
            try {
                when {
                    !shell.hasShizukuBinder() -> {
                        refreshStatus()
                        toast("尚未收到 Shizuku 连接；确认 Shizuku 正在运行后稍等 1 秒")
                    }
                    shell.hasShizukuPermission() -> toast("Shizuku 已连接并授权")
                    else -> shell.requestShizukuPermission(permCode)
                }
            } catch (t: Throwable) {
                toast("Shizuku 状态读取失败：${t.javaClass.simpleName}")
            }
        }
        val b = tile("Root"); rootValue = b.second
        val c = tile("悬浮窗"); overlayValue = c.second
        return UiKit.row(this, a.first, b.first, c.first, gapDp = 7)
'''
s = replace_required(s, status_old, status_new, 'clickable Shizuku status tile')

s = replace_required(
    s,
    '''                    setState(shizukuValue, shizuku, if (shizuku) "● 已授权" else if (binder) "○ 未授权" else "○ 未运行")
''',
    '''                    setState(shizukuValue, shizuku, if (shizuku) "● 已连接" else if (binder) "○ 已运行·未授权" else "○ 未连接")
''',
    'Shizuku status wording'
)

main.write_text(s, encoding='utf-8')

# 3) Version bump
gradle = root / 'app/build.gradle.kts'
g = gradle.read_text(encoding='utf-8')
g = g.replace('versionCode = 5', 'versionCode = 6', 1)
g = g.replace('versionName = "0.4.0"', 'versionName = "0.4.1"', 1)
gradle.write_text(g, encoding='utf-8')
