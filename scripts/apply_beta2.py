from pathlib import Path
import shutil

repo = Path(__file__).resolve().parents[1]
root = repo / "buildsrc" / "perfpilot_v1"
java = root / "app/src/main/java/com/oai/perfpilot"
overlay = repo / "overlays/beta2"

for name in ["DeviceProfile.kt", "KernelNodeResolver.kt", "PerfManagerClient.kt", "PerfController.kt"]:
    shutil.copy2(overlay / name, java / name)

# Main screen: K90 Max identity + 165 Hz shortcut; do not expose cloud-component disabling.
p = java / "MainActivity.kt"
s = p.read_text(encoding="utf-8")
s = s.replace(
    "private lateinit var shizukuValue:TextView; private lateinit var perfValue:TextView; private lateinit var cpuValue:TextView;",
    "private lateinit var deviceValue:TextView; private lateinit var shizukuValue:TextView; private lateinit var perfValue:TextView; private lateinit var cpuValue:TextView;",
)
s = s.replace(
    'status.addView(UiKit.text(this,"连接状态",13f,UiKit.TEXT,true));status.addView(UiKit.gap(this,10))',
    'status.addView(UiKit.text(this,"设备与连接",13f,UiKit.TEXT,true));status.addView(UiKit.gap(this,10));deviceValue=UiKit.text(this,"设备：检测中…",12f,UiKit.MUTED);status.addView(deviceValue);status.addView(UiKit.gap(this,8))',
)
s = s.replace(
    'root.addView(UiKit.row(this,UiKit.button(this,"120 Hz",true){runTask("120Hz"){perf.setRefreshRate(120)}},UiKit.button(this,"60 Hz"){runTask("60Hz"){perf.setRefreshRate(60)}}))',
    'root.addView(UiKit.row(this,UiKit.button(this,"165 Hz",true){runTask("165Hz"){perf.setRefreshRate(165)}},UiKit.button(this,"120 Hz"){runTask("120Hz"){perf.setRefreshRate(120)}}))',
)
s = s.replace(
    '        root.addView(UiKit.gap(this,8));root.addView(tool("Xiaomi 云控","Joyose / PowerKeeper 云控组件开关"){cloudDialog()})\n',
    '',
)
old_refresh = '''    private fun refreshStatus(){if(!::shizukuValue.isInitialized||worker.isShutdown)return;worker.execute{try{val binder=shell.hasShizukuBinder();val perm=shell.hasShizukuPermission();val p=if(perm) shell.exec("service check perfmanager 2>&1",ShellEngine.Mode.SHIZUKU,2500) else null;main.post{if(isFinishing)return@post;shizukuValue.setTextColor(if(perm)UiKit.PRIMARY else if(binder)UiKit.WARNING else UiKit.DANGER);shizukuValue.text=when{perm->"Shizuku：● 已连接并授权（点击可刷新）";binder->"Shizuku：○ 已运行，点此授权";else->"Shizuku：✕ 未连接"};val found=p?.out?.contains("found",true)==true||p?.out?.contains("IBinder",true)==true;perfValue.setTextColor(if(found)UiKit.PRIMARY else UiKit.MUTED);perfValue.text="MTK perfmanager：${if(found)"● 可用" else "○ 未检测到 / 未授权"}"}}catch(_:Throwable){}}}
'''
new_refresh = '''    private fun refreshStatus(){if(!::shizukuValue.isInitialized||worker.isShutdown)return;worker.execute{try{val binder=shell.hasShizukuBinder();val perm=shell.hasShizukuPermission();val p=if(perm) shell.exec("service check perfmanager 2>&1",ShellEngine.Mode.SHIZUKU,2500) else null;val snap=perf.snapshot();main.post{if(isFinishing)return@post;deviceValue.text="${snap.profile.title}\\n${snap.identity.model} · ${snap.identity.soc.ifBlank{snap.identity.boardPlatform}}";deviceValue.setTextColor(if(snap.profile.id==DeviceProfiles.K90_MAX_DIMENSITY_9500.id)UiKit.PRIMARY else UiKit.MUTED);shizukuValue.setTextColor(if(perm)UiKit.PRIMARY else if(binder)UiKit.WARNING else UiKit.DANGER);shizukuValue.text=when{perm->"Shizuku：● 已连接并授权（点击可刷新）";binder->"Shizuku：○ 已运行，点此授权";else->"Shizuku：✕ 未连接"};val found=p?.out?.contains("found",true)==true||p?.out?.contains("IBinder",true)==true;perfValue.setTextColor(if(found)UiKit.PRIMARY else UiKit.MUTED);perfValue.text="MTK perfmanager：${if(found)"● 可用" else "○ 未检测到 / 未授权"}"}}catch(_:Throwable){}}}
'''
if old_refresh not in s:
    raise SystemExit("MainActivity refreshStatus patch target not found")
s = s.replace(old_refresh, new_refresh, 1)
s = s.replace('    private fun cloudDialog(){android.app.AlertDialog.Builder(this).setTitle("Xiaomi 云控").setItems(arrayOf("禁用相关云控组件","恢复相关云控组件")){_,i->runTask("Xiaomi 云控"){if(i==0)perf.disableXiaomiCloudOverrides()else perf.restoreXiaomiCloudOverrides()}}.setNegativeButton("取消",null).show()}\n', '')
p.write_text(s, encoding="utf-8")

# Per-game refresh choices come from the detected device profile.
p = java / "GameProfilesActivity.kt"
s = p.read_text(encoding="utf-8")
old = 'val options = listOf(0 to "不修改", 60 to "60", 90 to "90", 120 to "120", 144 to "144")'
new = 'val options = listOf(0 to "不修改") + perf.supportedRefreshRates().map { it to it.toString() }'
if old not in s:
    raise SystemExit("GameProfiles refresh options patch target not found")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")

# Version.
p = root / "app/build.gradle.kts"
s = p.read_text(encoding="utf-8")
s = s.replace('versionCode = 10', 'versionCode = 11', 1)
s = s.replace('versionName = "1.0.0-beta1"', 'versionName = "1.0.0-beta2-k90"', 1)
p.write_text(s, encoding="utf-8")

print("Applied PerfPilot 1.0 beta2 K90 Max overlay")
