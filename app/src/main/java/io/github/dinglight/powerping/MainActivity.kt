package io.github.dinglight.powerping

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.dinglight.powerping.alarm.AlarmPlayer
import io.github.dinglight.powerping.data.PrefsRepository
import io.github.dinglight.powerping.service.ChargeMonitorService
import io.github.dinglight.powerping.service.MonitorBus
import io.github.dinglight.powerping.ui.MonitorScreen
import io.github.dinglight.powerping.ui.theme.PowerPingTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_START = "extra_auto_start"
        private const val PREVIEW_DURATION_MS = 5_000L
    }

    private val localPct = mutableIntStateOf(0)
    private val localCharging = mutableStateOf(false)
    private val notificationGranted = mutableStateOf(false)

    private var startOnPermissionResult = false
    private var previewPlayer: AlarmPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) localPct.intValue = level * 100 / scale
            localCharging.value = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshNotificationGranted()
        handleAutoStart(intent)

        setContent {
            PowerPingTheme {
                val status by MonitorBus.status.collectAsState()
                var threshold by remember {
                    mutableIntStateOf(PrefsRepository.getThreshold(this@MainActivity))
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {
                    refreshNotificationGranted()
                    if (startOnPermissionResult) {
                        startOnPermissionResult = false
                        startMonitoring()
                    }
                }

                MonitorScreen(
                    status = status,
                    localPct = localPct.intValue,
                    localCharging = localCharging.value,
                    threshold = threshold,
                    notificationGranted = notificationGranted.value,
                    onToggle = {
                        if (MonitorBus.status.value.serviceRunning) {
                            ChargeMonitorService.stop(this@MainActivity)
                        } else if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            startOnPermissionResult = true
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startMonitoring()
                        }
                    },
                    onThresholdChange = { value ->
                        threshold = value
                        PrefsRepository.setThreshold(this@MainActivity, value)
                        ChargeMonitorService.applySettings(this@MainActivity)
                    },
                    onPreview = { previewAlarm() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoStart(intent)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(batteryReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationGranted()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        previewPlayer?.stop()
        previewPlayer = null
        super.onDestroy()
    }

    private fun refreshNotificationGranted() {
        notificationGranted.value =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startMonitoring() {
        PrefsRepository.setMonitoringEnabled(this, true)
        ChargeMonitorService.start(this)
    }

    /** 降级通知点击进入：若开关开启且服务未运行，自动拉起监控 */
    private fun handleAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true &&
            PrefsRepository.isMonitoringEnabled(this) &&
            !MonitorBus.status.value.serviceRunning
        ) {
            startMonitoring()
        }
    }

    /** 试听闹铃：循环播放，5 秒后自动停止；再次点击立即停止 */
    private fun previewAlarm() {
        previewPlayer?.let {
            it.stop()
            previewPlayer = null
            handler.removeCallbacksAndMessages(null)
            return
        }
        val player = AlarmPlayer(applicationContext)
        previewPlayer = player
        player.start(loop = true)
        handler.postDelayed({
            if (previewPlayer === player) {
                player.stop()
                previewPlayer = null
            }
        }, PREVIEW_DURATION_MS)
    }
}
