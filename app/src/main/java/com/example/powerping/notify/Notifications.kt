package com.example.powerping.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.powerping.MainActivity
import com.example.powerping.R
import com.example.powerping.service.ChargeMonitorService
import com.example.powerping.service.MonitorState
import com.example.powerping.service.MonitorStatus

object Notifications {

    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_ALERT = "alert"

    const val ID_MONITORING = 1
    const val ID_ALERT = 2
    const val ID_FALLBACK_START = 3

    /** 双渠道：monitor 常驻静默（LOW），alert 高优先级横幅但无声（铃声由 AlarmPlayer 播放，避免双重声音） */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.notif_channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_monitor_desc)
            setShowBadge(false)
        }

        val alert = NotificationChannel(
            CHANNEL_ALERT,
            context.getString(R.string.notif_channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notif_channel_alert_desc)
            setSound(null, null)
            enableVibration(false)
        }

        nm.createNotificationChannel(monitor)
        nm.createNotificationChannel(alert)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notifyCompat(context: Context, id: Int, notification: Notification) {
        if (!canNotify(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    /** 常驻前台服务通知（monitor 渠道），按状态机显示文案 */
    fun buildMonitoring(context: Context, status: MonitorStatus): Notification {
        ensureChannels(context)
        val text = when (status.state) {
            MonitorState.MONITORING ->
                context.getString(R.string.notif_monitoring, status.batteryPct, status.threshold)
            MonitorState.ALERTING -> {
                var t = context.getString(R.string.state_alerting)
                if (status.alarmVolumeZero) t += "\n" + context.getString(R.string.notif_volume_zero)
                t
            }
            MonitorState.DONE ->
                context.getString(R.string.notif_done, status.threshold)
            else ->
                context.getString(R.string.notif_armed)
        }
        return baseBuilder(context, NotificationCompat.PRIORITY_LOW)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .build()
    }

    /** 到阈值的高优先级提醒（alert 渠道），含"停止提醒"操作 */
    fun buildAlert(context: Context, status: MonitorStatus): Notification {
        ensureChannels(context)
        var text = context.getString(R.string.notif_alert_text, status.threshold)
        if (status.alarmVolumeZero) text += "\n" + context.getString(R.string.notif_volume_zero)

        val stopIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, ChargeMonitorService::class.java)
                .setAction(ChargeMonitorService.ACTION_STOP_ALERT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return baseBuilder(context, NotificationCompat.PRIORITY_MAX, channelId = CHANNEL_ALERT)
            .setContentTitle(context.getString(R.string.notif_alert_title, status.batteryPct))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(0, context.getString(R.string.notif_stop_alert), stopIntent)
            .setOngoing(true)
            .build()
    }

    /** 后台启动服务被系统拒绝时的降级通知：点击进入界面并自动启动监控 */
    fun buildFallbackStart(context: Context): Notification {
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTO_START, true)
        val contentIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return baseBuilder(context, NotificationCompat.PRIORITY_MAX, channelId = CHANNEL_ALERT)
            .setContentTitle(context.getString(R.string.notif_fallback_title))
            .setContentText(context.getString(R.string.notif_fallback_text))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notif_fallback_text)))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
    }

    fun showFallbackStart(context: Context) {
        notifyCompat(context, ID_FALLBACK_START, buildFallbackStart(context))
    }

    private fun baseBuilder(
        context: Context,
        priority: Int,
        channelId: String = CHANNEL_MONITOR,
    ): NotificationCompat.Builder {
        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentIntent(contentIntent)
            .setPriority(priority)
            .setSound(null)
            .setDefaults(0)
    }
}
