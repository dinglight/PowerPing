package io.github.dinglight.powerping.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置持久化（SharedPreferences：power_ping_prefs）。
 * 三个字段均为低频读写，直接同步 getter 即可。
 */
object PrefsRepository {

    private const val PREFS_NAME = "power_ping_prefs"
    private const val KEY_THRESHOLD = "threshold_percent"
    private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    private const val KEY_ALERTED_THIS_CHARGE = "alerted_this_charge"

    const val DEFAULT_THRESHOLD = 85
    const val MIN_THRESHOLD = 50
    const val MAX_THRESHOLD = 95

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 触发阈值，范围 50–95 */
    fun getThreshold(context: Context): Int =
        prefs(context).getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    fun setThreshold(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_THRESHOLD, value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD))
            .apply()
    }

    /** 用户主开关；重启/进程重建后据此恢复监控 */
    fun isMonitoringEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MONITORING_ENABLED, false)

    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONITORING_ENABLED, enabled).apply()
    }

    /** 本次充电已提醒标志；拔线时重置（防抖核心） */
    fun isAlertedThisCharge(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALERTED_THIS_CHARGE, false)

    fun markAlerted(context: Context) {
        prefs(context).edit().putBoolean(KEY_ALERTED_THIS_CHARGE, true).apply()
    }

    fun clearAlertFlag(context: Context) {
        prefs(context).edit().putBoolean(KEY_ALERTED_THIS_CHARGE, false).apply()
    }
}
