package com.example.powerping.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.powerping.R
import com.example.powerping.service.MonitorState
import com.example.powerping.service.MonitorStatus
import kotlin.math.roundToInt

@Composable
fun MonitorScreen(
    status: MonitorStatus,
    localPct: Int,
    localCharging: Boolean,
    threshold: Int,
    notificationGranted: Boolean,
    onToggle: () -> Unit,
    onThresholdChange: (Int) -> Unit,
    onPreview: () -> Unit,
) {
    val running = status.serviceRunning
    val effectivePct = if (running) status.batteryPct else localPct
    val effectiveCharging = if (running) status.charging else localCharging

    // 拖动中仅更新本地值，松手才提交（避免高频写 prefs / 发送服务 Intent）
    var sliderValue by remember(threshold) { mutableFloatStateOf(threshold.toFloat()) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            if (!notificationGranted) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.permission_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // ---- 状态卡片 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$effectivePct%",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (effectiveCharging) {
                            Text(
                                text = stringResource(R.string.charging_mark),
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                    }

                    AssistChip(
                        onClick = {},
                        label = { Text(stateLabel(status.state)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = stateColor(status.state).copy(alpha = 0.18f),
                            labelColor = stateColor(status.state)
                        )
                    )

                    LinearProgressIndicator(
                        progress = { (effectivePct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = stringResource(
                            R.string.distance_to_target,
                            threshold,
                            (threshold - effectivePct).coerceAtLeast(0)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ---- 阈值滑块 ----
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = stringResource(R.string.target_battery))
                    Text(
                        text = "${sliderValue.roundToInt()}%",
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onThresholdChange(sliderValue.roundToInt()) },
                    valueRange = 50f..95f,
                    steps = 44
                )
            }

            // ---- 主开关 ----
            Button(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = if (running) stringResource(R.string.stop_monitoring)
                    else stringResource(R.string.start_monitoring)
                )
            }

            // ---- 试听 ----
            OutlinedButton(
                onClick = onPreview,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(text = stringResource(R.string.preview_alarm))
            }

            // ---- 说明卡片 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.info_text),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun stateLabel(state: MonitorState): String = when (state) {
    MonitorState.IDLE -> stringResource(R.string.state_idle)
    MonitorState.ARMED -> stringResource(R.string.state_armed)
    MonitorState.MONITORING -> stringResource(R.string.state_monitoring)
    MonitorState.ALERTING -> stringResource(R.string.state_alerting)
    MonitorState.DONE -> stringResource(R.string.state_done)
}

private fun stateColor(state: MonitorState): Color = when (state) {
    MonitorState.IDLE -> Color(0xFF9E9E9E)      // 灰
    MonitorState.ARMED -> Color(0xFF1976D2)     // 蓝
    MonitorState.MONITORING -> Color(0xFF388E3C) // 绿
    MonitorState.ALERTING -> Color(0xFFD32F2F)  // 红
    MonitorState.DONE -> Color(0xFFF57C00)      // 橙
}
