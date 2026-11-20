package com.example.procpulse.feature.processes

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.procpulse.core.model.CapabilityTier
import com.example.procpulse.core.model.ProcessCategory
import com.example.procpulse.core.model.ProcessEntry
import com.example.procpulse.core.model.ProcessSortBy
import com.example.procpulse.core.model.ProcessStateTag
import com.example.procpulse.core.ui.HonestDegradationBanner
import com.example.procpulse.core.ui.thresholdColor
import com.example.ui.theme.LocalProcPulsePalette
import com.example.ui.theme.TnumText
import java.util.Locale

@Composable
fun ProcessesScreen(
    processes: List<ProcessEntry>,
    activeTier: CapabilityTier,
    shizukuAvailable: Boolean,
    hasUsageStatsPermission: Boolean,
    selectedCategory: ProcessCategory,
    searchQuery: String,
    filterCpuNonZero: Boolean,
    filterActiveNet: Boolean,
    filterInMemory: Boolean,
    sortBy: ProcessSortBy,
    selectedPids: Set<Int>,
    inspectedPid: Int?,
    getAppIcon: (String) -> ImageBitmap?,
    onSelectCategory: (ProcessCategory) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleFilterCpu: () -> Unit,
    onToggleFilterNet: () -> Unit,
    onToggleFilterMem: () -> Unit,
    onSelectSort: (ProcessSortBy) -> Unit,
    onToggleSelectPid: (Int) -> Unit,
    onClearSelection: () -> Unit,
    onBatchForceStop: () -> Unit,
    onBatchFreeze: () -> Unit,
    onBatchWhitelist: () -> Unit,
    onInspectProcess: (ProcessEntry) -> Unit,
    onConnectShizuku: () -> Unit,
    onRequestRoot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalProcPulsePalette.current
    val context = LocalContext.current
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // Category Tabs
        val categories = ProcessCategory.entries
        ScrollableTabRow(
            selectedTabIndex = categories.indexOf(selectedCategory),
            containerColor = palette.card,
            contentColor = palette.accent,
            edgePadding = 8.dp
        ) {
            categories.forEach { cat ->
                Tab(
                    selected = cat == selectedCategory,
                    onClick = { onSelectCategory(cat) },
                    modifier = Modifier.testTag("proc_tab_${cat.name.lowercase()}"),
                    text = {
                        Text(
                            text = cat.label,
                            style = TnumText.MicroLabel.copy(
                                fontWeight = if (cat == selectedCategory) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (cat == selectedCategory) palette.accent else palette.textSecondary
                        )
                    }
                )
            }
        }

        // Search + Sort
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Поиск: имя, пакет, PID, UID", style = TnumText.MicroLabel) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Очистить поиск", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                textStyle = TnumText.DataRow,
                modifier = Modifier
                    .weight(1f)
                    .testTag("process_search_input")
            )

            Box {
                OutlinedButton(
                    onClick = { sortMenuExpanded = true },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("process_sort_button"),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(Icons.Outlined.Sort, contentDescription = "Сортировка", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(sortBy.label, style = TnumText.MicroLabel)
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    ProcessSortBy.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label, style = TnumText.DataRow) },
                            onClick = {
                                onSelectSort(s)
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = filterCpuNonZero,
                onClick = onToggleFilterCpu,
                label = { Text("CPU > 0%", style = TnumText.MicroLabel) }
            )
            FilterChip(
                selected = filterActiveNet,
                onClick = onToggleFilterNet,
                label = { Text("С сетью", style = TnumText.MicroLabel) }
            )
            FilterChip(
                selected = filterInMemory,
                onClick = onToggleFilterMem,
                label = { Text("PSS > 0 MB", style = TnumText.MicroLabel) }
            )
            Text(
                text = "Найдено: ${processes.size}",
                style = TnumText.MicroLabel,
                color = palette.textMuted,
                modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp)
            )
        }

        // Multi-select batch action bar
        if (selectedPids.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.innerSurface)
                    .border(1.dp, palette.accent.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Выбрано: ${selectedPids.size}",
                    style = TnumText.DataRow.copy(fontWeight = FontWeight.Bold),
                    color = palette.accent
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onBatchWhitelist,
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Whitelist", style = TnumText.MicroLabel)
                    }
                    OutlinedButton(
                        onClick = onBatchFreeze,
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(4.dp),
                        enabled = activeTier != CapabilityTier.STANDARD
                    ) {
                        Text("Freeze", style = TnumText.MicroLabel)
                    }
                    Button(
                        onClick = onBatchForceStop,
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(4.dp),
                        enabled = activeTier != CapabilityTier.STANDARD,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.critRed)
                    ) {
                        Text("Stop", style = TnumText.MicroLabel)
                    }
                    IconButton(onClick = onClearSelection, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Снять выделение", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Tier 0 Usage Access or Native Daemon limitation hint
        if (activeTier == CapabilityTier.STANDARD && !hasUsageStatsPermission) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.innerSurface)
                        .border(1.dp, palette.accent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Доступ к статистике использования", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.accent)
                        Text("Выдайте PACKAGE_USAGE_STATS для показа недавних приложений в Tier 0.", style = TnumText.MicroLabel, color = palette.textSecondary)
                    }
                    Text("ВЫДАТЬ →", style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold), color = palette.accent)
                }
            }
        } else if (activeTier == CapabilityTier.STANDARD && selectedCategory == ProcessCategory.NATIVE) {
            Box(modifier = Modifier.padding(12.dp)) {
                HonestDegradationBanner(
                    reason = "Нативные демоны и дерево PID/PPID скрыты SELinux в Tier 0.",
                    shizukuAvailable = shizukuAvailable,
                    onConnectShizuku = onConnectShizuku,
                    onRequestRoot = onRequestRoot
                )
            }
        }

        // Process LazyColumn
        if (processes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Нет процессов, соответствующих фильтрам",
                    style = TnumText.DataRow,
                    color = palette.textMuted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(processes, key = { "${it.pid}_${it.packageName}" }) { proc ->
                    ProcessRowItem(
                        entry = proc,
                        isSelected = proc.pid in selectedPids,
                        isInspected = proc.pid == inspectedPid,
                        iconBitmap = remember(proc.packageName) { getAppIcon(proc.packageName) },
                        onClick = {
                            if (selectedPids.isNotEmpty()) {
                                onToggleSelectPid(proc.pid)
                            } else {
                                onInspectProcess(proc)
                            }
                        },
                        onLongClick = { onToggleSelectPid(proc.pid) }
                    )
                    HorizontalDivider(color = palette.border)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProcessRowItem(
    entry: ProcessEntry,
    isSelected: Boolean,
    isInspected: Boolean,
    iconBitmap: ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val palette = LocalProcPulsePalette.current
    val bg = when {
        isSelected -> palette.accent.copy(alpha = 0.14f)
        isInspected -> palette.innerSurface
        else -> palette.background
    }

    val tagColor = when (entry.stateTag) {
        ProcessStateTag.FG -> palette.okGreen
        ProcessStateTag.SVC -> palette.accent
        ProcessStateTag.STP, ProcessStateTag.FRZ -> palette.critRed
        ProcessStateTag.BGD, ProcessStateTag.CCH -> palette.textMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("process_row_${entry.pid}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = entry.displayName,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.innerSurface)
                    .border(1.dp, palette.border, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Android,
                    contentDescription = null,
                    tint = palette.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.displayName,
                    style = TnumText.DataRow.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.isProtected) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = "Защищённый процесс",
                        tint = palette.okGreen,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = entry.stateTag.tag,
                    style = TnumText.MicroLabel.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = tagColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(tagColor.copy(alpha = 0.14f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            Text(
                text = "${entry.packageName} · PID ${entry.pid}",
                style = TnumText.MicroLabel,
                color = palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(86.dp)) {
            val cpuVal = entry.cpuPercent
            Text(
                text = cpuVal?.let { "${"%.1f".format(Locale.US, it)}%" } ?: "CPU --",
                style = TnumText.DataRow.copy(fontWeight = FontWeight.Bold),
                color = cpuVal?.let { thresholdColor(it) } ?: palette.textMuted
            )
            if (cpuVal != null) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(3.dp)
                        .background(palette.border)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((cpuVal / 100f).coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(thresholdColor(cpuVal))
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = entry.ramPssMb?.let { "${it.toInt()} MB" } ?: "-- MB",
                style = TnumText.MicroLabel,
                color = palette.textSecondary
            )
        }
    }
}
