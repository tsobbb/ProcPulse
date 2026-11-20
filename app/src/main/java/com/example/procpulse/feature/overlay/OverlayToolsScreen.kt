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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
                        modifier = Modifier.testTag("grant_overlay_permission_btn")
                    ) {
                        Text("Разрешить поверх других окон", style = TnumText.MicroLabel)
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
                    Switch(
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
                    Switch(
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
                    Switch(
                        checked = config.snapToEdges,
                        onCheckedChange = { onUpdateConfig(config.copy(snapToEdges = it)) }
                    )
                }
            }
        }

        // Live HUD Preview
        item {
            PrecisionCard(title = "Предпросмотр HUD (${config.preset.label})") {
                val previewBg = Color(0xFF09090B).copy(alpha = config.backgroundOpacity)
                val cpuTxt = snapshot?.cpu?.totalUsagePercent?.let { "CPU ${it.toInt()}%" } ?: "CPU 34%"
                val tempTxt = snapshot?.battery?.tempCelsius?.let { "${"%.1f".format(Locale.US, it)}°C" } ?: "38.2°C"
                val fpsTxt = snapshot?.fps?.fps?.let { "${it.toInt()} FPS" } ?: "60 FPS"
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
                            text = when (config.preset) {
                                OverlayPreset.SINGLE_LINE -> "$fpsTxt | $cpuTxt | $tempTxt | -420mA"
                                OverlayPreset.CORNER_SPARKLINE -> "$cpuTxt · $tempTxt · 2.1W"
                                OverlayPreset.GAMING_HUD -> "$fpsTxt (16.6ms)"
                            },
                            style = TnumText.DataRow.copy(fontSize = config.fontSizeSp.sp, fontWeight = FontWeight.Bold),
                            color = palette.accent
                        )
                        if (config.preset != OverlayPreset.SINGLE_LINE) {
                            Text(
                                text = "RAM 4120/8192M · ↓12K ↑4K",
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
                        FilterChip(
                            selected = config.preset == preset,
                            onClick = { onUpdateConfig(config.copy(preset = preset)) },
                            label = { Text(preset.label, style = TnumText.MicroLabel) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Отображаемые датчики:", style = TnumText.MicroLabel, color = palette.textSecondary)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OverlayWidgetMetric.entries.forEach { metric ->
                        val active = metric in config.metrics
                        FilterChip(
                            selected = active,
                            onClick = {
                                val updated = if (active) config.metrics - metric else config.metrics + metric
                                if (updated.isNotEmpty()) {
                                    onUpdateConfig(config.copy(metrics = updated))
                                }
                            },
                            label = { Text(metric.label, style = TnumText.MicroLabel) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Прозрачность фона: ${(config.backgroundOpacity * 100).toInt()}%", style = TnumText.MicroLabel, color = palette.textSecondary)
                Slider(
                    value = config.backgroundOpacity,
                    onValueChange = { onUpdateConfig(config.copy(backgroundOpacity = it)) },
                    valueRange = 0.25f..1.0f
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Размер шрифта HUD: ${config.fontSizeSp}sp", style = TnumText.MicroLabel, color = palette.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(10, 11, 13, 15).forEach { sz ->
                            OutlinedButton(
                                onClick = { onUpdateConfig(config.copy(fontSizeSp = sz)) },
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, if (config.fontSizeSp == sz) palette.accent else palette.border)
                            ) {
                                Text("${sz}sp", style = TnumText.MicroLabel)
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
