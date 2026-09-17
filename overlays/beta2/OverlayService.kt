package com.oai.perfpilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.oai.perfpilot.overlay.START"
        const val ACTION_STOP = "com.oai.perfpilot.overlay.STOP"
        private const val CHANNEL_ID = "perfpilot_overlay_v2"
        private const val NOTIFICATION_ID = 817
        private const val PREF = "overlay_state"
        private const val KEY_RUNNING = "running"

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)

        fun start(context: Context) {
            val i = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, OverlayService::class.java).setAction(ACTION_STOP)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (_: Throwable) {
                context.stopService(Intent(context, OverlayService::class.java))
            }
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var overlay: LinearLayout
    private lateinit var cpuText: TextView
    private lateinit var gpuText: TextView
    private lateinit var tempText: TextView
    private lateinit var fpsText: TextView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var metrics: RuntimeMetricsReader
    private val workers = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var generation = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        metrics = RuntimeMetricsReader(this, ShellEngine(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            markRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        if (!Settings.canDrawOverlays(this)) {
            markRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!active) {
            addOverlay()
            active = true
            markRunning(true)
            generation++
            updateLoop(generation)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        active = false
        generation++
        markRunning(false)
        main.removeCallbacksAndMessages(null)
        workers.shutdownNow()
        if (::overlay.isInitialized) try { wm.removeView(overlay) } catch (_: Throwable) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun markRunning(v: Boolean) {
        getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, v).apply()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "PerfPilot 性能悬浮窗", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "可从通知栏直接关闭性能悬浮窗"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PerfPilot 性能悬浮窗")
            .setContentText("点击进入；也可直接关闭")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭悬浮窗", stop)
            .setOngoing(true)
            .build()
    }

    private fun label(prefix: String): TextView = TextView(this).apply {
        text = "$prefix --"
        setTextColor(Color.WHITE)
        textSize = 11.5f
        typeface = android.graphics.Typeface.MONOSPACE
        includeFontPadding = false
    }

    private fun addOverlay() {
        val bg = GradientDrawable().apply {
            setColor(0xE5202427.toInt())
            cornerRadius = dp(13).toFloat()
        }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(6), dp(8))
            background = bg
        }
        val data = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cpuText = label("CPU")
        gpuText = label("GPU")
        tempText = label("BAT")
        fpsText = label("FPS")
        data.addView(cpuText); data.addView(gpuText); data.addView(tempText); data.addView(fpsText)
        val close = TextView(this).apply {
            text = "  ×  "
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(2), 0)
            setOnClickListener { stopSelf() }
        }
        overlay.addView(data)
        overlay.addView(close)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(100)
        }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        data.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = sx - (e.rawX - tx).toInt()
                    params.y = sy + (e.rawY - ty).toInt()
                    try { wm.updateViewLayout(overlay, params) } catch (_: Throwable) {}
                    true
                }
                else -> false
            }
        }
        wm.addView(overlay, params)
    }

    private fun updateLoop(g: Long) {
        if (!active || g != generation || workers.isShutdown) return
        runMetric(g, { metrics.readCpu() }) { cpuText.text = "CPU $it" }
        runMetric(g, { metrics.readGpu() }) { gpuText.text = "GPU $it" }
        runMetric(g, { metrics.readBatteryTemperature() }) { tempText.text = "BAT $it" }
        runMetric(g, { metrics.readForegroundFps() }) { fpsText.text = "FPS $it" }
        main.postDelayed({ updateLoop(g) }, 2200L)
    }

    private fun runMetric(g: Long, read: () -> String, apply: (String) -> Unit) {
        workers.execute {
            val value = try { read() } catch (_: Throwable) { "N/A" }
            main.post { if (active && g == generation) apply(value) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
