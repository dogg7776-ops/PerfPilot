package com.oai.perfpilot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var shell:ShellEngine; private lateinit var perf:PerfController
    private val worker=Executors.newSingleThreadExecutor(); private val main=Handler(Looper.getMainLooper())
    private lateinit var deviceValue:TextView; private lateinit var shizukuValue:TextView; private lateinit var perfValue:TextView; private lateinit var cpuValue:TextView; private lateinit var gpuValue:TextView; private lateinit var tempValue:TextView; private lateinit var logText:TextView
    private var shizukuListenerRegistered=false; private var binderListenersRegistered=false; @Volatile private var metricsRunning=false
    private val permissionListener=Shizuku.OnRequestPermissionResultListener { _, grant -> shell.invalidateModeCache(); toast(if(grant==PackageManager.PERMISSION_GRANTED)"Shizuku 已授权" else "Shizuku 未授权"); refreshStatus() }
    private val binderReceived=Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val binderDead=Shizuku.OnBinderDeadListener { refreshStatus() }

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);try{UiKit.applyWindow(this)}catch(_:Throwable){};shell=ShellEngine(this);perf=PerfController(this,shell);try{Shizuku.addRequestPermissionResultListener(permissionListener);shizukuListenerRegistered=true;Shizuku.addBinderReceivedListener(binderReceived);Shizuku.addBinderDeadListener(binderDead);binderListenersRegistered=true}catch(_:Throwable){};try{setContentView(buildUi());refreshStatus()}catch(t:Throwable){showFallback(t)}}

    private fun buildUi():ScrollView{
        val scroll=ScrollView(this).apply{setBackgroundColor(UiKit.BG)}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(UiKit.dp(this@MainActivity,16),UiKit.dp(this@MainActivity,18),UiKit.dp(this@MainActivity,16),UiKit.dp(this@MainActivity,34))}
        root.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(UiKit.text(this@MainActivity,"PerfPilot",28f,UiKit.TEXT,true));addView(UiKit.text(this@MainActivity,"MTK / Xiaomi 性能调度重构版",12.5f,UiKit.MUTED).apply{setPadding(0,UiKit.dp(this@MainActivity,5),0,0)})})
        root.addView(UiKit.gap(this,16))
        val status=UiKit.card(this,15)
        status.addView(UiKit.text(this,"设备与连接",13f,UiKit.TEXT,true));status.addView(UiKit.gap(this,10));deviceValue=UiKit.text(this,"设备：检测中…",12f,UiKit.MUTED);status.addView(deviceValue);status.addView(UiKit.gap(this,8))
        shizukuValue=UiKit.text(this,"Shizuku：检测中…",13f,UiKit.MUTED,true).apply{background=UiKit.ripple(this@MainActivity,UiKit.SURFACE_3,12);setPadding(UiKit.dp(this@MainActivity,12),UiKit.dp(this@MainActivity,12),UiKit.dp(this@MainActivity,12),UiKit.dp(this@MainActivity,12));setOnClickListener{requestShizuku()}}
        perfValue=UiKit.text(this,"MTK perfmanager：检测中…",13f,UiKit.MUTED,true).apply{setPadding(0,UiKit.dp(this@MainActivity,10),0,0)}
        status.addView(shizukuValue);status.addView(perfValue);root.addView(status)
        root.addView(UiKit.sectionTitle(this,"实时状态","读取不到就显示 N/A，不伪造数据。"))
        val cpuTile=metric("CPU","N/A"); cpuValue=cpuTile.second
        val gpuTile=metric("GPU","N/A"); gpuValue=gpuTile.second
        val tempTile=metric("温度","N/A"); tempValue=tempTile.second
        val fpsTile=metric("FPS","N/A")
        root.addView(UiKit.row(this,cpuTile.first,gpuTile.first));root.addView(UiKit.gap(this,8));root.addView(UiKit.row(this,tempTile.first,fpsTile.first))
        root.addView(UiKit.sectionTitle(this,"快捷操作"))
        root.addView(UiKit.row(this,UiKit.button(this,"165 Hz",true){runTask("165Hz"){perf.setRefreshRate(165)}},UiKit.button(this,"120 Hz"){runTask("120Hz"){perf.setRefreshRate(120)}}))
        root.addView(UiKit.gap(this,8));root.addView(UiKit.row(this,UiKit.button(this,"游戏模式",true){runTask("游戏模式"){perf.safeGameProfile()}},UiKit.button(this,"性能悬浮窗"){toggleOverlay()}))
        root.addView(UiKit.sectionTitle(this,"工具"))
        root.addView(tool("底层性能参数","FPSGO / FBT / XGF / GED / UClamp / C2PS / PowerHAL"){startActivity(Intent(this,AdvancedTuningActivity::class.java))})
        root.addView(UiKit.gap(this,8));root.addView(tool("游戏独立配置","按应用保存刷新率、FPSGO 与 CPU 策略"){startActivity(Intent(this,GameProfilesActivity::class.java))})
        root.addView(UiKit.gap(this,8));root.addView(tool("性能接口诊断","检查 Shizuku UID、perfmanager、PowerHAL、FPSGO、GED"){startActivity(Intent(this,DiagnosticsActivity::class.java))})
                root.addView(UiKit.gap(this,8));root.addView(tool("CPU Governor","优先 Shizuku；无权限时不强制 Root"){governorDialog()})
        root.addView(UiKit.gap(this,8));root.addView(tool("全部恢复","恢复设置、sysfs 备份并释放 PowerHAL handle"){runTask("恢复"){perf.restoreAll()}})
        root.addView(UiKit.sectionTitle(this,"执行结果"));logText=UiKit.text(this,"等待操作。",11.5f,UiKit.MUTED);root.addView(UiKit.card(this,14).apply{addView(logText)})
        root.addView(UiKit.gap(this,14));root.addView(UiKit.card(this,14,0xFFFFF7E8.toInt(),18).apply{addView(UiKit.text(this@MainActivity,"安全说明",13f,UiKit.WARNING,true));addView(UiKit.text(this@MainActivity,"本版不会停止温控服务，也不会把温控 trip point 改到危险温度；原软件的这类功能不复刻。",11.5f,UiKit.MUTED).apply{setPadding(0,UiKit.dp(this@MainActivity,6),0,0)})})
        scroll.addView(root);return scroll
    }

    private fun metric(label:String,value:String): Pair<LinearLayout,TextView> {
        val valueView=UiKit.text(this,value,15f,UiKit.TEXT,true)
        val card=UiKit.card(this,12,UiKit.SURFACE,16).apply{
            addView(UiKit.text(this@MainActivity,label,10.5f,UiKit.MUTED,true))
            addView(valueView.apply{setPadding(0,UiKit.dp(this@MainActivity,5),0,0)})
        }
        return card to valueView
    }
    private fun tool(title:String,sub:String,click:()->Unit)=UiKit.card(this,14).apply{isClickable=true;background=UiKit.ripple(this@MainActivity,UiKit.SURFACE,18);addView(UiKit.text(this@MainActivity,title,14.5f,UiKit.TEXT,true));addView(UiKit.text(this@MainActivity,"$sub  ›",11.5f,UiKit.MUTED).apply{setPadding(0,UiKit.dp(this@MainActivity,5),0,0)});setOnClickListener{click()}}

    private fun refreshStatus(){if(!::shizukuValue.isInitialized||worker.isShutdown)return;worker.execute{try{val binder=shell.hasShizukuBinder();val perm=shell.hasShizukuPermission();val p=if(perm) shell.exec("service check perfmanager 2>&1",ShellEngine.Mode.SHIZUKU,2500) else null;val snap=perf.snapshot();main.post{if(isFinishing)return@post;deviceValue.text="${snap.profile.title}\n${snap.identity.model} · ${snap.identity.soc.ifBlank{snap.identity.boardPlatform}}";deviceValue.setTextColor(if(snap.profile.id==DeviceProfiles.K90_MAX_DIMENSITY_9500.id)UiKit.PRIMARY else UiKit.MUTED);shizukuValue.setTextColor(if(perm)UiKit.PRIMARY else if(binder)UiKit.WARNING else UiKit.DANGER);shizukuValue.text=when{perm->"Shizuku：● 已连接并授权（点击可刷新）";binder->"Shizuku：○ 已运行，点此授权";else->"Shizuku：✕ 未连接"};val found=p?.out?.contains("found",true)==true||p?.out?.contains("IBinder",true)==true;perfValue.setTextColor(if(found)UiKit.PRIMARY else UiKit.MUTED);perfValue.text="MTK perfmanager：${if(found)"● 可用" else "○ 未检测到 / 未授权"}"}}catch(_:Throwable){}}}
    private fun requestShizuku(){try{if(shell.hasShizukuBinder()&&!shell.hasShizukuPermission())shell.requestShizukuPermission(5001)else refreshStatus()}catch(t:Throwable){toast(t.message?:"Shizuku 调用失败")}}

    override fun onResume(){super.onResume();refreshStatus();metricsRunning=true;metricsLoop()}
    override fun onPause(){metricsRunning=false;super.onPause()}
    private fun metricsLoop(){if(!metricsRunning||worker.isShutdown)return;worker.execute{val m=try{perf.liveMetrics()}catch(_:Throwable){null};main.post{if(!metricsRunning||m==null)return@post;cpuValue.text="CPU\n${m.cpu.take(50)}";gpuValue.text="GPU\n${m.gpu}";tempValue.text="温度\n${m.temp}";main.postDelayed({metricsLoop()},3000)}}}
    private fun runTask(name:String,block:()->String){logText.text="$name：执行中…";worker.execute{val s=try{block()}catch(t:Throwable){"${t.javaClass.simpleName}: ${t.message}"};main.post{if(!isFinishing)logText.text="$name\n$s"}}}
    private fun governorDialog(){val arr=arrayOf("schedutil","performance","powersave");android.app.AlertDialog.Builder(this).setTitle("CPU Governor").setItems(arr){_,i->runTask("CPU Governor"){perf.setCpuGovernor(arr[i])}}.setNegativeButton("取消",null).show()}
    private fun toggleOverlay(){if(!Settings.canDrawOverlays(this)){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")));toast("请允许悬浮窗后返回");return};if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),6002);startForegroundService(Intent(this,OverlayService::class.java));toast("已启动悬浮窗")}
    private fun showFallback(t:Throwable){setContentView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(36,60,36,36);setBackgroundColor(UiKit.BG);addView(UiKit.text(this@MainActivity,"PerfPilot 启动保护",24f,UiKit.DANGER,true));addView(UiKit.text(this@MainActivity,"${t.javaClass.simpleName}: ${t.message}",13f,UiKit.MUTED).apply{setPadding(0,20,0,0)})})}
    override fun onDestroy(){metricsRunning=false;if(shizukuListenerRegistered)try{Shizuku.removeRequestPermissionResultListener(permissionListener)}catch(_:Throwable){};if(binderListenersRegistered){try{Shizuku.removeBinderReceivedListener(binderReceived)}catch(_:Throwable){};try{Shizuku.removeBinderDeadListener(binderDead)}catch(_:Throwable){}};worker.shutdownNow();super.onDestroy()}
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
