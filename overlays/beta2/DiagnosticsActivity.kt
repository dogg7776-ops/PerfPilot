package com.oai.perfpilot

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class DiagnosticsActivity : Activity() {
    private lateinit var perf: PerfController
    private val worker=Executors.newSingleThreadExecutor(); private val main=Handler(Looper.getMainLooper())
    private lateinit var box:LinearLayout; private lateinit var reportView:TextView
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);UiKit.applyWindow(this);perf=PerfController(this,ShellEngine(this));setContentView(buildUi());runTests()}
    private fun buildUi():ScrollView{
        val scroll=ScrollView(this).apply{setBackgroundColor(UiKit.BG)}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(UiKit.dp(this@DiagnosticsActivity,16),UiKit.dp(this@DiagnosticsActivity,18),UiKit.dp(this@DiagnosticsActivity,16),UiKit.dp(this@DiagnosticsActivity,32))}
        root.addView(UiKit.row(this,UiKit.smallButton(this,"← 返回"){finish()},UiKit.smallButton(this,"重新检测",true){runTests()}))
        root.addView(UiKit.sectionTitle(this,"性能接口诊断","不修改任何参数，只检测 Shizuku、PowerHAL、FPSGO、GED 等实际能力。"))
        box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};root.addView(box)
        root.addView(UiKit.gap(this,12)); reportView=UiKit.text(this,"",11f,UiKit.MUTED); root.addView(reportView)
        root.addView(UiKit.gap(this,12));root.addView(UiKit.button(this,"复制诊断报告"){ val cm=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; cm.setPrimaryClip(ClipData.newPlainText("PerfPilot diagnostics",reportView.text)); Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show() })
        scroll.addView(root);return scroll
    }
    private fun runTests(){box.removeAllViews();box.addView(UiKit.card(this,14).apply{addView(UiKit.text(this@DiagnosticsActivity,"正在检测…",13f,UiKit.MUTED))});worker.execute{val list=perf.diagnostics();val report=buildString{append("PerfPilot 1.0 diagnostics\n");list.forEach{append("[${it.state}] ${it.name}: ${it.detail}\n")}};main.post{if(isFinishing||isDestroyed)return@post;box.removeAllViews();list.forEachIndexed{i,d->if(i>0)box.addView(UiKit.gap(this,8));val color=when(d.state){PerfController.Diagnostic.State.OK->UiKit.PRIMARY;PerfController.Diagnostic.State.WARN->UiKit.WARNING;PerfController.Diagnostic.State.FAIL->UiKit.DANGER};box.addView(UiKit.card(this,14).apply{addView(UiKit.text(this@DiagnosticsActivity,"${if(d.state==PerfController.Diagnostic.State.OK)"✓" else if(d.state==PerfController.Diagnostic.State.WARN)"!" else "✕"} ${d.name}",14f,color,true));addView(UiKit.text(this@DiagnosticsActivity,d.detail,11.5f,UiKit.MUTED).apply{setPadding(0,UiKit.dp(this@DiagnosticsActivity,6),0,0)})})};reportView.text=report}}}
    override fun onDestroy(){worker.shutdownNow();super.onDestroy()}
}
