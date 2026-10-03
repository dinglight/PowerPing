package com.example.powerping.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import com.example.powerping.R

/**
 * 闹铃播放器：MediaPlayer + isLooping 原生循环；
 * USAGE_ALARM 音频流使音量跟随系统闹钟音量，勿扰模式下用户允许闹钟即可响。
 * 所有方法幂等，线程安全仅保证在主线程调用。
 */
class AlarmPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    val isPlaying: Boolean
        get() = try {
            player?.isPlaying == true
        } catch (_: IllegalStateException) {
            false
        }

    fun start(loop: Boolean = true) {
        stop()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, resolveAlarmUri())
            mp.isLooping = loop
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (_: Exception) {
            try {
                mp.release()
            } catch (_: Exception) {
            }
            player = null
        }
    }

    fun stop() {
        player?.let { p ->
            try {
                p.stop()
            } catch (_: IllegalStateException) {
            }
            try {
                p.release()
            } catch (_: Exception) {
            }
        }
        player = null
    }

    /** 等价于 stop()，语义化命名供服务销毁时调用 */
    fun release() = stop()

    /** 系统默认闹钟铃声优先；为空时回退到内置合成铃声 */
    private fun resolveAlarmUri(): Uri {
        val fallback: Uri =
            Uri.parse("android.resource://${context.packageName}/${R.raw.alarm_fallback}")
        return try {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    /** 闹钟音量是否为 0（触发时提示用户） */
    fun isAlarmVolumeZero(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            am.getStreamVolume(AudioManager.STREAM_ALARM) == 0
        } catch (_: Exception) {
            false
        }
    }
}
