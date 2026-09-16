package com.oai.perfpilot

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.TextView

object UiKit {
    const val BG = 0xFFF7F8FA.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val SURFACE_2 = 0xFFF1F3F5.toInt()
    const val SURFACE_3 = 0xFFFAFBFC.toInt()
    const val STROKE = 0xFFDDE2E7.toInt()
    const val PRIMARY = 0xFF0F8A68.toInt()
    const val PRIMARY_DARK = 0xFFE8F7F1.toInt()
    const val TEXT = 0xFF172126.toInt()
    const val MUTED = 0xFF65737B.toInt()
    const val DIM = 0xFF98A3AA.toInt()
    const val DANGER = 0xFFD64545.toInt()
    const val WARNING = 0xFFB26A00.toInt()
    const val INFO = 0xFF246BCE.toInt()

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun applyWindow(activity: Activity) {
        activity.window.statusBarColor = BG
        activity.window.navigationBarColor = SURFACE
        if (Build.VERSION.SDK_INT >= 30) {
            activity.window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    fun rounded(fill: Int, radiusDp: Int, context: Context, stroke: Int? = STROKE, strokeWidthDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(context, strokeWidthDp), stroke)
        }

    fun ripple(context: Context, fill: Int, radiusDp: Int, stroke: Int? = STROKE): RippleDrawable {
        val base = rounded(fill, radiusDp, context, stroke)
        return RippleDrawable(ColorStateList.valueOf(0x180F8A68), base, null)
    }

    fun text(context: Context, value: String, sizeSp: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
        }

    fun card(context: Context, paddingDp: Int = 16, fill: Int = SURFACE, radiusDp: Int = 18): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp))
            background = rounded(fill, radiusDp, context)
            elevation = dp(context, 1).toFloat()
        }

    fun button(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        text(context, label, 14f, if (primary) Color.WHITE else TEXT, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(context, 48)
            setPadding(dp(context, 14), 0, dp(context, 14), 0)
            background = ripple(context, if (primary) PRIMARY else SURFACE, 14, if (primary) PRIMARY else STROKE)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun smallButton(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        text(context, label, 12.5f, if (primary) Color.WHITE else TEXT, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(context, 38)
            setPadding(dp(context, 11), 0, dp(context, 11), 0)
            background = ripple(context, if (primary) PRIMARY else SURFACE, 12, if (primary) PRIMARY else STROKE)
            setOnClickListener { onClick() }
        }

    fun chip(context: Context, label: String, active: Boolean = false, danger: Boolean = false): TextView {
        val fg = when { danger -> DANGER; active -> PRIMARY; else -> MUTED }
        val bg = when { danger -> 0xFFFFEEEE.toInt(); active -> PRIMARY_DARK; else -> SURFACE_2 }
        return text(context, label, 11.5f, fg, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(context, 30)
            setPadding(dp(context, 10), 0, dp(context, 10), 0)
            background = rounded(bg, 999, context, if (active) 0xFFB7E3D2.toInt() else STROKE)
        }
    }

    fun sectionTitle(context: Context, title: String, subtitle: String? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 22), 0, dp(context, 10))
            addView(text(context, title, 19f, TEXT, true))
            if (!subtitle.isNullOrBlank()) addView(text(context, subtitle, 12.5f, MUTED).apply { setPadding(0, dp(context, 4), 0, 0) })
        }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(STROKE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)).apply {
            setMargins(0, dp(context, 12), 0, dp(context, 12))
        }
    }

    fun row(context: Context, vararg views: View, gapDp: Int = 8): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { index, view ->
            view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) leftMargin = dp(context, gapDp)
            }
            addView(view)
        }
    }

    fun gap(context: Context, heightDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp))
    }
}
