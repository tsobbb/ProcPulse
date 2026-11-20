package com.example.procpulse.feature.overlay

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.procpulse.core.model.OverlayConfig
import com.example.procpulse.core.model.OverlayPreset
import com.example.procpulse.core.model.OverlayWidgetMetric
import com.example.procpulse.core.model.SystemSnapshot
import com.example.procpulse.core.ui.PrecisionCard
import com.example.procpulse.core.ui.PrecisionFilterChip
import com.example.procpulse.core.ui.PrecisionSlider
import com.example.procpulse.core.ui.PrecisionSwitch
import com.example.ui.theme.LocalProcPulsePalette
import com.example.ui.theme.TnumText
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlayToolsScreen(
    config: OverlayConfig,
    canDrawOverlays: Boolean,
    snapshot: SystemSnapshot?,
    onUpdateConfig: (OverlayConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalProcPulsePalette.current
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!canDrawOverlays) {
            item {
                PrecisionCard(title = "Разрешение на оверлей") {
                    Text(
                        text = "Для отображения плавающего HUD поверх игр и приложений необходимо разрешение SYSTEM_ALERT_WINDOW.",
                        style = TnumText.DataRow,
                        color = palette.textSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = Color(0xFF09090B)
                        ),
                        modifier = Modifier.testTag("grant_overlay_permission_btn")
                    ) {
                        Text("Разрешить поверх других окон", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }

        // Main Controls
        item {
            PrecisionCard(title = "Плавающий HUD-оверлей") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Включить HUD-оверлей", style = TnumText.DataRow.copy(fontWeight = FontWeight.Bold), color = palette.textPrimary)
                        Text("Системное окно с привязкой к FGS (specialUse)", style = TnumText.MicroLabel, color = palette.textMuted)
                    }
                    PrecisionSwitch(
                        checked = config.enabled,
                        enabled = canDrawOverlays,
                        onCheckedChange = { onUpdateConfig(config.copy(enabled = it)) },
                        modifier = Modifier.testTag("hud_enable_switch")
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pass-through (сквозные касания)", style = TnumText.DataRow, color = palette.textPrimary)
                        Text("Клики проходят сквозь HUD в игру. Отключается из шторки.", style = TnumText.MicroLabel, color = palette.textMuted)
                    }
                    PrecisionSwitch(
                        checked = config.passThrough,
                        onCheckedChange = { onUpdateConfig(config.copy(passThrough = it)) },
                        modifier = Modifier.testTag("hud_passthrough_switch")
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Прилипание к краям экрана", style = TnumText.DataRow, color = palette.textPrimary)
                        Text("Автовыравнивание при перетаскивании", style = TnumText.MicroLabel, color = palette.textMuted)
                    }
                    PrecisionSwitch(
                        checked = config.snapToEdges,
                        onCheckedChange = { onUpdateConfig(config.copy(snapToEdges = it)) }
                    )
                }
            }
        }

        // Dynamic Live HUD Preview strictly based on selected metrics and real telemetry
        item {
            PrecisionCard(title = "Предпросмотр HUD (${config.preset.label})") {
                val previewBg = Color(0xFF09090B).copy(alpha = config.backgroundOpacity)
                val previewParts = buildPreviewMetricTokens(config, snapshot)
                val primaryLine = when (config.preset) {
                    OverlayPreset.SINGLE_LINE -> previewParts.joinToString(" | ").ifEmpty { "Нет выбранных метрик" }
                    OverlayPreset.CORNER_SPARKLINE -> previewParts.take(3).joinToString(" · ").ifEmpty { "Нет метрик" }
                    OverlayPreset.GAMING_HUD -> previewParts.take(2).joinToString(" · ").ifEmpty { "Нет метрик" }
                }
                val secondaryTokens = when (config.preset) {
                    OverlayPreset.SINGLE_LINE -> emptyList()
                    OverlayPreset.CORNER_SPARKLINE -> previewParts.drop(3)
                    OverlayPreset.GAMING_HUD -> previewParts.drop(2)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.innerSurface)
                        .border(1.dp, palette.border, RoundedCornerShape(6.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(previewBg)
                            .border(1.dp, palette.border, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = primaryLine,
                            style = TnumText.DataRow.copy(fontSize = config.fontSizeSp.sp, fontWeight = FontWeight.Bold),
                            color = palette.accent
                        )
                        if (secondaryTokens.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = secondaryTokens.joinToString(" · "),
                                style = TnumText.MicroLabel.copy(fontSize = (config.fontSizeSp - 1).coerceAtLeast(9).sp),
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Presets & Metrics
        item {
            PrecisionCard(title = "Пресет и метрики конструктора") {
                Text("Форма виджета:", style = TnumText.MicroLabel, color = palette.textSecondary)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OverlayPreset.entries.forEach { preset ->
                        PrecisionFilterChip(
                            selected = config.preset == preset,
                            onClick = { onUpdateConfig(config.copy(preset = preset)) },
                            label = preset.label
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Отображаемые датчики:", style = TnumText.MicroLabel, color = palette.textSecondary)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OverlayWidgetMetric.entries.forEach { metric ->
                        val active = metric in config.metrics
                        PrecisionFilterChip(
                            selected = active,
                            onClick = {
                                val updated = if (active) config.metrics - metric else config.metrics + metric
                                if (updated.isNotEmpty()) {
                                    onUpdateConfig(config.copy(metrics = updated))
                                }
                            },
                            label = metric.label
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Прозрачность фона: ${(config.backgroundOpacity * 100).toInt()}%", style = TnumText.MicroLabel, color = palette.textSecondary)
                PrecisionSlider(
                    value = config.backgroundOpacity,
                    onValueChange = { onUpdateConfig(config.copy(backgroundOpacity = it)) },
                    valueRange = 0.25f..1.0f
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Шрифт HUD: ${config.fontSizeSp}sp", style = TnumText.MicroLabel, color = palette.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(10, 11, 13, 15).forEach { sz ->
                            val selected = config.fontSizeSp == sz
                            OutlinedButton(
                                onClick = { onUpdateConfig(config.copy(fontSizeSp = sz)) },
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, if (selected) palette.accent else palette.border)
                            ) {
                                Text(
                                    text = "${sz}sp",
                                    style = TnumText.MicroLabel.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (selected) palette.accent else palette.textSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quick Settings Tiles Info
        item {
            PrecisionCard(title = "Плитки быстрых настроек (Quick Settings)") {
                Text(
                    text = "В системной шторке Android доступны две плитки ProcPulse: «ProcPulse HUD» (мгновенное включение оверлея) и «Blackbox Запись» (старт/стоп записи телеметрии без открытия приложения).",
                    style = TnumText.MicroLabel,
                    color = palette.textSecondary
                )
            }
        }
    }
}

private fun buildPreviewMetricTokens(
    config: OverlayConfig,
    snapshot: SystemSnapshot?
): List<String> {
    val ordered = listOf(
        OverlayWidgetMetric.FPS,
        OverlayWidgetMetric.FRAME_TIME,
        OverlayWidgetMetric.CPU,
        OverlayWidgetMetric.TEMP,
        OverlayWidgetMetric.POWER,
        OverlayWidgetMetric.RAM_ZRAM,
        OverlayWidgetMetric.NET,
        OverlayWidgetMetric.TOP_PROCESS
    )
    return ordered.filter { it in config.metrics }.map { metric ->
        when (metric) {
            OverlayWidgetMetric.FPS -> {
                val fps = snapshot?.fps?.takeIf { it.available }?.fps
                if (fps != null) "${fps.toInt()} FPS" else "-- FPS · Tier 1/2"
            }
            OverlayWidgetMetric.FRAME_TIME -> {
                val ft = snapshot?.fps?.takeIf { it.available }?.frameTimeMs
                if (ft != null) "${"%.1f".format(Locale.US, ft)}ms" else "-- ms"
            }
            OverlayWidgetMetric.CPU -> {
                val cpu = snapshot?.cpu?.totalUsagePercent
                if (cpu != null) "CPU ${cpu.toInt()}%" else "CPU --"
            }
            OverlayWidgetMetric.TEMP -> {
                val t = snapshot?.battery?.tempCelsius
                if (t != null) "${"%.1f".format(Locale.US, t)}°C" else "--°C"
            }
            OverlayWidgetMetric.POWER -> {
                val b = snapshot?.battery
                if (b != null) "${b.currentMa.toInt()}mA · ${"%.1f".format(Locale.US, b.powerWatts)}W" else "--mA"
            }
            OverlayWidgetMetric.RAM_ZRAM -> {
                val m = snapshot?.memory
                if (m != null && m.totalMb > 0L) {
                    val used = (m.totalMb - m.availableMb).coerceAtLeast(0L)
                    "RAM ${used}/${m.totalMb}M"
                } else {
                    "RAM --/--M"
                }
            }
            OverlayWidgetMetric.NET -> {
                val n = snapshot?.network
                if (n != null) {
                    val rxK = (n.wifiRxBytesPerSec + n.cellRxBytesPerSec) / 1024L
                    val txK = (n.wifiTxBytesPerSec + n.cellTxBytesPerSec) / 1024L
                    "↓${rxK}K ↑${txK}K"
                } else {
                    "↓0K ↑0K"
                }
            }
            OverlayWidgetMetric.TOP_PROCESS -> {
                val top = snapshot?.topCpuConsumers?.firstOrNull() ?: snapshot?.topRamConsumers?.firstOrNull()
                if (top != null) {
                    val pct = top.cpuPercent?.let { " ${it.toInt()}%" } ?: ""
                    "${top.displayName}$pct"
                } else {
                    "Top --"
                }
            }
        }
    }
}
