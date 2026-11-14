package com.iskra.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Рисует остров поверх всех приложений. Один экземпляр на весь процесс.
 * Окно меняет размер нативной анимацией, а страница island.html просто заполняет окно.
 */
@SuppressLint("StaticFieldLeak", "SetJavaScriptEnabled", "AddJavascriptInterface", "ClickableViewAccessibility")
object IslandController {

    // Отступ острова от верха экрана, dp. Больше число — остров ниже.
    const val TOP_MARGIN = 14

    private const val COLLAPSED_W = 168
    private const val COLLAPSED_H = 44

    private var owner: Any? = null
    private var ctx: Context? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var web: WebView? = null
    private var loaded = false
    private var anim: ValueAnimator? = null
    private var lastMediaJson: String = "{\"has\":false}"
    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    var mediaController: MediaController? = null

    // ---------- настройки ----------

    private fun prefs(c: Context) = c.getSharedPreferences("iskra", Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean("enabled", true)

    fun setEnabled(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("enabled", v).apply()
    }

    fun isShowing(): Boolean = web != null

    // ---------- показ / скрытие ----------

    fun show(newOwner: Any, context: Context) {
        ui.post { showNow(newOwner, context) }
    }

    fun hide(from: Any?) {
        ui.post {
            if (from == null || from === owner) removeNow()
        }
    }

    private fun dp(v: Int): Int {
        val d = ctx?.resources?.displayMetrics?.density ?: 3f
        return (v * d).toInt()
    }

    private fun js(code: String) {
        ui.post { web?.evaluateJavascript(code, null) }
    }

    fun pushMedia(json: String) {
        lastMediaJson = json
        if (loaded) js("window.onMedia && window.onMedia($json)")
    }

    private fun showNow(newOwner: Any, context: Context) {
        if (!Settings.canDrawOverlays(context)) return
        if (web != null) {
            if (owner === newOwner) return
            // Остров, который держит слушатель уведомлений, надёжнее: его не заменяем запасным.
            if (owner is IslandListener && newOwner !is IslandListener) return
            removeNow()
        }
        owner = newOwner
        ctx = context
        loaded = false

        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val w = WebView(context)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.isLongClickable = false
        w.setOnLongClickListener { true }
        w.isHapticFeedbackEnabled = false
        w.addJavascriptInterface(Bridge(), "Android")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loaded = true
                js("window.onMedia && window.onMedia($lastMediaJson)")
            }
        }
        w.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                js("window.onOutside && window.onOutside()")
            }
            false
        }

        val html = context.assets.open("island.html").bufferedReader().use { it.readText() }
        w.loadDataWithBaseURL("https://iskra.local/", html, "text/html", "UTF-8", null)

        val p = WindowManager.LayoutParams(
            dp(COLLAPSED_W),
            dp(COLLAPSED_H),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = dp(TOP_MARGIN)
        if (Build.VERSION.SDK_INT >= 30) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        try {
            manager.addView(w, p)
        } catch (e: Exception) {
            owner = null
            try {
                w.destroy()
            } catch (e2: Exception) {
            }
            return
        }
        wm = manager
        lp = p
        web = w
    }

    private fun removeNow() {
        anim?.cancel()
        anim = null
        val v = web
        if (v != null) {
            try {
                wm?.removeView(v)
            } catch (e: Exception) {
            }
            try {
                v.destroy()
            } catch (e: Exception) {
            }
        }
        web = null
        loaded = false
        owner = null
    }

    private fun animateTo(wd: Int, hd: Int, ms: Long) {
        val v = web ?: return
        val p = lp ?: return
        val m = wm ?: return
        val c = ctx ?: return
        val maxW = c.resources.displayMetrics.widthPixels - dp(8)
        val tw = minOf(dp(wd), maxW)
        val th = dp(hd)
        val fw = p.width
        val fh = p.height
        anim?.cancel()
        if (fw == tw && fh == th) return
        if (ms <= 0L) {
            p.width = tw
            p.height = th
            try {
                m.updateViewLayout(v, p)
            } catch (e: Exception) {
            }
            return
        }
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = ms
        a.interpolator = PathInterpolator(0.2f, 0.9f, 0.2f, 1f)
        a.addUpdateListener { va ->
            val f = va.animatedValue as Float
            p.width = (fw + (tw - fw) * f).toInt()
            p.height = (fh + (th - fh) * f).toInt()
            try {
                m.updateViewLayout(v, p)
            } catch (e: Exception) {
            }
        }
        anim = a
        a.start()
    }

    // ---------- мост для island.html: Android.xxx() ----------

    class Bridge {
        @JavascriptInterface
        fun screenW(): Int {
            val c = ctx ?: return 360
            val dm = c.resources.displayMetrics
            return (dm.widthPixels / dm.density).toInt()
        }

        @JavascriptInterface
        fun setSize(w: Int, h: Int, ms: Int) {
            ui.post { animateTo(w, h, ms.toLong()) }
        }

        /** "процент,заряжается", например "87,true" */
        @JavascriptInterface
        fun battery(): String {
            val c = ctx ?: return "-1,false"
            return try {
                val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                "$pct,${bm.isCharging}"
            } catch (e: Exception) {
                "-1,false"
            }
        }

        @JavascriptInterface
        fun getVolume(): Int {
            val c = ctx ?: return 0
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = maxOf(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        }

        @JavascriptInterface
        fun setVolume(percent: Int) {
            val c = ctx ?: return
            try {
                val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(percent * max / 100f), 0)
            } catch (e: Exception) {
            }
        }

        /** Фонарик. Возвращает итоговое состояние. */
        @JavascriptInterface
        fun torch(on: Boolean): Boolean {
            val c = ctx ?: return false
            return try {
                val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                var id: String? = null
                for (cid in cm.cameraIdList) {
                    val ch = cm.getCameraCharacteristics(cid)
                    val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    val back = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    if (flash && back) {
                        id = cid
                        break
                    }
                }
                if (id == null) {
                    false
                } else {
                    cm.setTorchMode(id, on)
                    on
                }
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun media(cmd: String) {
            ui.post {
                val c = mediaController ?: return@post
                val t = c.transportControls
                try {
                    if (cmd == "toggle") {
                        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                    } else if (cmd == "next") {
                        t.skipToNext()
                    } else if (cmd == "prev") {
                        t.skipToPrevious()
                    } else if (cmd.startsWith("seek:")) {
                        val ms = cmd.substring(5).toLongOrNull()
                        if (ms != null) t.seekTo(ms)
                    }
                } catch (e: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun openMediaApp() {
            ui.post {
                val c = ctx ?: return@post
                val pkg = mediaController?.packageName ?: return@post
                try {
                    val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return@post
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.startActivity(i)
                } catch (e: Exception) {
                }
            }
        }
    }
}
