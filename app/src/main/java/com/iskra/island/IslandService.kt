package com.iskra.island

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView

class IslandService : Service() {

    companion object {
        const val ACTION_STOP = "com.iskra.island.STOP"
        private const val CHANNEL = "iskra"

        // Размеры в dp. Подгони под свой телефон.
        private const val COLLAPSED_W = 130
        private const val COLLAPSED_H = 38
        private const val EXPANDED_W = 340
        private const val EXPANDED_H = 230
        private const val TOP_MARGIN = 8 // отступ от верха экрана (где камера)
    }

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var web: WebView? = null
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addIsland()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Искра", NotificationManager.IMPORTANCE_MIN)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, IslandService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_delete),
            "Остановить",
            stopPi
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Искра работает")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(action)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun addIsland() {
        val w = WebView(this)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.addJavascriptInterface(Bridge(), "Android")

        // Грузим через https-адрес, чтобы fetch к погодному API работал без проблем с CORS
        val html = assets.open("island.html").bufferedReader().use { it.readText() }
        w.loadDataWithBaseURL("https://iskra.local/", html, "text/html", "UTF-8", null)

        lp = WindowManager.LayoutParams(
            dp(COLLAPSED_W),
            dp(COLLAPSED_H),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(TOP_MARGIN)

        wm.addView(w, lp)
        web = w
    }

    private fun resize(expanded: Boolean) {
        val v = web ?: return
        val maxW = resources.displayMetrics.widthPixels - dp(16)
        lp.width = if (expanded) minOf(dp(EXPANDED_W), maxW) else dp(COLLAPSED_W)
        lp.height = dp(if (expanded) EXPANDED_H else COLLAPSED_H)
        wm.updateViewLayout(v, lp)
    }

    override fun onDestroy() {
        web?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    /** Методы, которые island.html вызывает как Android.xxx() */
    inner class Bridge {
        @JavascriptInterface
        fun setExpanded(expanded: Boolean) {
            ui.post { resize(expanded) }
        }

        /** Возвращает "процент,заряжается" например "87,true" */
        @JavascriptInterface
        fun battery(): String {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            return "$pct,$charging"
        }

        @JavascriptInterface
        fun quit() {
            ui.post { stopSelf() }
        }
    }
}
