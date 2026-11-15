package com.iskra.island

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Слушатель уведомлений: система сама держит его живым и перезапускает после смахивания
 * из недавних, поэтому остров живёт здесь. Заодно через него читается музыка.
 */
class IslandListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: IslandListener? = null
    }

    private val ui = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var current: MediaController? = null
    private var lastArtKey: String = ""
    private var lastArt: String = ""

    private fun component() = ComponentName(this, IslandListener::class.java)

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> ui.post { pick(list) } }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            push()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            push()
        }

        override fun onSessionDestroyed() {
            ui.post { pick(safeSessions()) }
        }
    }

    private fun safeSessions(): List<MediaController>? {
        return try {
            msm?.getActiveSessions(component())
        } catch (e: SecurityException) {
            null
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        msm = getSystemService(MediaSessionManager::class.java)
        try {
            msm?.addOnActiveSessionsChangedListener(sessionsListener, component())
        } catch (e: SecurityException) {
        }
        pick(safeSessions())
        if (IslandController.isEnabled(this)) {
            IslandController.show(this, this)
        }
    }

    override fun onListenerDisconnected() {
        cleanup()
        try {
            NotificationListenerService.requestRebind(component())
        } catch (e: Exception) {
        }
        // Если прошивка временно отключила слушатель, передаём остров фоновой службе.
        if (IslandController.isEnabled(this) && Settings.canDrawOverlays(this)) {
            val keeper = IslandService.instance
            if (keeper != null) IslandController.show(keeper, keeper)
            else try { startForegroundService(Intent(this, IslandService::class.java)) } catch (_: Exception) {}
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        instance = null
        try {
            msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        try {
            current?.unregisterCallback(callback)
        } catch (e: Exception) {
        }
        current = null
        IslandController.mediaController = null
        IslandController.hide(this)
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (best?.sessionToken != current?.sessionToken) {
            try {
                current?.unregisterCallback(callback)
            } catch (e: Exception) {
            }
            current = best
            IslandController.mediaController = best
            try {
                best?.registerCallback(callback)
            } catch (e: Exception) {
            }
        }
        push()
    }

    private fun artFor(md: MediaMetadata?, key: String): String {
        if (key == lastArtKey) return lastArt
        var out = ""
        try {
            val bmp: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (bmp != null) {
                val small = Bitmap.createScaledBitmap(bmp, 96, 96, true)
                val bos = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                out = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            out = ""
        }
        lastArtKey = key
        lastArt = out
        return out
    }

    private fun push() {
        val c = current
        val st = c?.playbackState
        val md = c?.metadata
        if (c == null || st == null || st.state == PlaybackState.STATE_STOPPED || st.state == PlaybackState.STATE_NONE) {
            IslandController.pushMedia("{\"has\":false}")
            return
        }
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val playing = st.state == PlaybackState.STATE_PLAYING || st.state == PlaybackState.STATE_BUFFERING
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        val o = JSONObject()
        o.put("has", true)
        o.put("title", title)
        o.put("artist", artist)
        o.put("playing", playing)
        o.put("pos", pos)
        o.put("dur", dur)
        o.put("rate", st.playbackSpeed.toDouble())
        o.put("art", artFor(md, "$title|$artist"))
        o.put("pkg", c.packageName)
        IslandController.pushMedia(o.toString())
    }
}
