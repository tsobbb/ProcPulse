package com.example.procpulse.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.procpulse.core.model.CapabilityTier
import com.example.procpulse.core.model.ChartSeriesSnapshot
import com.example.procpulse.core.model.CpuClusterRole
import com.example.procpulse.core.model.LagDiagnosticsSnapshot
import com.example.procpulse.core.model.ProcessEntry
import com.example.procpulse.core.model.SocketConnection
import com.example.procpulse.core.model.SysfsAvailability
import com.example.procpulse.core.model.SystemSnapshot
import com.example.procpulse.core.model.ThermalLevel
import com.example.procpulse.core.ui.HonestDegradationBanner
import com.example.procpulse.core.ui.PrecisionCard
import com.example.procpulse.core.ui.PrecisionFilterChip
import com.example.procpulse.core.ui.PrecisionLineChart
import com.example.procpulse.core.ui.SegmentedMemoryBar
import com.example.procpulse.core.ui.thresholdColor
import com.example.ui.theme.LocalProcPulsePalette
import com.example.ui.theme.TnumText
import java.util.Locale

@Composable
fun DashboardScreen(
    snapshot: SystemSnapshot?,
    isPaused: Boolean,
    samplingIntervalMs: Long,
    cpuChart: ChartSeriesSnapshot,
    ramChart: ChartSeriesSnapshot,
    powerChart: ChartSeriesSnapshot,
    tempChart: ChartSeriesSnapshot,
    sockets: List<SocketConnection>,
    onTogglePause: () -> Unit,
    onSelectSamplingInterval: (Long) -> Unit,
    onConnectShizuku: () -> Unit,
    onRequestRoot: () -> Unit,
    onOpenCalibration: () -> Unit,
    onLoadSockets: () -> Unit,
    onInspectProcess: (ProcessEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalProcPulsePalette.current
    var showSocketsDialog by remember { mutableStateOf(false) }

    if (snapshot == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "ИНИЦИАЛИЗАЦИЯ ТЕЛЕМЕТРИИ...",
                style = TnumText.DataRow,
                color = palette.textSecondary
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Sampling rate + Pause / Freeze bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val intervals = listOf(250L to "250мс", 500L to "500мс", 1000L to "1с", 2000L to "2с", 5000L to "5с")
                    intervals.forEach { (ms, label) ->
                        PrecisionFilterChip(
                            selected = samplingIntervalMs == ms,
                            onClick = { onSelectSamplingInterval(ms) },
                            label = label,
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = onTogglePause,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isPaused) palette.accent.copy(alpha = 0.2f) else palette.card)
                        .border(1.dp, if (isPaused) palette.accent else palette.border, RoundedCornerShape(6.dp))
                        .testTag("dashboard_pause_button")
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        contentDescription = if (isPaused) "Возобновить графики" else "Пауза графиков",
                        tint = if (isPaused) palette.accent else palette.textPrimary
                    )
                }
            }
        }

        // 1. CPU & SoC Card
        item {
            val cpu = snapshot.cpu
            val totalStr = cpu.totalUsagePercent?.let { "${"%.1f".format(Locale.US, it)}%" } ?: "N/A (Tier 0)"
            PrecisionCard(
                title = "CPU & SoC",
                trailingBadge = {
                    Text(
                        text = "GOV: ${cpu.activeGovernor.uppercase()}",
                        style = TnumText.MicroLabel,
                        color = palette.accent
                    )
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = totalStr,
                        style = TnumText.HeroMetric,
                        color = cpu.totalUsagePercent?.let { thresholdColor(it) } ?: palette.textSecondary
                    )
                    if (cpu.isUsageExact) {
                        Text(
                            text = "USR ${"%.1f".format(Locale.US, cpu.userPercent ?: 0f)}%  " +
                                "SYS ${"%.1f".format(Locale.US, cpu.kernelPercent ?: 0f)}%  " +
                                "IO ${"%.1f".format(Locale.US, cpu.ioWaitPercent ?: 0f)}%",
                            style = TnumText.MicroLabel,
                            color = palette.textSecondary
                        )
                    }
                }

                if (!cpu.isUsageExact) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HonestDegradationBanner(
                        reason = "SELinux блокирует прямое чтение /proc/stat в Tier 0. Доступны частоты ядер; для точной нагрузки CPU и переключений контекста подключите Shizuku или Root.",
                        shizukuAvailable = snapshot.shizukuAvailable,
                        onConnectShizuku = onConnectShizuku,
                        onRequestRoot = onRequestRoot
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    PrecisionLineChart(
                        series = cpuChart,
                        unitSuffix = "%",
                        fixedMax = 100f
                    )
                }

                if (cpu.contextSwitchesPerSec != null || cpu.interruptsPerSec != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("CTXT/s: ${cpu.contextSwitchesPerSec ?: 0}", style = TnumText.MicroLabel, color = palette.textMuted)
                        Text("INTR/s: ${cpu.interruptsPerSec ?: 0}", style = TnumText.MicroLabel, color = palette.textMuted)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                val clusters = cpu.cores.groupBy { it.clusterRole }.entries.sortedBy {
                    when (it.key) {
                        CpuClusterRole.PRIME -> 0
                        CpuClusterRole.PERF -> 1
                        CpuClusterRole.EFF -> 2
                    }
                }
                clusters.forEach { (role, clusterCores) ->
                    val maxF = clusterCores.maxOfOrNull { it.maxFreqMhz } ?: 0
                    Text(
                        text = "${clusterCores.size}x ${role.label.uppercase()} (MAX $maxF MHz)",
                        style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    clusterCores.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { core ->
                                val ratio = if (core.usagePercent != null) {
                                    (core.usagePercent / 100f).coerceIn(0f, 1f)
                                } else if (core.maxFreqMhz > 0) {
                                    (core.currentFreqMhz.toFloat() / core.maxFreqMhz.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                                // Red reserved strictly for real CPU > 85% / thermal throttling; Tier 0 freq bar uses accent
                                val barColor = if (core.usagePercent != null) {
                                    thresholdColor(core.usagePercent)
                                } else {
                                    palette.accent
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(palette.innerSurface)
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("C${core.coreIndex}", style = TnumText.MicroLabel, color = palette.textMuted)
                                        Text(
                                            text = if (core.usagePercent != null) {
                                                "${core.usagePercent.toInt()}% · ${core.currentFreqMhz}M"
                                            } else {
                                                "${core.currentFreqMhz}/${core.maxFreqMhz}M"
                                            },
                                            style = TnumText.MicroLabel,
                                            color = palette.textPrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .background(palette.border)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(ratio)
                                                .height(4.dp)
                                                .background(barColor)
                                        )
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        // 2. GPU, Bus & FPS Card
        item {
            val gpu = snapshot.gpu
            val fps = snapshot.fps
            PrecisionCard(
                title = "GPU, Шина памяти и FPS",
                trailingBadge = {
                    Text(
                        text = gpu.vendorName.take(16),
                        style = TnumText.MicroLabel,
                        color = palette.textMuted
                    )
                }
            ) {
                Text(
                    text = gpu.rendererName,
                    style = TnumText.DataRow,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricBlock(
                        label = "GPU LOAD",
                        value = gpu.usagePercent?.let { "${it.toInt()}%" } ?: "N/A",
                        sub = gpu.currentFreqMhz?.let { "$it / ${gpu.maxFreqMhz ?: it} MHz" }
                            ?: gpu.gpuAvailability.label
                    )
                    MetricBlock(
                        label = "DDR / BUS",
                        value = gpu.ddrFreqMhz?.let { "$it MHz" } ?: "N/A",
                        sub = if (gpu.ddrFreqMhz != null) "devfreq" else gpu.ddrAvailability.label
                    )
                    MetricBlock(
                        label = "LIVE FPS",
                        value = if (fps.available && fps.fps != null) "${fps.fps.toInt()}" else "N/A",
                        sub = fps.frameTimeMs?.let { "${"%.1f".format(Locale.US, it)} ms" } ?: fps.sourceNote
                    )
                }
            }
        }

        // 3. RAM, ZRAM & PSI Card
        item {
            val mem = snapshot.memory
            val usedMb = (mem.totalMb - mem.availableMb).coerceAtLeast(0L)
            val pct = if (mem.totalMb > 0) (usedMb * 100f) / mem.totalMb else 0f
            PrecisionCard(
                title = "RAM, ZRAM и Memory Pressure (PSI)",
                trailingBadge = {
                    Text(
                        text = "${usedMb} / ${mem.totalMb} MB (${pct.toInt()}%)",
                        style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                        color = thresholdColor(pct)
                    )
                }
            ) {
                SegmentedMemoryBar(
                    appAnonMb = mem.appAnonMb,
                    cachedMb = mem.cachedMb,
                    kernelSlabMb = mem.kernelSlabMb,
                    freeMb = mem.freeMb,
                    totalMb = mem.totalMb
                )
                Spacer(modifier = Modifier.height(8.dp))
                PrecisionLineChart(
                    series = ramChart,
                    unitSuffix = "%",
                    fixedMax = 100f,
                    height = 68.dp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(palette.innerSurface)
                        .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ZRAM / SWAP", style = TnumText.MicroLabel, color = palette.textMuted)
                        Text(
                            text = if (mem.zramTotalMb > 0) "${mem.zramUsedMb} / ${mem.zramTotalMb} MB" else "N/A",
                            style = TnumText.DataRow,
                            color = palette.textPrimary
                        )
                        Text(
                            text = if (mem.zramAvailability == SysfsAvailability.AVAILABLE && mem.zramComprMb > 0) {
                                "Orig ${mem.zramOrigMb}M → Compr ${mem.zramComprMb}M (${"%.2f".format(Locale.US, mem.zramRatio)}x)"
                            } else {
                                mem.zramAvailability.label
                            },
                            style = TnumText.MicroLabel,
                            color = palette.textSecondary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                        Text("PSI MEMORY", style = TnumText.MicroLabel, color = palette.textMuted)
                        Text(
                            text = mem.psiSomeAvg10?.let { "some ${"%.2f".format(Locale.US, it)}%" } ?: "N/A",
                            style = TnumText.DataRow,
                            color = palette.textPrimary
                        )
                        Text(
                            text = mem.psiFullAvg10?.let { "full ${"%.2f".format(Locale.US, it)}%" }
                                ?: mem.psiAvailability.label,
                            style = TnumText.MicroLabel,
                            color = palette.textSecondary
                        )
                    }
                }
            }
        }

        // 4. Power & Battery Card
        item {
            val bat = snapshot.battery
            PrecisionCard(
                title = "Питание и Батарея",
                trailingBadge = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${bat.percent}% · ${bat.statusLabel}",
                            style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                            color = if (bat.isCharging) palette.okGreen else palette.textPrimary
                        )
                        IconButton(
                            onClick = onOpenCalibration,
                            modifier = Modifier.size(32.dp).testTag("battery_calibration_shortcut")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = "Калибровка датчика тока",
                                tint = palette.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricBlock(
                        label = "ТОК (CALIBRATED)",
                        value = "${"%.0f".format(Locale.US, bat.currentMa)} мА",
                        sub = "${bat.voltageMv} мВ"
                    )
                    MetricBlock(
                        label = "МОЩНОСТЬ",
                        value = "${"%.2f".format(Locale.US, bat.powerWatts)} Вт",
                        sub = "${"%.1f".format(Locale.US, bat.ratePctPerHour)} %/ч"
                    )
                    MetricBlock(
                        label = "ОСТАТОК ЗАРЯДА",
                        value = if (bat.chargeCounterMah > 0) "${bat.chargeCounterMah} мАч" else "N/A",
                        sub = when {
                            bat.designCapacityMah > 0 -> "Проект: ${bat.designCapacityMah}"
                            bat.estimatedFullCapacityMah > 0 -> "100%: ~${bat.estimatedFullCapacityMah} мАч"
                            else -> "${bat.tempCelsius}°C"
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                PrecisionLineChart(
                    series = powerChart,
                    unitSuffix = "W",
                    height = 68.dp
                )
            }
        }

        // 5. Thermal Control Card
        item {
            val therm = snapshot.thermal
            val levelColor = when (therm.status) {
                ThermalLevel.NONE -> palette.okGreen
                ThermalLevel.LIGHT, ThermalLevel.MODERATE -> palette.warnAmber
                else -> palette.critRed
            }
            PrecisionCard(
                title = "Термоконтроль",
                trailingBadge = {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(levelColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "ТРОТТЛИНГ: ${therm.status.label}",
                            style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                            color = levelColor
                        )
                    }
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Thermal Headroom (10s): ${therm.headroom?.let { "%.2f".format(Locale.US, it) } ?: "N/A"}",
                        style = TnumText.DataRow,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Зон: ${therm.zones.size}",
                        style = TnumText.MicroLabel,
                        color = palette.textMuted
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                PrecisionLineChart(series = tempChart, unitSuffix = "°C", height = 64.dp)
                if (therm.zones.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    therm.zones.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { z ->
                                val zCol = thresholdColor((z.tempCelsius / 85f) * 100f)
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(palette.innerSurface)
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${z.friendlyCategory} (${z.rawType.take(10)})",
                                        style = TnumText.MicroLabel,
                                        color = palette.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${"%.1f".format(Locale.US, z.tempCelsius)}°C",
                                        style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                                        color = zCol
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }

        // 6. Block 3: Lag & Freeze Diagnostics Card
        item {
            LagDiagnosticsCard(
                tier = snapshot.tier,
                shizukuAvailable = snapshot.shizukuAvailable,
                lag = snapshot.lagDiagnostics,
                onConnectShizuku = onConnectShizuku,
                onRequestRoot = onRequestRoot
            )
        }

        // 7. Network & Sockets Card
        item {
            val net = snapshot.network
            PrecisionCard(
                title = "Сеть и Активные сокеты",
                trailingBadge = {
                    Text(
                        text = net.activeTransportLabel.uppercase(),
                        style = TnumText.MicroLabel,
                        color = palette.accent
                    )
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricBlock(
                        label = "WI-FI RX / TX",
                        value = "↓${formatRate(net.wifiRxBytesPerSec)} ↑${formatRate(net.wifiTxBytesPerSec)}",
                        sub = "Сессия: ${formatBytes(net.sessionWifiTotalBytes)}"
                    )
                    MetricBlock(
                        label = "CELLULAR RX / TX",
                        value = "↓${formatRate(net.cellRxBytesPerSec)} ↑${formatRate(net.cellTxBytesPerSec)}",
                        sub = "Сессия: ${formatBytes(net.sessionCellTotalBytes)}"
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(palette.innerSurface)
                        .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                        .clickable {
                            onLoadSockets()
                            showSocketsDialog = true
                        }
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                        .testTag("open_sockets_table_button"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (snapshot.tier == CapabilityTier.STANDARD) {
                            "Таблица сокетов (требуется Shizuku / Root)"
                        } else {
                            "Активных TCP/UDP сокетов: ${net.activeSocketCount ?: 0}"
                        },
                        style = TnumText.DataRow,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "ОТКРЫТЬ →",
                        style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                        color = palette.accent
                    )
                }
            }
        }

        // 8. Top Consumers Quick Jump
        if (snapshot.topCpuConsumers.isNotEmpty() || snapshot.topRamConsumers.isNotEmpty()) {
            item {
                val topList = snapshot.topCpuConsumers.ifEmpty { snapshot.topRamConsumers }
                PrecisionCard(title = "Топ потребителей ресурсов") {
                    topList.forEachIndexed { idx, proc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInspectProcess(proc) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = proc.displayName,
                                    style = TnumText.DataRow,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = proc.packageName,
                                        style = TnumText.MicroLabel,
                                        color = palette.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Text(
                                        text = if (proc.pid != null) " · PID ${proc.pid}" else " · PID N/A · UID ${proc.uid}",
                                        style = TnumText.MicroLabel,
                                        color = palette.textMuted,
                                        maxLines = 1
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${proc.cpuPercent?.let { "%.1f%%".format(Locale.US, it) } ?: "--"}  |  ${proc.ramPssMb?.let { "${it.toInt()} MB" } ?: "--"}",
                                style = TnumText.DataRow,
                                color = palette.accent
                            )
                        }
                        if (idx < topList.lastIndex) {
                            HorizontalDivider(color = palette.border)
                        }
                    }
                }
            }
        }
    }

    if (showSocketsDialog) {
        AlertDialog(
            onDismissRequest = { showSocketsDialog = false },
            containerColor = palette.card,
            title = { Text("АКТИВНЫЕ СОКЕТЫ (${sockets.size})", style = TnumText.SectionHeader, color = palette.textPrimary) },
            text = {
                if (snapshot.tier == CapabilityTier.STANDARD) {
                    HonestDegradationBanner(
                        reason = "Просмотр таблицы /proc/net/tcp и ss недоступен в Tier 0.",
                        shizukuAvailable = snapshot.shizukuAvailable,
                        onConnectShizuku = { showSocketsDialog = false; onConnectShizuku() },
                        onRequestRoot = { showSocketsDialog = false; onRequestRoot() }
                    )
                } else if (sockets.isEmpty()) {
                    Text("Нет открытых соединений.", style = TnumText.DataRow, color = palette.textSecondary)
                } else {
                    LazyColumn(modifier = Modifier.height(340.dp)) {
                        items(sockets) { s ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "${s.protocol} · ${s.state} · ${s.ownerName}",
                                    style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                                    color = palette.accent
                                )
                                Text(
                                    text = "${s.localAddress}:${s.localPort} → ${s.remoteAddress}:${s.remotePort}",
                                    style = TnumText.MicroLabel,
                                    color = palette.textPrimary
                                )
                                HorizontalDivider(color = palette.border, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSocketsDialog = false }) {
                    Text("ЗАКРЫТЬ", style = TnumText.MicroLabel, color = palette.accent)
                }
            }
        )
    }
}

@Composable
private fun LagDiagnosticsCard(
    tier: CapabilityTier,
    shizukuAvailable: Boolean,
    lag: LagDiagnosticsSnapshot?,
    onConnectShizuku: () -> Unit,
    onRequestRoot: () -> Unit
) {
    val palette = LocalProcPulsePalette.current
    var expanded by rememberSaveable { mutableStateOf(true) }
    val hasAlert = lag != null && (
        lag.clusterCaps.any { it.isCapped } ||
            lag.runQueueAlert ||
            lag.lmkKillsLast5Min.isNotEmpty() ||
            lag.diskIo.isIoBottleneck ||
            (lag.jank.jankyFramesPercent ?: 0f) >= 10f
        )

    PrecisionCard(
        title = "Причины лагов и фризов",
        trailingBadge = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                if (hasAlert) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = "Аномалия",
                        tint = palette.critRed,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = if (expanded) "Свернуть" else "Развернуть",
                    style = TnumText.MicroLabel,
                    color = palette.accent
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    ) {
        if (!expanded) {
            Text(
                text = if (tier == CapabilityTier.STANDARD) {
                    "Требуется Tier 1/2 для чтения vmstat, CPU Cap и Jank"
                } else {
                    "CPU Cap: ${lag?.clusterCaps?.count { it.isCapped } ?: 0} · LMK kills: ${lag?.lmkKillsLast5Min?.size ?: 0} · Janky: ${lag?.jank?.jankyFramesPercent?.let { "%.1f%%".format(Locale.US, it) } ?: "--"}"
                },
                style = TnumText.MicroLabel,
                color = palette.textSecondary
            )
            return@PrecisionCard
        }

        if (tier == CapabilityTier.STANDARD || lag == null) {
            HonestDegradationBanner(
                reason = "Скрытый CPU Cap, очередь планировщика (/proc/stat), Swap-трэшинг (/proc/vmstat), LMKD-события и Jank Profiler требуют Shizuku (Tier 1) или Root (Tier 2).",
                shizukuAvailable = shizukuAvailable,
                onConnectShizuku = onConnectShizuku,
                onRequestRoot = onRequestRoot
            )
            return@PrecisionCard
        }

        // 1. Hidden CPU Cap & ThermalService
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(palette.innerSurface)
                .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1. СКРЫТЫЙ CPU CAP & THERMAL", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.textSecondary)
                lag.thermalServiceStatus?.let {
                    Text("ThermalService: $it", style = TnumText.MicroLabel, color = palette.accent)
                }
            }
            Spacer(Modifier.height(4.dp))
            lag.clusterCaps.forEach { cl ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "${cl.role.label} (${cl.onlineCores}/${cl.totalCores} on) · ${cl.governor}",
                        style = TnumText.MicroLabel,
                        color = palette.textPrimary
                    )
                    Text(
                        text = if (cl.isCapped) {
                            "CAP ${cl.scalingMaxMhz}/${cl.cpuinfoMaxMhz}MHz"
                        } else {
                            "${cl.scalingMinMhz}-${cl.scalingMaxMhz}/${cl.cpuinfoMaxMhz}MHz"
                        },
                        style = TnumText.MicroLabel.copy(fontWeight = if (cl.isCapped) FontWeight.Bold else FontWeight.Normal),
                        color = if (cl.isCapped) palette.critRed else palette.okGreen
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 2. Run Queue & 1-min spikes
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(palette.innerSurface)
                .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("2. ОЧЕРЕДЬ ЯДРА (RUN QUEUE) & СПАЙКИ", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.textSecondary)
                Text(
                    text = "run:${lag.procsRunning ?: "--"} blk:${lag.procsBlocked ?: "--"}",
                    style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                    color = if (lag.runQueueAlert) palette.critRed else palette.okGreen
                )
            }
            if (lag.minuteCpuSpikes.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "Спайки (1м): " + lag.minuteCpuSpikes.take(3).joinToString { "${it.processName} ${"%.1f".format(Locale.US, it.cpuPercent)}%" },
                    style = TnumText.MicroLabel,
                    color = palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (lag.topWakeups.isNotEmpty()) {
                Text(
                    text = "Wakeups (Alarm/Job): " + lag.topWakeups.take(3).joinToString { "${it.packageName}(${it.count})" },
                    style = TnumText.MicroLabel,
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // 3. Swap Thrashing & LMKD
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(palette.innerSurface)
                .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            val vm = lag.vmStatRates
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("3. SWAP-ТРЭШИНГ & LMKD (5 МИН)", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.textSecondary)
                Text(
                    text = "LMK: ${lag.lmkKillsLast5Min.size}",
                    style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                    color = if (lag.lmkKillsLast5Min.isNotEmpty()) palette.critRed else palette.okGreen
                )
            }
            Spacer(Modifier.height(3.dp))
            if (vm.availability == SysfsAvailability.AVAILABLE) {
                Text(
                    text = "swapIn:${"%.1f".format(Locale.US, vm.pswpinPerSec)}/s  swapOut:${"%.1f".format(Locale.US, vm.pswpoutPerSec)}/s  " +
                        "majFlt:${"%.1f".format(Locale.US, vm.pgmajfaultPerSec)}/s  allocStall:${"%.1f".format(Locale.US, vm.allocstallPerSec)}/s",
                    style = TnumText.MicroLabel,
                    color = if (vm.allocstallPerSec > 1f) palette.warnAmber else palette.textPrimary
                )
            } else {
                Text(vm.availability.label, style = TnumText.MicroLabel, color = palette.textMuted)
            }
            if (lag.lmkKillsLast5Min.isNotEmpty()) {
                Text(
                    text = "Убиты: " + lag.lmkKillsLast5Min.takeLast(3).joinToString { "${it.packageName} @${it.timestampLabel}" },
                    style = TnumText.MicroLabel,
                    color = palette.critRed,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // 4. Disk I/O + 5. Frame / Jank Profiler
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.innerSurface)
                    .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                    .padding(8.dp)
            ) {
                val disk = lag.diskIo
                Text("4. ДИСК I/O", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.textSecondary)
                Text(
                    text = if (disk.availability == SysfsAvailability.AVAILABLE) {
                        "R:${disk.readKbPerSec.toInt()}K W:${disk.writeKbPerSec.toInt()}K\nBusy ${disk.ioBusyPercent.toInt()}%"
                    } else disk.availability.label,
                    style = TnumText.MicroLabel,
                    color = if (disk.isIoBottleneck) palette.critRed else palette.textPrimary
                )
                disk.dataFreePercent?.let {
                    Text("/data free: ${"%.0f".format(Locale.US, it)}%", style = TnumText.MicroLabel, color = palette.textMuted)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1.3f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.innerSurface)
                    .border(1.dp, palette.border, RoundedCornerShape(4.dp))
                    .padding(8.dp)
            ) {
                val j = lag.jank
                Text(
                    text = "5. FRAME / JANK (${j.refreshRateHz.toInt()}Hz)",
                    style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                    color = palette.textSecondary
                )
                Text(
                    text = "${j.foregroundPackage ?: "--"} · ${j.rendererType ?: "Renderer?"}",
                    style = TnumText.MicroLabel,
                    color = palette.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "FPS ${j.fps?.let { "%.0f".format(Locale.US, it) } ?: "--"} · Janky ${j.jankyFramesPercent?.let { "%.1f%%".format(Locale.US, it) } ?: "--"}",
                    style = TnumText.MicroLabel,
                    color = if ((j.jankyFramesPercent ?: 0f) >= 10f) palette.critRed else palette.textPrimary
                )
                Text(
                    text = j.dominantCause ?: j.sourceNote,
                    style = TnumText.MicroLabel,
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MetricBlock(label: String, value: String, sub: String) {
    val palette = LocalProcPulsePalette.current
    Column {
        Text(text = label, style = TnumText.MicroLabel.copy(fontSize = 10.sp), color = palette.textMuted)
        Text(text = value, style = TnumText.CardMetric, color = palette.textPrimary)
        Text(text = sub, style = TnumText.MicroLabel, color = palette.textSecondary)
    }
}

fun formatRate(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576L -> "${"%.1f".format(Locale.US, bytesPerSec / 1_048_576f)}MB/s"
        bytesPerSec >= 1024L -> "${bytesPerSec / 1024L}KB/s"
        else -> "${bytesPerSec}B/s"
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824L -> "${"%.2f".format(Locale.US, bytes / 1_073_741_824f)} GB"
        bytes >= 1_048_576L -> "${"%.1f".format(Locale.US, bytes / 1_048_576f)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }
}
