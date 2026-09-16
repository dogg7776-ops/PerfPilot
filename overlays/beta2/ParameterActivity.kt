package com.oai.perfpilot

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class ParameterActivity : Activity() {
    private lateinit var shell: ShellEngine
    private lateinit var perf: PerfController
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiKit.applyWindow(this)
        shell = ShellEngine(this); perf = PerfController(this, shell)
        val category = intent.getStringExtra("category") ?: PerfCatalog.categories.first()
        setContentView(buildUi(category))
    }

    private fun buildUi(category:String): ScrollView {
        val scroll=ScrollView(this).apply{setBackgroundColor(UiKit.BG)}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; setPadding(UiKit.dp(this@ParameterActivity,16),UiKit.dp(this@ParameterActivity,18),UiKit.dp(this@ParameterActivity,16),UiKit.dp(this@ParameterActivity,32))}
        root.addView(UiKit.row(this,UiKit.smallButton(this,"← 返回"){finish()},UiKit.chip(this,"${PerfCatalog.inCategory(category).size} 项",true)))
        root.addView(UiKit.sectionTitle(this,category,"优先读取真实节点；节点未暴露时通过 MTK perfmanager / PowerHAL 写入。"))
        PerfCatalog.inCategory(category).forEachIndexed { index,param ->
            if(index>0) root.addView(UiKit.gap(this,10))
            root.addView(paramCard(param))
        }
        scroll.addView(root); return scroll
    }

    private fun paramCard(param:PerfParam): LinearLayout {
        val card=UiKit.card(this,15,UiKit.SURFACE,18)
        val title=UiKit.text(this,param.title,15f,UiKit.TEXT,true)
        val idText=param.resourceId?.let{"0x${it.toUInt().toString(16).padStart(8,'0')}"} ?: "sysfs"
        val state=UiKit.text(this,"正在读取…",12.5f,UiKit.MUTED)
        card.addView(UiKit.row(this,title,UiKit.chip(this,idText,param.resourceId!=null)))
        card.addView(UiKit.text(this,param.description,11.8f,UiKit.MUTED).apply{setPadding(0,UiKit.dp(this@ParameterActivity,8),0,0)})
        card.addView(state.apply{setPadding(0,UiKit.dp(this@ParameterActivity,10),0,0)})
        if(param.writable){
            val input=EditText(this).apply{
                hint=when { param.min!=null&&param.max!=null -> "${param.min} – ${param.max}"; else -> "输入整数值" }
                inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                setTextColor(UiKit.TEXT); setHintTextColor(UiKit.DIM); textSize=14f
                background=UiKit.rounded(UiKit.SURFACE_3,12,this@ParameterActivity)
                setPadding(UiKit.dp(this@ParameterActivity,12),0,UiKit.dp(this@ParameterActivity,12),0)
            }
            val write=UiKit.smallButton(this,"写入",true){
                val v=input.text.toString().trim().toIntOrNull(); if(v==null){toast("请输入整数");return@smallButton}
                state.text="正在写入…"; worker.execute{
                    val r=perf.writeParam(param,v)
                    main.post{ state.setTextColor(if(r.ok) UiKit.PRIMARY else UiKit.DANGER); state.text="${if(r.ok) "✓" else "✕"} ${r.source}：${r.detail}"; if(r.ok) input.setText(v.toString()) }
                }
            }
            card.addView(UiKit.gap(this,10)); card.addView(UiKit.row(this,input,write))
        } else {
            card.addView(UiKit.text(this,"只读：为安全起见不提供热保护阈值写入。",11.5f,UiKit.WARNING,true).apply{setPadding(0,UiKit.dp(this@ParameterActivity,10),0,0)})
        }
        worker.execute{
            val r=perf.readParam(param)
            main.post{
                if(isFinishing||isDestroyed)return@post
                state.setTextColor(if(r.ok) UiKit.PRIMARY else UiKit.MUTED)
                state.text=if(r.ok) "当前值：${r.value}  ·  ${r.source}${r.path?.let{"\n$it"}?:""}" else "${r.source}：${r.detail}"
            }
        }
        return card
    }

    override fun onDestroy(){worker.shutdownNow();super.onDestroy()}
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
