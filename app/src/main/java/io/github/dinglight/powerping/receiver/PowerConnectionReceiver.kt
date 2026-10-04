package io.github.dinglight.powerping.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.dinglight.powerping.data.PrefsRepository
import io.github.dinglight.powerping.notify.Notifications
import io.github.dinglight.powerping.service.ChargeMonitorService

/**
 * 插拔充电器广播（隐式广播豁免清单，可静态注册）。
 * 进程已死时插电 → 复活监控服务；服务存活时拔线由服务内电量广播处理。
 */
class PowerConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                if (!PrefsRepository.isMonitoringEnabled(context)) return
                try {
                    ChargeMonitorService.start(context)
                } catch (_: Exception) {
                    // 后台启动被限制：降级通知兜底
                    Notifications.showFallbackStart(context)
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                // 服务存活时其内部 ACTION_BATTERY_CHANGED 已覆盖；进程死亡时无需处理
            }
        }
    }
}
