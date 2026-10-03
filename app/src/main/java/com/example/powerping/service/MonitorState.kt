package com.example.powerping.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 监控状态机：
 * IDLE        服务未运行 / 用户关闭监控
 * ARMED       监控已开启，未插电，等待充电器插入
 * MONITORING  充电中 且 电量 < 阈值
 * ALERTING    达到阈值，闹铃循环播放中
 * DONE        本次充电已提醒过（用户停铃但未拔线），静默等待拔线
 */
enum class MonitorState { IDLE, ARMED, MONITORING, ALERTING, DONE }

data class MonitorStatus(
    val serviceRunning: Boolean = false,
    val state: MonitorState = MonitorState.IDLE,
    val batteryPct: Int = 0,
    val charging: Boolean = false,
    val threshold: Int = 85,
    val alarmVolumeZero: Boolean = false,
)

/**
 * 服务与 UI 之间的内存态总线（不持久化）。
 * 服务写，UI 用 collectAsState() 订阅；关键标志仍写入 PrefsRepository 保证崩溃/重启一致。
 */
object MonitorBus {

    private val _status = MutableStateFlow(MonitorStatus())

    val status: StateFlow<MonitorStatus> = _status

    fun update(transform: (MonitorStatus) -> MonitorStatus) {
        _status.value = transform(_status.value)
    }
}
