package com.iskra.island

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
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

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
    private const val VIS_H = 340   // высота окна с рисунком, dp (не меняется)

    private var owner: Any? = null
    private var ctx: Context? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var clp: WindowManager.LayoutParams? = null
    private var web: WebView? = null
    private var catcher: View? = null
    private var visW = 324
    private var loaded = false
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
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
        val html = context.assets.open("island.html").bufferedReader().use { it.readText() }
        w.loadDataWithBaseURL("https://iskra.local/", html, "text/html", "UTF-8", null)

        val dm = context.resources.displayMetrics
        val expW = minOf(324, (dm.widthPixels / dm.density).toInt() - 16)
        visW = expW
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        // Окно с рисунком: большое, не меняет размер и не ловит нажатия. Вся анимация внутри страницы.
        val p = WindowManager.LayoutParams(
            dp(expW), dp(VIS_H),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        placeTop(p)

        // Невидимое окно-ловец нажатий ровно по размеру острова: остальной экран остаётся нажимаемым.
        val cv = View(context)
        cv.setOnTouchListener { _, e -> onCatch(e) }
        val cp = WindowManager.LayoutParams(
            dp(COLLAPSED_W), dp(COLLAPSED_H),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        placeTop(cp)

        try {
            manager.addView(w, p)
            manager.addView(cv, cp)
        } catch (e: Exception) {
            try {
                manager.removeView(w)
            } catch (e2: Exception) {
            }
            try {
                w.destroy()
            } catch (e3: Exception) {
            }
            owner = null
            return
        }
        wm = manager
        lp = p
        clp = cp
        web = w
        catcher = cv
    }

    private fun placeTop(p: WindowManager.LayoutParams) {
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = dp(TOP_MARGIN)
        if (Build.VERSION.SDK_INT >= 30) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun density(): Float = ctx?.resources?.displayMetrics?.density ?: 3f

    private val longRun = Runnable {
        longFired = true
        val d = density()
        js("window.onLong && window.onLong(${downX / d}, ${downY / d})")
    }

    /** Нажатия по невидимому окну пересылаются в страницу: тап, свайп, долгое нажатие. */
    private fun onCatch(e: MotionEvent): Boolean {
        val d = density()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                longFired = false
                ui.postDelayed(longRun, 650)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 10 * d || abs(e.y - downY) > 10 * d) ui.removeCallbacks(longRun)
            }
            MotionEvent.ACTION_UP -> {
                ui.removeCallbacks(longRun)
                if (!longFired) {
                    val dx = (e.x - downX) / d
                    val dy = (e.y - downY) / d
                    if (abs(dy) > 28f && abs(dy) > abs(dx)) {
                        js("window.onSwipe && window.onSwipe(${if (dy < 0) -1 else 1})")
                    } else if (abs(dx) < 16f && abs(dy) < 16f) {
                        js("window.onTap && window.onTap(${e.x / d}, ${e.y / d})")
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longRun)
            MotionEvent.ACTION_OUTSIDE -> {
                js("window.onOutside && window.onOutside()")
                return false
            }
        }
        return true
    }

    private fun removeNow() {
        ui.removeCallbacks(longRun)
        val cv = catcher
        if (cv != null) {
            try {
                wm?.removeView(cv)
            } catch (e: Exception) {
            }
        }
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
        catcher = null
        web = null
        loaded = false
        owner = null
    }

    /** Меняется только невидимое окно-ловец: окно с рисунком остаётся как есть. */
    private fun resizeCatcher(wd: Int, hd: Int) {
        val v = catcher ?: return
        val p = clp ?: return
        val m = wm ?: return
        p.width = dp(minOf(wd, visW))
        p.height = dp(hd)
        try {
            m.updateViewLayout(v, p)
        } catch (e: Exception) {
        }
    }

    // ---------- тексты песен (lrclib.net, без ключа) ----------

    private fun httpGet(u: String): String? {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "IskraIsland/0.4")
        return try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    }

    private fun lrcAttempt(title: String, artist: String, dur: Int): String {
        val t = URLEncoder.encode(title, "UTF-8")
        val a = URLEncoder.encode(artist, "UTF-8")
        val extra = if (dur > 0) "&duration=$dur" else ""
        val g = httpGet("https://lrclib.net/api/get?track_name=$t&artist_name=$a$extra")
        if (g != null) {
            val s = JSONObject(g).optString("syncedLyrics", "")
            if (s.isNotBlank() && s != "null") return s
        }
        val sr = httpGet("https://lrclib.net/api/search?track_name=$t&artist_name=$a")
        if (sr != null) {
            val arr = JSONArray(sr)
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i).optString("syncedLyrics", "")
                if (s.isNotBlank() && s != "null") return s
            }
        }
        return ""
    }

    private fun findLrc(title: String, artist: String, dur: Int): String {
        val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
        val firstArtist = artist.split(Regex("\\s*[,&]\\s*|\\s+feat\\.?\\s+"))[0].trim()
        var r = lrcAttempt(title, artist, dur)
        if (r.isEmpty() && (cleanTitle != title || firstArtist != artist)) {
            r = lrcAttempt(cleanTitle.ifEmpty { title }, firstArtist.ifEmpty { artist }, dur)
        }
        return r
    }

    // ---------- мост для island.html: Android.xxx() ----------

    class Bridge {
        @JavascriptInterface
        fun fetchLyrics(key: String, title: String, artist: String, durSec: Int) {
            Thread {
                var lrc = ""
                try {
                    lrc = findLrc(title, artist, durSec)
                } catch (e: Exception) {
                }
                val o = JSONObject()
                o.put("key", key)
                o.put("lrc", lrc)
                js("window.onLyrics && window.onLyrics(" + o.toString() + ")")
            }.start()
        }

        @JavascriptInterface
        fun screenW(): Int {
            val c = ctx ?: return 360
            val dm = c.resources.displayMetrics
            return (dm.widthPixels / dm.density).toInt()
        }

        @JavascriptInterface
        fun setSize(w: Int, h: Int, ms: Int) {
            ui.post { resizeCatcher(w, h) }
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
