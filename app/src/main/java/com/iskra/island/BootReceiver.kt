package com.iskra.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** После перезагрузки телефона сам включает остров (если уже выдано разрешение «поверх приложений»). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.canDrawOverlays(context)) return
        try {
            context.startForegroundService(Intent(context, IslandService::class.java))
        } catch (e: Exception) {
        }
    }
}
