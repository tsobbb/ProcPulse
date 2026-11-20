package com.example.procpulse.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.procpulse.core.bridge.CachedAppMeta
import com.example.procpulse.core.model.AccentPreset
import com.example.procpulse.core.model.AppSettings
import com.example.procpulse.core.model.CapabilityTier
import com.example.procpulse.core.model.CurrentCalibrationMode
import com.example.procpulse.core.model.PreferredTierMode
import com.example.procpulse.core.model.SystemSnapshot
import com.example.procpulse.core.model.ThemeMode
import com.example.procpulse.core.ui.PrecisionCard
import com.example.procpulse.core.ui.PrecisionFilterChip
import com.example.procpulse.core.ui.PrecisionSwitch
import com.example.ui.theme.LocalProcPulsePalette
import com.example.ui.theme.TnumText

private enum class ListEditorMode {
    WHITELIST,
    BLACKLIST
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    snapshot: SystemSnapshot?,
    isBatteryOptIgnored: Boolean,
    loadInstalledApps: suspend () -> List<CachedAppMeta>,
    onBack: () -> Unit,
    onSelectTheme: (ThemeMode) -> Unit,
    onSelectAccent: (AccentPreset) -> Unit,
    onSelectPreferredTier: (PreferredTierMode) -> Unit,
    onConnectShizuku: () -> Unit,
    onRequestRoot: () -> Unit,
    onInvertCurrent: (Boolean) -> Unit,
    onSelectCalibration: (CurrentCalibrationMode) -> Unit,
    onBootWatchdogToggle: (Boolean) -> Unit,
    onAddWhitelist: (String) -> Unit,
    onRemoveWhitelist: (String) -> Unit,
    onToggleBlacklist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalProcPulsePalette.current
    val context = LocalContext.current
    var editorMode by remember { mutableStateOf<ListEditorMode?>(null) }

    BackHandler(onBack = onBack)

    Column(modifier = modifier.fillMaxSize()) {
        // Dedicated Settings header bar (global TopAppBar is hidden when Settings is open)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.card)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("settings_back_btn")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Назад",
                        tint = palette.textPrimary
                    )
                }
                Text(
                    text = "НАСТРОЙКИ И КАЛИБРОВКА",
                    style = TnumText.SectionHeader.copy(fontWeight = FontWeight.Bold),
                    color = palette.textPrimary
                )
            }
            HorizontalDivider(color = palette.border)
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Capability Tiers (2x2 equal-width grid)
            item {
                val activeTier = snapshot?.tier ?: CapabilityTier.STANDARD
                PrecisionCard(
                    title = "Системный доступ (Capability Tiers)",
                    trailingBadge = {
                        Text(activeTier.shortLabel, style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.accent)
                    }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PreferredTierMode.entries.chunked(2).forEach { rowModes ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowModes.forEach { mode ->
                                    PrecisionFilterChip(
                                        selected = settings.preferredTierMode == mode,
                                        onClick = { onSelectPreferredTier(mode) },
                                        label = mode.label,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
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

            // 2. Power Sensor Calibration (2x2 equal-width grid)
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
                        PrecisionSwitch(
                            checked = settings.invertCurrentSign,
                            onCheckedChange = onInvertCurrent,
                            modifier = Modifier.testTag("invert_current_switch")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Множитель единиц измерения:", style = TnumText.MicroLabel, color = palette.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CurrentCalibrationMode.entries.chunked(2).forEach { rowModes ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowModes.forEach { m ->
                                    PrecisionFilterChip(
                                        selected = settings.currentCalibrationMode == m,
                                        onClick = { onSelectCalibration(m) },
                                        label = m.label,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
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
                            PrecisionFilterChip(
                                selected = settings.themeMode == m,
                                onClick = { onSelectTheme(m) },
                                label = m.label
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Акцент интерфейса:", style = TnumText.MicroLabel, color = palette.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccentPreset.entries.forEach { p ->
                            PrecisionFilterChip(
                                selected = settings.accentPreset == p,
                                onClick = { onSelectAccent(p) },
                                label = p.label
                            )
                        }
                    }
                }
            }

            // 4. Protected Whitelist & Blacklist (Clickable managers + inline list)
            item {
                PrecisionCard(title = "Белый и Чёрный списки") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(palette.innerSurface)
                            .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                            .clickable { editorMode = ListEditorMode.WHITELIST }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .testTag("manage_whitelist_btn"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Защищённый Whitelist (${settings.whitelistPackages.size})",
                                style = TnumText.DataRow.copy(fontWeight = FontWeight.Bold),
                                color = palette.okGreen
                            )
                            Text(
                                text = "SystemUI, лаунчер и ProcPulse защищены автоматически",
                                style = TnumText.MicroLabel,
                                color = palette.textMuted
                            )
                        }
                        Text("Управлять →", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.accent)
                    }
                    if (settings.whitelistPackages.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        settings.whitelistPackages.forEach { pkg ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(pkg, style = TnumText.MicroLabel, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onRemoveWhitelist(pkg) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Удалить", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(palette.innerSurface)
                            .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                            .clickable { editorMode = ListEditorMode.BLACKLIST }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .testTag("manage_blacklist_btn"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Blacklist для Watchdog (${settings.blacklistPackages.size})",
                                style = TnumText.DataRow.copy(fontWeight = FontWeight.Bold),
                                color = palette.critRed
                            )
                            Text(
                                text = "Нажмите для выбора приложений или ручного ввода пакета",
                                style = TnumText.MicroLabel,
                                color = palette.textMuted
                            )
                        }
                        Text("Управлять →", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.accent)
                    }
                    if (settings.blacklistPackages.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        settings.blacklistPackages.forEach { pkg ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(pkg, style = TnumText.MicroLabel, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onToggleBlacklist(pkg) }, modifier = Modifier.size(32.dp)) {
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
                        PrecisionSwitch(checked = settings.startWatchdogOnBoot, onCheckedChange = onBootWatchdogToggle)
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

    editorMode?.let { mode ->
        PackageListEditorDialog(
            mode = mode,
            selectedPackages = if (mode == ListEditorMode.WHITELIST) settings.whitelistPackages else settings.blacklistPackages,
            loadInstalledApps = loadInstalledApps,
            onTogglePackage = { pkg ->
                if (mode == ListEditorMode.WHITELIST) {
                    if (pkg in settings.whitelistPackages) onRemoveWhitelist(pkg) else onAddWhitelist(pkg)
                } else {
                    onToggleBlacklist(pkg)
                }
            },
            onDismiss = { editorMode = null }
        )
    }
}

@Composable
private fun PackageListEditorDialog(
    mode: ListEditorMode,
    selectedPackages: Set<String>,
    loadInstalledApps: suspend () -> List<CachedAppMeta>,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalProcPulsePalette.current
    var apps by remember { mutableStateOf<List<CachedAppMeta>>(emptyList()) }
    var manualPkg by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = loadInstalledApps()
            .filter { !it.isSystem || it.packageName in selectedPackages }
            .sortedWith(
                compareByDescending<CachedAppMeta> { it.packageName in selectedPackages }
                    .thenBy { it.displayName.lowercase() }
            )
    }

    val filtered = remember(apps, search, selectedPackages) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) apps
        else apps.filter { it.displayName.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = {
            Text(
                text = if (mode == ListEditorMode.WHITELIST) "Редактор Whitelist" else "Редактор Blacklist",
                style = TnumText.SectionHeader.copy(fontWeight = FontWeight.Bold),
                color = palette.textPrimary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualPkg,
                        onValueChange = { manualPkg = it },
                        placeholder = { Text("com.example.app", style = TnumText.MicroLabel) },
                        singleLine = true,
                        textStyle = TnumText.MicroLabel,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            val clean = manualPkg.trim()
                            if (clean.isNotEmpty()) {
                                if (clean !in selectedPackages) onTogglePackage(clean)
                                manualPkg = ""
                            }
                        },
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Добавить", style = TnumText.MicroLabel)
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Фильтр приложений", style = TnumText.MicroLabel) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    singleLine = true,
                    textStyle = TnumText.MicroLabel,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = app.packageName in selectedPackages
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTogglePackage(app.packageName) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onTogglePackage(app.packageName) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = palette.accent,
                                    checkmarkColor = palette.background
                                )
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.displayName, style = TnumText.DataRow, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, style = TnumText.MicroLabel, color = palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Готово", style = TnumText.DataRow.copy(fontWeight = FontWeight.Bold), color = palette.accent)
            }
        }
    )
}
