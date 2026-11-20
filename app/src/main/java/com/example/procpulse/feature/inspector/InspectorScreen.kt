package com.example.procpulse.feature.inspector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shield
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.procpulse.core.model.CapabilityTier
import com.example.procpulse.core.model.LogcatEntry
import com.example.procpulse.core.model.ProcessDetail
import com.example.procpulse.core.ui.HonestDegradationBanner
import com.example.procpulse.core.ui.PrecisionCard
import com.example.ui.theme.LocalProcPulsePalette
import com.example.ui.theme.TnumText
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InspectorScreen(
    detail: ProcessDetail?,
    activeTier: CapabilityTier,
    shizukuAvailable: Boolean,
    isWhitelisted: Boolean,
    isBlacklisted: Boolean,
    logcatEntries: List<LogcatEntry>,
    logcatPaused: Boolean,
    logcatLevel: Char,
    logcatFilter: String,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onConnectShizuku: () -> Unit,
    onRequestRoot: () -> Unit,
    onOpenAppInfo: (String) -> Unit,
    onKillBackground: (String) -> Unit,
    onForceStop: (String) -> Unit,
    onFreezeToggle: (String, Boolean) -> Unit,
    onRestrictBackgroundToggle: (String, Boolean) -> Unit,
    onSendSignal: (Int, Int) -> Unit,
    onRenice: (Int, Int) -> Unit,
    onTaskset: (Int, String) -> Unit,
    onToggleComponent: (String, String, Boolean) -> Unit,
    onToggleWhitelist: (String) -> Unit,
    onToggleBlacklist: (String) -> Unit,
    onToggleLogcatPause: () -> Unit,
    onLogcatLevelChange: (Char) -> Unit,
    onLogcatFilterChange: (String) -> Unit,
    onExportJson: (ProcessDetail) -> Unit,
    onExportMarkdown: (ProcessDetail) -> Unit,
    onExportLogcat: (String, List<LogcatEntry>) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalProcPulsePalette.current

    if (showBackButton) {
        BackHandler(onBack = onBack)
    }

    if (detail == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Выберите процесс из списка для глубокой инспекции",
                style = TnumText.DataRow,
                color = palette.textMuted
            )
        }
        return
    }

    val entry = detail.entry
    val filteredLogs = remember(logcatEntries, logcatLevel, logcatFilter) {
        val order = mapOf('V' to 0, 'D' to 1, 'I' to 2, 'W' to 3, 'E' to 4)
        val minLvl = order[logcatLevel] ?: 0
        val q = logcatFilter.trim().lowercase()
        logcatEntries.filter { log ->
            (order[log.level] ?: 0) >= minLvl &&
                (q.isEmpty() || log.tag.lowercase().contains(q) || log.message.lowercase().contains(q))
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header Bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (showBackButton) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("inspector_back_button")) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                        }
                    }
                    Column {
                        Text(
                            text = entry.displayName,
                            style = TnumText.CardMetric,
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${entry.packageName} · PID ${entry.pid} · UID ${entry.uid}",
                            style = TnumText.MicroLabel,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { onExportJson(detail) },
                        modifier = Modifier.height(34.dp).testTag("export_proc_json_button"),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("JSON", style = TnumText.MicroLabel)
                    }
                    OutlinedButton(
                        onClick = { onExportMarkdown(detail) },
                        modifier = Modifier.height(34.dp).testTag("export_proc_md_button"),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("MD", style = TnumText.MicroLabel)
                    }
                }
            }
        }

        if (detail.tierLimitationReason != null) {
            item {
                HonestDegradationBanner(
                    reason = detail.tierLimitationReason,
                    shizukuAvailable = shizukuAvailable,
                    onConnectShizuku = onConnectShizuku,
                    onRequestRoot = onRequestRoot
                )
            }
        }

        // 1. Memory Breakdown
        item {
            PrecisionCard(
                title = "Детальная память процесса",
                trailingBadge = {
                    detail.ussMb?.let {
                        Text(
                            text = "ОСВОБОДИТСЯ: ${"%.1f".format(Locale.US, it)} MB",
                            style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                            color = palette.accent
                        )
                    }
                }
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemCell("PSS", detail.pssMb)
                    MemCell("USS (Private)", detail.ussMb, highlight = true)
                    MemCell("RSS", detail.rssMb)
                    MemCell("Swap/ZRAM", detail.swapZramMb)
                    MemCell("Graphics/EGL", detail.graphicsEglMb)
                    MemCell("Native Heap", detail.nativeHeapMb)
                    MemCell("ART Heap", detail.artHeapMb)
                }
            }
        }

        // 2. Kernel & Lifecycle + OOM Score Scale
        item {
            PrecisionCard(title = "Параметры ядра и жизненного цикла") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("PPID: ${entry.ppid}", style = TnumText.DataRow, color = palette.textPrimary)
                    Text("Nice: ${detail.nice ?: "--"}", style = TnumText.DataRow, color = palette.textPrimary)
                    Text("Threads: ${detail.threadsCount ?: "--"}", style = TnumText.DataRow, color = palette.textPrimary)
                    Text("FD: ${detail.fdCount ?: "--"}", style = TnumText.DataRow, color = palette.textPrimary)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Standby: ${detail.standbyBucket}", style = TnumText.MicroLabel, color = palette.textSecondary)
                    Text("Affinity: ${detail.affinityMask ?: "N/A"}", style = TnumText.MicroLabel, color = palette.textSecondary)
                }
                Spacer(modifier = Modifier.height(8.dp))
                val oom = detail.oomScoreAdj
                Text(
                    text = "oom_score_adj: ${oom ?: "N/A (Tier 1/2)"}",
                    style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                    color = palette.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                val oomNorm = if (oom != null) ((oom + 1000) / 2000f).coerceIn(0f, 1f) else 0.5f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.innerSurface)
                        .border(1.dp, palette.border, RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(oomNorm)
                            .height(8.dp)
                            .background(if ((oom ?: 0) > 300) palette.critRed else if ((oom ?: 0) > 0) palette.warnAmber else palette.okGreen)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("-1000 (Защита)", style = TnumText.MicroLabel, color = palette.textMuted)
                    Text("0 (FG)", style = TnumText.MicroLabel, color = palette.textMuted)
                    Text("+1000 (Киллер LMKD)", style = TnumText.MicroLabel, color = palette.textMuted)
                }
            }
        }

        // 3. Task Control Deck
        item {
            var niceSlider by remember(detail.nice) { mutableFloatStateOf((detail.nice ?: 0).toFloat()) }
            PrecisionCard(title = "Управление процессом (${activeTier.shortLabel})") {
                // Whitelist / Blacklist toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onToggleWhitelist(entry.packageName) },
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, if (isWhitelisted) palette.okGreen else palette.border)
                    ) {
                        Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (isWhitelisted) palette.okGreen else palette.textSecondary)
                        Spacer(Modifier.width(4.dp))
                        Text(if (isWhitelisted) "В Whitelist" else "+ Whitelist", style = TnumText.MicroLabel)
                    }
                    OutlinedButton(
                        onClick = { onToggleBlacklist(entry.packageName) },
                        modifier = Modifier.weight(1f).height(38.dp),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, if (isBlacklisted) palette.critRed else palette.border)
                    ) {
                        Text(if (isBlacklisted) "В Blacklist" else "+ Blacklist", style = TnumText.MicroLabel, color = if (isBlacklisted) palette.critRed else palette.textSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Tier 0 actions
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { onOpenAppInfo(entry.packageName) },
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("О приложении (ОС)", style = TnumText.MicroLabel)
                    }
                    OutlinedButton(
                        onClick = { onKillBackground(entry.packageName) },
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Kill Background (Tier 0)", style = TnumText.MicroLabel)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                // Tier 1 (Shizuku) actions
                Text("TIER 1 (SHIZUKU / SHELL)", style = TnumText.MicroLabel, color = palette.textMuted)
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { onForceStop(entry.packageName) },
                        enabled = activeTier != CapabilityTier.STANDARD && !entry.isProtected,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.critRed),
                        modifier = Modifier.height(38.dp).testTag("inspector_force_stop_btn"),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Force Stop", style = TnumText.MicroLabel)
                    }
                    OutlinedButton(
                        onClick = { onFreezeToggle(entry.packageName, !entry.isFrozenOrStopped) },
                        enabled = activeTier != CapabilityTier.STANDARD && !entry.isProtected,
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(if (entry.isFrozenOrStopped) "Unfreeze" else "Freeze (Suspend)", style = TnumText.MicroLabel)
                    }
                    OutlinedButton(
                        onClick = { onRestrictBackgroundToggle(entry.packageName, !detail.isRestrictedBackground) },
                        enabled = activeTier != CapabilityTier.STANDARD,
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(if (detail.isRestrictedBackground) "Разрешить фон" else "Запретить фон", style = TnumText.MicroLabel)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                // Tier 2 (Root) actions
                Text("TIER 2 (ROOT KERNEL CONTROL)", style = TnumText.MicroLabel, color = palette.textMuted)
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { onSendSignal(entry.pid, 9) },
                        enabled = activeTier == CapabilityTier.ROOT && !entry.isProtected,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.critRed),
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("SIGKILL (-9)", style = TnumText.MicroLabel)
                    }
                    OutlinedButton(
                        onClick = { onSendSignal(entry.pid, if (entry.isFrozenOrStopped) 18 else 19) },
                        enabled = activeTier == CapabilityTier.ROOT && !entry.isProtected,
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(if (entry.isFrozenOrStopped) "SIGCONT (Resume)" else "SIGSTOP (Pause in RAM)", style = TnumText.MicroLabel)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Renice: ${niceSlider.roundToInt()}",
                        style = TnumText.MicroLabel,
                        color = palette.textPrimary,
                        modifier = Modifier.width(84.dp)
                    )
                    Slider(
                        value = niceSlider,
                        onValueChange = { niceSlider = it },
                        valueRange = -20f..19f,
                        enabled = activeTier == CapabilityTier.ROOT,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { onRenice(entry.pid, niceSlider.roundToInt()) },
                        enabled = activeTier == CapabilityTier.ROOT,
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Set", style = TnumText.MicroLabel)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("0f" to "Eff (0x0F)", "f0" to "Perf (0xF0)", "ff" to "All (0xFF)").forEach { (mask, label) ->
                        OutlinedButton(
                            onClick = { onTaskset(entry.pid, mask) },
                            enabled = activeTier == CapabilityTier.ROOT,
                            modifier = Modifier.weight(1f).height(34.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(label, style = TnumText.MicroLabel)
                        }
                    }
                }
            }
        }

        // 4. OS Activity: WakeLocks, Services, Sockets
        item {
            PrecisionCard(title = "Активность в ОС (Services / WakeLocks / Sockets)") {
                Text("WakeLocks (${detail.wakeLocks.size}):", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.textSecondary)
                if (detail.wakeLocks.isEmpty()) {
                    Text("Нет активных или недоступно в ${activeTier.shortLabel}", style = TnumText.MicroLabel, color = palette.textMuted)
                } else {
                    detail.wakeLocks.take(6).forEach {
                        Text("• $it", style = TnumText.MicroLabel, color = palette.textPrimary)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("Запущенные Services (${detail.runningServices.size}):", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.textSecondary)
                if (detail.runningServices.isEmpty()) {
                    Text("Нет активных сервисов", style = TnumText.MicroLabel, color = palette.textMuted)
                } else {
                    detail.runningServices.take(6).forEach {
                        Text("• $it", style = TnumText.MicroLabel, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("Сокеты UID ${entry.uid} (${detail.sockets.size}):", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.textSecondary)
                if (detail.sockets.isEmpty()) {
                    Text("Нет активных соединений", style = TnumText.MicroLabel, color = palette.textMuted)
                } else {
                    detail.sockets.take(8).forEach { s ->
                        Text(
                            text = "${s.protocol} :${s.localPort} → ${s.remoteAddress}:${s.remotePort} [${s.state}]",
                            style = TnumText.MicroLabel,
                            color = palette.textPrimary
                        )
                    }
                }
            }
        }

        // 5. Components (Services & BroadcastReceivers)
        if (detail.components.isNotEmpty()) {
            item {
                PrecisionCard(title = "Компоненты (${detail.components.size})") {
                    detail.components.take(16).forEach { comp ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(comp.name.substringAfterLast('.'), style = TnumText.MicroLabel, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(comp.type, style = TnumText.MicroLabel, color = palette.textMuted)
                            }
                            Switch(
                                checked = comp.enabled,
                                enabled = activeTier == CapabilityTier.ROOT,
                                onCheckedChange = { enabled ->
                                    onToggleComponent(entry.packageName, comp.name, enabled)
                                }
                            )
                        }
                    }
                }
            }
        }

        // 6. Live Logcat
        item {
            PrecisionCard(
                title = "Live Logcat (PID ${entry.pid})",
                trailingBadge = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onToggleLogcatPause, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = if (logcatPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                contentDescription = "Пауза Logcat",
                                modifier = Modifier.size(16.dp),
                                tint = palette.accent
                            )
                        }
                        IconButton(
                            onClick = { onExportLogcat(entry.packageName, filteredLogs) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = "Экспорт .log", modifier = Modifier.size(16.dp), tint = palette.textPrimary)
                        }
                    }
                }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf('V', 'D', 'I', 'W', 'E').forEach { lvl ->
                        FilterChip(
                            selected = logcatLevel == lvl,
                            onClick = { onLogcatLevelChange(lvl) },
                            label = { Text(lvl.toString(), style = TnumText.MicroLabel) },
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = logcatFilter,
                    onValueChange = onLogcatFilterChange,
                    placeholder = { Text("Фильтр по тегу или тексту", style = TnumText.MicroLabel) },
                    singleLine = true,
                    textStyle = TnumText.MicroLabel,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (activeTier == CapabilityTier.STANDARD) {
                    Text("Чтение Live Logcat доступно в Tier 1 (Shizuku) и Tier 2 (Root).", style = TnumText.MicroLabel, color = palette.textMuted)
                } else if (filteredLogs.isEmpty()) {
                    Text("Нет записей лога для данного фильтра.", style = TnumText.MicroLabel, color = palette.textMuted)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(palette.innerSurface)
                            .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                            .padding(6.dp)
                    ) {
                        filteredLogs.takeLast(40).forEach { log ->
                            val lvlColor = when (log.level) {
                                'E' -> palette.critRed
                                'W' -> palette.warnAmber
                                'I' -> palette.okGreen
                                else -> palette.textSecondary
                            }
                            Text(
                                text = "${log.level}/${log.tag}: ${log.message}",
                                style = TnumText.MicroLabel,
                                color = lvlColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemCell(label: String, mb: Float?, highlight: Boolean = false) {
    val palette = LocalProcPulsePalette.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(palette.innerSurface)
            .border(1.dp, if (highlight) palette.accent else palette.border, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label, style = TnumText.MicroLabel, color = if (highlight) palette.accent else palette.textMuted)
        Text(
            text = mb?.let { "${"%.1f".format(Locale.US, it)} MB" } ?: "--",
            style = TnumText.DataRow.copy(fontWeight = FontWeight.Bold),
            color = palette.textPrimary
        )
    }
}
