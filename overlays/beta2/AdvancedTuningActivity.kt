package com.oai.perfpilot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

class AdvancedTuningActivity : Activity() {
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);UiKit.applyWindow(this);setContentView(buildUi())}
    private fun buildUi():ScrollView{
        val scroll=ScrollView(this).apply{setBackgroundColor(UiKit.BG)}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(UiKit.dp(this@AdvancedTuningActivity,16),UiKit.dp(this@AdvancedTuningActivity,18),UiKit.dp(this@AdvancedTuningActivity,16),UiKit.dp(this@AdvancedTuningActivity,32))}
        root.addView(UiKit.row(this,UiKit.smallButton(this,"← 返回"){finish()},UiKit.chip(this,"PerfPilot 1.0",true)))
        root.addView(UiKit.sectionTitle(this,"底层性能参数","按原软件的 MTK FPSGO / GED / UClamp / C2PS / PowerHAL 能力重构；白色 UI 与明确状态。"))
        root.addView(UiKit.card(this,14,0xFFFFF7E8.toInt(),18).apply{addView(UiKit.text(this@AdvancedTuningActivity,"安全边界",13f,UiKit.WARNING,true));addView(UiKit.text(this@AdvancedTuningActivity,"热保护/MAGT 项只读，不停止温控服务，也不修改危险 trip point。",11.5f,UiKit.MUTED).apply{setPadding(0,UiKit.dp(this@AdvancedTuningActivity,6),0,0)})})
        PerfCatalog.categories.forEach{cat->root.addView(UiKit.gap(this,10));root.addView(UiKit.card(this,15).apply{isClickable=true;background=UiKit.ripple(this@AdvancedTuningActivity,UiKit.SURFACE,18);addView(UiKit.text(this@AdvancedTuningActivity,cat,15f,UiKit.TEXT,true));addView(UiKit.text(this@AdvancedTuningActivity,"${PerfCatalog.inCategory(cat).size} 个参数  ›",11.5f,UiKit.MUTED).apply{setPadding(0,UiKit.dp(this@AdvancedTuningActivity,6),0,0)});setOnClickListener{startActivity(Intent(this@AdvancedTuningActivity,ParameterActivity::class.java).putExtra("category",cat))}})}
        root.addView(UiKit.gap(this,14));root.addView(UiKit.button(this,"性能接口诊断",true){startActivity(Intent(this,DiagnosticsActivity::class.java))})
        scroll.addView(root);return scroll
    }
}
