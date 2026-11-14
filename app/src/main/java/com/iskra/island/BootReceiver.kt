package com.iskra.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** После перезагрузки поднимает остров (запасной путь; основной — слушатель уведомлений). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!IslandController.isEnabled(context)) return
        if (!Settings.canDrawOverlays(context)) return
        try {
            context.startForegroundService(Intent(context, IslandService::class.java))
        } catch (e: Exception) {
        }
    }
}
