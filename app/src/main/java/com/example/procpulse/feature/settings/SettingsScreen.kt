package com.example.procpulse.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.procpulse.core.model.AccentPreset
import com.example.procpulse.core.model.AppSettings
import com.example.procpulse.core.model.CapabilityTier
import com.example.procpulse.core.model.CurrentCalibrationMode
import com.example.procpulse.core.model.PreferredTierMode
import com.example.procpulse.core.model.SystemSnapshot
import com.example.procpulse.core.model.ThemeMode
import com.example.procpulse.core.ui.PrecisionCard
import com.example.ui.theme.LocalProcPulsePalette
import com.example.ui.theme.TnumText

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    snapshot: SystemSnapshot?,
    isBatteryOptIgnored: Boolean,
    onBack: () -> Unit,
    onSelectTheme: (ThemeMode) -> Unit,
    onSelectAccent: (AccentPreset) -> Unit,
    onSelectPreferredTier: (PreferredTierMode) -> Unit,
    onConnectShizuku: () -> Unit,
    onRequestRoot: () -> Unit,
    onInvertCurrent: (Boolean) -> Unit,
    onSelectCalibration: (CurrentCalibrationMode) -> Unit,
    onBootWatchdogToggle: (Boolean) -> Unit,
    onRemoveWhitelist: (String) -> Unit,
    onRemoveBlacklist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalProcPulsePalette.current
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("settings_back_btn")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                }
                Text("НАСТРОЙКИ И КАЛИБРОВКА", style = TnumText.SectionHeader, color = palette.textPrimary)
            }
        }

        // 1. Capability Tiers
        item {
            val activeTier = snapshot?.tier ?: CapabilityTier.STANDARD
            PrecisionCard(
                title = "Системный доступ (Capability Tiers)",
                trailingBadge = {
                    Text(activeTier.shortLabel, style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.accent)
                }
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PreferredTierMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.preferredTierMode == mode,
                            onClick = { onSelectPreferredTier(mode) },
                            label = { Text(mode.label, style = TnumText.MicroLabel) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Shizuku Binder: ${if (snapshot?.shizukuAvailable == true) "Обнаружен" else "Не запущен"} · Права: ${if (snapshot?.shizukuAuthorized == true) "Выданы" else "Нет"}",
                    style = TnumText.MicroLabel,
                    color = palette.textSecondary
                )
                Text(
                    text = "Root (su): ${if (snapshot?.rootAvailable == true) "Подключён" else "Не активирован"}",
                    style = TnumText.MicroLabel,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onConnectShizuku, shape = RoundedCornerShape(4.dp)) {
                        Text("Подключить Shizuku", style = TnumText.MicroLabel)
                    }
                    OutlinedButton(onClick = onRequestRoot, shape = RoundedCornerShape(4.dp)) {
                        Text("Проверить Root", style = TnumText.MicroLabel)
                    }
                }
            }
        }

        // 2. Power Sensor Calibration
        item {
            PrecisionCard(title = "Калибровка датчиков питания (OEM)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Инверсия знака тока (+/-)", style = TnumText.DataRow, color = palette.textPrimary)
                        Text("Исправляет знак BATTERY_PROPERTY_CURRENT_NOW на некоторых OEM", style = TnumText.MicroLabel, color = palette.textMuted)
                    }
                    Switch(
                        checked = settings.invertCurrentSign,
                        onCheckedChange = onInvertCurrent,
                        modifier = Modifier.testTag("invert_current_switch")
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Множитель единиц измерения:", style = TnumText.MicroLabel, color = palette.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CurrentCalibrationMode.entries.forEach { m ->
                        FilterChip(
                            selected = settings.currentCalibrationMode == m,
                            onClick = { onSelectCalibration(m) },
                            label = { Text(m.label, style = TnumText.MicroLabel) }
                        )
                    }
                }
            }
        }

        // 3. Appearance (Theme & Accent)
        item {
            PrecisionCard(title = "Внешний вид (Utilitarian Precision)") {
                Text("Тема:", style = TnumText.MicroLabel, color = palette.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = settings.themeMode == m,
                            onClick = { onSelectTheme(m) },
                            label = { Text(m.label, style = TnumText.MicroLabel) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Акцент интерфейса:", style = TnumText.MicroLabel, color = palette.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccentPreset.entries.forEach { p ->
                        FilterChip(
                            selected = settings.accentPreset == p,
                            onClick = { onSelectAccent(p) },
                            label = { Text(p.label, style = TnumText.MicroLabel) }
                        )
                    }
                }
            }
        }

        // 4. Protected Whitelist & Blacklist
        item {
            PrecisionCard(title = "Белый и Чёрный списки") {
                Text("Защищённый Whitelist (${settings.whitelistPackages.size}):", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.okGreen)
                if (settings.whitelistPackages.isEmpty()) {
                    Text("Пользовательских исключений нет (SystemUI, лаунчер и ProcPulse защищены автоматически).", style = TnumText.MicroLabel, color = palette.textMuted)
                } else {
                    settings.whitelistPackages.forEach { pkg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(pkg, style = TnumText.MicroLabel, color = palette.textPrimary)
                            IconButton(onClick = { onRemoveWhitelist(pkg) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = "Удалить", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Blacklist для Watchdog (${settings.blacklistPackages.size}):", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.critRed)
                if (settings.blacklistPackages.isEmpty()) {
                    Text("Пакеты в Blacklist не добавлены (настраивается в Инспекторе процесса).", style = TnumText.MicroLabel, color = palette.textMuted)
                } else {
                    settings.blacklistPackages.forEach { pkg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(pkg, style = TnumText.MicroLabel, color = palette.textPrimary)
                            IconButton(onClick = { onRemoveBlacklist(pkg) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = "Удалить", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }

        // 5. Background Execution & Privacy
        item {
            PrecisionCard(title = "Фоновая работа и Автономность") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Автозапуск Watchdog при загрузке ОС", style = TnumText.DataRow, color = palette.textPrimary)
                        Text("Опциональный триггер BOOT_COMPLETED", style = TnumText.MicroLabel, color = palette.textMuted)
                    }
                    Switch(checked = settings.startWatchdogOnBoot, onCheckedChange = onBootWatchdogToggle)
                }
                Spacer(Modifier.height(8.dp))
                if (!isBatteryOptIgnored) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        },
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, palette.accent)
                    ) {
                        Text("Отключить оптимизацию батареи (для 8ч Blackbox)", style = TnumText.MicroLabel, color = palette.accent)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = palette.okGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "OFFLINE-FIRST: Разрешение INTERNET отсутствует в манифесте APK.",
                        style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                        color = palette.okGreen
                    )
                }
            }
        }
    }
}
