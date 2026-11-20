package com.example.procpulse.feature.profiler

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.procpulse.core.database.ProfilerRepository
import com.example.procpulse.core.database.ProfilerSampleEntity
import com.example.procpulse.core.database.ProfilerSessionEntity
import com.example.procpulse.core.database.WatchdogEventEntity
import com.example.procpulse.core.model.ChartSeriesSnapshot
import com.example.procpulse.core.model.WatchdogConfig
import com.example.procpulse.core.ui.PrecisionCard
import com.example.procpulse.core.ui.PrecisionLineChart
import com.example.procpulse.service.ActiveProfilerState
import com.example.ui.theme.LocalProcPulsePalette
import com.example.ui.theme.TnumText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilerScreen(
    profilerState: ActiveProfilerState,
    sessions: List<ProfilerSessionEntity>,
    selectedSessionId: Long?,
    selectedSamples: List<ProfilerSampleEntity>,
    watchdogConfig: WatchdogConfig,
    watchdogEvents: List<WatchdogEventEntity>,
    onStartRecording: (title: String, intervalMs: Long, durationSec: Int) -> Unit,
    onStopRecording: () -> Unit,
    onSelectSession: (Long?) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onExportCsv: (Long) -> Unit,
    onExportJson: (Long) -> Unit,
    onUpdateWatchdog: (WatchdogConfig) -> Unit,
    onClearWatchdogEvents: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalProcPulsePalette.current
    var sessionTitle by remember { mutableStateOf("") }
    var recIntervalMs by remember { mutableLongStateOf(1000L) }
    var recDurationSec by remember { mutableIntStateOf(0) }
    var scrubbedSampleIdx by remember { mutableStateOf<Int?>(null) }

    val dateFmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Blackbox Recorder Controls
        item {
            PrecisionCard(
                title = "Blackbox Profiler",
                trailingBadge = {
                    if (profilerState.isRecording) {
                        Text(
                            text = "REC · ${profilerState.samplesRecorded} ср.",
                            style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                            color = palette.critRed
                        )
                    }
                }
            ) {
                OutlinedTextField(
                    value = sessionTitle,
                    onValueChange = { sessionTitle = it },
                    placeholder = { Text("Название сессии (опционально)", style = TnumText.MicroLabel) },
                    singleLine = true,
                    enabled = !profilerState.isRecording,
                    textStyle = TnumText.DataRow,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("Интервал записи:", style = TnumText.MicroLabel, color = palette.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1000L to "1с", 2000L to "2с", 5000L to "5с", 10_000L to "10с").forEach { (ms, lbl) ->
                        FilterChip(
                            selected = recIntervalMs == ms,
                            enabled = !profilerState.isRecording,
                            onClick = { recIntervalMs = ms },
                            label = { Text(lbl, style = TnumText.MicroLabel) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Длительность:", style = TnumText.MicroLabel, color = palette.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Вручную", 900 to "15 мин", 1800 to "30 мин", 3600 to "1 ч", 28800 to "8 ч").forEach { (sec, lbl) ->
                        FilterChip(
                            selected = recDurationSec == sec,
                            enabled = !profilerState.isRecording,
                            onClick = { recDurationSec = sec },
                            label = { Text(lbl, style = TnumText.MicroLabel) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (profilerState.isRecording) {
                    Button(
                        onClick = onStopRecording,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.critRed),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp).testTag("stop_profiler_btn")
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ОСТАНОВИТЬ ЗАПИСЬ (${profilerState.samplesRecorded} ср.)", style = TnumText.DataRow)
                    }
                } else {
                    Button(
                        onClick = { onStartRecording(sessionTitle, recIntervalMs, recDurationSec) },
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp).testTag("start_profiler_btn")
                    ) {
                        Icon(Icons.Outlined.FiberManualRecord, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("НАЧАТЬ ЗАПИСЬ BLACKBOX", style = TnumText.DataRow)
                    }
                }
            }
        }

        // 2. Post-Analysis Timeline Viewer (when a session is selected)
        val activeSession = sessions.firstOrNull { it.id == selectedSessionId }
        if (activeSession != null) {
            item {
                PrecisionCard(
                    title = "Пост-анализ: ${activeSession.title}",
                    trailingBadge = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                onClick = { onExportCsv(activeSession.id) },
                                modifier = Modifier.height(30.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("CSV", style = TnumText.MicroLabel)
                            }
                            OutlinedButton(
                                onClick = { onExportJson(activeSession.id) },
                                modifier = Modifier.height(30.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("JSON", style = TnumText.MicroLabel)
                            }
                        }
                    }
                ) {
                    if (selectedSamples.isEmpty()) {
                        Text("Загрузка срезов телеметрии...", style = TnumText.MicroLabel, color = palette.textMuted)
                    } else {
                        val cpuSeries = remember(selectedSamples) {
                            val vals = FloatArray(selectedSamples.size) { selectedSamples[it].cpuTotalPercent }
                            val ts = LongArray(selectedSamples.size) { selectedSamples[it].timestampMs }
                            ChartSeriesSnapshot(vals, ts, vals.minOrNull() ?: 0f, vals.maxOrNull() ?: 100f, vals.last())
                        }
                        val tempSeries = remember(selectedSamples) {
                            val vals = FloatArray(selectedSamples.size) { selectedSamples[it].tempCelsius }
                            val ts = LongArray(selectedSamples.size) { selectedSamples[it].timestampMs }
                            ChartSeriesSnapshot(vals, ts, vals.minOrNull() ?: 0f, vals.maxOrNull() ?: 80f, vals.last())
                        }

                        Text("Проведите пальцем по графику CPU для привязки пиков к процессам:", style = TnumText.MicroLabel, color = palette.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        PrecisionLineChart(
                            series = cpuSeries,
                            unitSuffix = "%",
                            fixedMax = 100f,
                            height = 96.dp,
                            onScrubIndexChanged = { idx -> scrubbedSampleIdx = idx }
                        )
                        Spacer(Modifier.height(8.dp))
                        PrecisionLineChart(
                            series = tempSeries,
                            unitSuffix = "°C",
                            height = 68.dp
                        )

                        val inspectedSample = selectedSamples.getOrNull(scrubbedSampleIdx ?: selectedSamples.lastIndex)
                        if (inspectedSample != null) {
                            Spacer(Modifier.height(8.dp))
                            val topProcs = remember(inspectedSample.topProcessesJson) {
                                ProfilerRepository.decodeTopProcesses(inspectedSample.topProcessesJson)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(palette.innerSurface)
                                    .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "СРЕЗ ${dateFmt.format(Date(inspectedSample.timestampMs))} · CPU ${"%.1f".format(Locale.US, inspectedSample.cpuTotalPercent)}% · ${"%.1f".format(Locale.US, inspectedSample.tempCelsius)}°C · ${inspectedSample.currentMa.toInt()}mA",
                                    style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                                    color = palette.accent
                                )
                                Text(
                                    text = "Кластеры: ${inspectedSample.clusterFreqsSummary}",
                                    style = TnumText.MicroLabel,
                                    color = palette.textSecondary
                                )
                                Spacer(Modifier.height(4.dp))
                                if (topProcs.isEmpty()) {
                                    Text("Нет данных о процессах в этом срезе", style = TnumText.MicroLabel, color = palette.textMuted)
                                } else {
                                    topProcs.forEach { p ->
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("${p.displayName} (${p.pid})", style = TnumText.MicroLabel, color = palette.textPrimary)
                                            Text("${"%.1f".format(Locale.US, p.cpuPercent)}% · ${p.ramMb.toInt()}MB", style = TnumText.MicroLabel, color = palette.textSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Saved Sessions List
        item {
            PrecisionCard(title = "Сохранённые сессии (${sessions.size})") {
                if (sessions.isEmpty()) {
                    Text("Записей пока нет. Нажмите «Начать запись Blackbox» выше.", style = TnumText.MicroLabel, color = palette.textMuted)
                } else {
                    sessions.forEachIndexed { idx, s ->
                        val isSel = s.id == selectedSessionId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSel) palette.accent.copy(alpha = 0.12f) else palette.innerSurface)
                                .border(1.dp, if (isSel) palette.accent else palette.border, RoundedCornerShape(4.dp))
                                .clickable { onSelectSession(if (isSel) null else s.id) }
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s.title, style = TnumText.DataRow.copy(fontWeight = FontWeight.Bold), color = palette.textPrimary)
                                Text(
                                    text = "${dateFmt.format(Date(s.startTimeMs))} · ${s.sampleCount} ср. · Peak ${s.peakCpuPercent.toInt()}% / ${"%.1f".format(Locale.US, s.peakTempCelsius)}°C",
                                    style = TnumText.MicroLabel,
                                    color = palette.textSecondary
                                )
                            }
                            IconButton(onClick = { onDeleteSession(s.id) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Удалить сессию", tint = palette.textMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                        if (idx < sessions.lastIndex) Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        // 4. Watchdog Rules Engine
        item {
            PrecisionCard(
                title = "Watchdog (Локальный сторож)",
                trailingBadge = {
                    Switch(
                        checked = watchdogConfig.enabled,
                        onCheckedChange = { onUpdateWatchdog(watchdogConfig.copy(enabled = it)) },
                        modifier = Modifier.testTag("watchdog_enable_switch")
                    )
                }
            ) {
                Text(
                    text = "По умолчанию работает только в режиме уведомлений. Авто-действия применяются строго к пакетам из Blacklist.",
                    style = TnumText.MicroLabel,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(8.dp))

                Text("Порог CPU при выключенном экране: > ${watchdogConfig.cpuThresholdPercent}% (${watchdogConfig.cpuDurationMinutes} мин)", style = TnumText.MicroLabel, color = palette.textPrimary)
                Slider(
                    value = watchdogConfig.cpuThresholdPercent.toFloat(),
                    onValueChange = { onUpdateWatchdog(watchdogConfig.copy(cpuThresholdPercent = it.roundToInt())) },
                    valueRange = 15f..90f
                )

                Text("Порог температуры SoC/Батареи: > ${watchdogConfig.tempThresholdCelsius}°C", style = TnumText.MicroLabel, color = palette.textPrimary)
                Slider(
                    value = watchdogConfig.tempThresholdCelsius.toFloat(),
                    onValueChange = { onUpdateWatchdog(watchdogConfig.copy(tempThresholdCelsius = it.roundToInt())) },
                    valueRange = 36f..65f
                )

                Text("Порог свободной RAM + ZRAM: < ${watchdogConfig.freeRamThresholdMb} МБ", style = TnumText.MicroLabel, color = palette.textPrimary)
                Slider(
                    value = watchdogConfig.freeRamThresholdMb.toFloat(),
                    onValueChange = { onUpdateWatchdog(watchdogConfig.copy(freeRamThresholdMb = it.roundToInt())) },
                    valueRange = 128f..1500f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Авто-реакция для Blacklist (Opt-in)", style = TnumText.DataRow, color = palette.textPrimary)
                        Text("Force Stop / Freeze после ${watchdogConfig.screenOffFreezeDelayMinutes} мин сна (Tier 1/2)", style = TnumText.MicroLabel, color = palette.textMuted)
                    }
                    Switch(
                        checked = watchdogConfig.autoKillBlacklisted,
                        onCheckedChange = { onUpdateWatchdog(watchdogConfig.copy(autoKillBlacklisted = it)) }
                    )
                }
            }
        }

        // 5. Watchdog Event Log
        item {
            PrecisionCard(
                title = "Журнал Watchdog (${watchdogEvents.size})",
                trailingBadge = {
                    if (watchdogEvents.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearWatchdogEvents,
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, palette.border)
                        ) {
                            Text("Очистить", style = TnumText.MicroLabel)
                        }
                    }
                }
            ) {
                if (watchdogEvents.isEmpty()) {
                    Text("Срабатываний Watchdog нет.", style = TnumText.MicroLabel, color = palette.textMuted)
                } else {
                    watchdogEvents.take(25).forEachIndexed { idx, ev ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = "${dateFmt.format(Date(ev.timestampMs))} · ${ev.ruleType} · ${ev.actionTaken}",
                                style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                                color = palette.accent
                            )
                            Text(ev.description, style = TnumText.MicroLabel, color = palette.textPrimary)
                        }
                        if (idx < watchdogEvents.lastIndex) HorizontalDivider(color = palette.border)
                    }
                }
            }
        }
    }
}
