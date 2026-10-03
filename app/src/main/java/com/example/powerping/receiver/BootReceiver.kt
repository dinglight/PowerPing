package com.example.powerping.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.powerping.data.PrefsRepository
import com.example.powerping.notify.Notifications
import com.example.powerping.service.ChargeMonitorService

/**
 * 开机/升级完成：若用户开关开启则拉起监控服务。
 * Android 12+ 限制后台启动前台服务——失败时降级为可点击启动的通知。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!PrefsRepository.isMonitoringEnabled(context)) return
        tryStartService(context)
    }

    internal fun tryStartService(context: Context) {
        try {
            ChargeMonitorService.start(context)
        } catch (_: Exception) {
            // ForegroundServiceStartNotAllowedException 等：降级通知，用户点击属豁免场景
            Notifications.showFallbackStart(context)
        }
    }
}
