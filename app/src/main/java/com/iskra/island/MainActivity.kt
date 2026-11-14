package com.iskra.island

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF000000.toInt())
        }

        val title = TextView(this).apply {
            text = "Искра"
            textSize = 32f
            setTextColor(0xFFFFB300.toInt())
            gravity = Gravity.CENTER
        }
        val hint = TextView(this).apply {
            text = "Остров с глазами появится сверху экрана поверх всех приложений.\n" +
                "Нажми на него — раскроется с часами, погодой и батареей."
            textSize = 15f
            setTextColor(0xFFBBBBBB.toInt())
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        val start = Button(this).apply {
            text = "Включить остров"
            setOnClickListener { startIsland() }
        }
        val stop = Button(this).apply {
            text = "Остановить"
            setOnClickListener {
                stopService(Intent(this@MainActivity, IslandService::class.java))
            }
        }

        root.addView(title)
        root.addView(hint)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
    }

    private fun startIsland() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши показ поверх других приложений и вернись", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, IslandService::class.java))
        moveTaskToBack(true)
    }
}
