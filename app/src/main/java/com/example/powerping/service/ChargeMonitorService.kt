package com.example.powerping.service

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.powerping.alarm.AlarmPlayer
import com.example.powerping.data.PrefsRepository
import com.example.powerping.notify.Notifications

/**
 * 前台服务（specialUse）：常驻监控电量，sticky 广播事件驱动，零轮询。
 *
 * 状态机：IDLE / ARMED / MONITORING / ALERTING / DONE（见 MonitorState）
 * 防抖：首次达到阈值置 alerted_this_charge 并响铃；标志仅在拔线时重置。
 */
class ChargeMonitorService : Service() {

    private lateinit var alarmPlayer: AlarmPlayer
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var lastNotifiedKey: Pair<Int, MonitorState>? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) handleBattery(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        alarmPlayer = AlarmPlayer(applicationContext)
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 用户关闭监控（startService 拉起的非前台调用，无需 startForeground）
                PrefsRepository.setMonitoringEnabled(this, false)
                stopAlarm()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForegroundNow()
        }

        when (intent?.action) {
            ACTION_START -> {
                PrefsRepository.setMonitoringEnabled(this, true)
                syncThreshold()
                evaluateFromSticky()
            }
            ACTION_APPLY_SETTINGS -> {
                syncThreshold()
                evaluateFromSticky()
            }
            ACTION_STOP_ALERT -> {
                stopAlarm()
                MonitorBus.update { it.copy(state = MonitorState.DONE) }
                updateNotification()
            }
            null -> {
                // START_STICKY 进程重建：按持久化开关恢复监控
                if (!PrefsRepository.isMonitoringEnabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                syncThreshold()
                evaluateFromSticky()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        alarmPlayer.release()
        releaseWakeLock()
        Notifications.cancel(this, Notifications.ID_ALERT)
        MonitorBus.update {
            it.copy(serviceRunning = false, state = MonitorState.IDLE, alarmVolumeZero = false)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 启动/停止

    private fun startForegroundNow() {
        if (foregroundStarted) return
        Notifications.ensureChannels(this)
        val notification = Notifications.buildMonitoring(this, MonitorBus.status.value)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                Notifications.ID_MONITORING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notifications.ID_MONITORING, notification)
        }
        foregroundStarted = true
        MonitorBus.update { it.copy(serviceRunning = true) }
    }

    private fun syncThreshold() {
        MonitorBus.update { it.copy(threshold = PrefsRepository.getThreshold(this)) }
    }

    // ---------------------------------------------------------------- 电量处理

    private fun evaluateFromSticky() {
        // sticky 特性：注册(null)即可读取最近一次电量广播
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            handleBattery(sticky)
        } else {
            updateNotification()
        }
    }

    private fun handleBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else MonitorBus.status.value.batteryPct
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        evaluate(pct, plugged)
    }

    private fun evaluate(pct: Int, plugged: Boolean) {
        val threshold = PrefsRepository.getThreshold(this)
        MonitorBus.update { it.copy(batteryPct = pct, charging = plugged, threshold = threshold) }

        if (!plugged) {
            // 拔线：停铃 + 重置防抖标志 + 重新武装
            if (MonitorBus.status.value.state == MonitorState.ALERTING) stopAlarm()
            PrefsRepository.clearAlertFlag(this)
            MonitorBus.update { it.copy(state = MonitorState.ARMED, alarmVolumeZero = false) }
        } else {
            when {
                MonitorBus.status.value.state == MonitorState.ALERTING -> {
                    // 已在提醒中，保持
                }
                PrefsRepository.isAlertedThisCharge(this) -> {
                    MonitorBus.update { it.copy(state = MonitorState.DONE) }
                }
                pct >= threshold -> triggerAlarm()
                else -> MonitorBus.update { it.copy(state = MonitorState.MONITORING) }
            }
        }
        updateNotification()
    }

    // ---------------------------------------------------------------- 闹铃

    private fun triggerAlarm() {
        PrefsRepository.markAlerted(this)
        val volumeZero = alarmPlayer.isAlarmVolumeZero()
        MonitorBus.update { it.copy(state = MonitorState.ALERTING, alarmVolumeZero = volumeZero) }
        alarmPlayer.start(loop = true)
        acquireWakeLock()
        Notifications.notifyCompat(
            this,
            Notifications.ID_ALERT,
            Notifications.buildAlert(this, MonitorBus.status.value)
        )
    }

    private fun stopAlarm() {
        alarmPlayer.stop()
        releaseWakeLock()
        Notifications.cancel(this, Notifications.ID_ALERT)
        MonitorBus.update {
            if (it.state == MonitorState.ALERTING) it.copy(state = MonitorState.DONE) else it
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PowerPing:alert")
        }
        // 30 分钟上限作为安全网；正常由 stopAlarm / onDestroy 释放
        wakeLock?.takeIf { !it.isHeld }?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    // ---------------------------------------------------------------- 通知

    private fun updateNotification() {
        val status = MonitorBus.status.value
        val key = status.batteryPct to status.state
        if (key == lastNotifiedKey) return
        lastNotifiedKey = key
        Notifications.notifyCompat(
            this,
            Notifications.ID_MONITORING,
            Notifications.buildMonitoring(this, status)
        )
    }

    companion object {
        const val ACTION_START = "com.example.powerping.action.START"
        const val ACTION_STOP = "com.example.powerping.action.STOP"
        const val ACTION_STOP_ALERT = "com.example.powerping.action.STOP_ALERT"
        const val ACTION_APPLY_SETTINGS = "com.example.powerping.action.APPLY_SETTINGS"

        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60 * 1000

        fun start(context: Context) {
            val intent = Intent(context, ChargeMonitorService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            PrefsRepository.setMonitoringEnabled(context, false)
            if (MonitorBus.status.value.serviceRunning) {
                val intent = Intent(context, ChargeMonitorService::class.java).setAction(ACTION_STOP)
                context.startService(intent)
            }
        }

        fun applySettings(context: Context) {
            if (!MonitorBus.status.value.serviceRunning) return
            val intent =
                Intent(context, ChargeMonitorService::class.java).setAction(ACTION_APPLY_SETTINGS)
            context.startService(intent)
        }
    }
}
