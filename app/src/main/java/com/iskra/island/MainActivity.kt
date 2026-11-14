package com.iskra.island

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(AppBridge(), "App")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pushStatus()
            }
        }
        web.loadUrl("file:///android_asset/app.html")

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.fitsSystemWindows = true
        root.addView(
            web,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ensureIsland()
        pushStatus()
    }

    private fun ensureIsland() {
        if (!IslandController.isEnabled(this)) return
        if (!Settings.canDrawOverlays(this)) return
        val l = IslandListener.instance
        if (l != null) {
            IslandController.show(l, l)
        } else if (!IslandController.isShowing()) {
            try {
                startForegroundService(Intent(this, IslandService::class.java))
            } catch (e: Exception) {
            }
        }
    }

    private fun listenerGranted(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return s.contains(packageName)
    }

    private fun statusJson(): String {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val o = JSONObject()
        o.put("overlay", Settings.canDrawOverlays(this))
        o.put("listener", listenerGranted())
        o.put("battery", pm.isIgnoringBatteryOptimizations(packageName))
        o.put("enabled", IslandController.isEnabled(this))
        return o.toString()
    }

    private fun pushStatus() {
        if (!::web.isInitialized) return
        web.evaluateJavascript("window.setStatus && window.setStatus(" + statusJson() + ")", null)
    }

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    private fun handle(name: String) {
        val pkgUri = Uri.parse("package:$packageName")
        when (name) {
            "overlay" -> open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri))
            "listener" -> open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "appinfo" -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
            "battery" -> {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
                } catch (e: Exception) {
                    open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            "toggle" -> {
                val on = !IslandController.isEnabled(this)
                IslandController.setEnabled(this, on)
                if (on) {
                    ensureIsland()
                } else {
                    stopService(Intent(this, IslandService::class.java))
                    IslandController.hide(null)
                }
                pushStatus()
            }
        }
    }

    inner class AppBridge {
        @JavascriptInterface
        fun status(): String = statusJson()

        @JavascriptInterface
        fun act(name: String) {
            runOnUiThread { handle(name) }
        }
    }
}
