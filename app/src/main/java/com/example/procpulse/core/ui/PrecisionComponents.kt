package com.example.procpulse.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.procpulse.core.model.CapabilityTier
import com.example.procpulse.core.model.ChartSeriesSnapshot
import com.example.ui.theme.LocalProcPulsePalette
import com.example.ui.theme.MemoryCyan
import com.example.ui.theme.MemoryPurple
import com.example.ui.theme.TnumText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun thresholdColor(percent: Float): Color {
    val palette = LocalProcPulsePalette.current
    return when {
        percent > 85f -> palette.critRed
        percent >= 60f -> palette.warnAmber
        else -> palette.okGreen
    }
}

@Composable
fun PrecisionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailingBadge: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalProcPulsePalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = palette.card,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.uppercase(),
                    style = TnumText.SectionHeader,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (trailingBadge != null) {
                    Spacer(Modifier.width(8.dp))
                    trailingBadge()
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun PrecisionFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalProcPulsePalette.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = palette.innerSurface,
            labelColor = palette.textSecondary,
            selectedContainerColor = palette.accent.copy(alpha = 0.16f),
            selectedLabelColor = palette.accent,
            disabledContainerColor = palette.innerSurface.copy(alpha = 0.5f),
            disabledLabelColor = palette.textMuted
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = palette.border,
            selectedBorderColor = palette.accent.copy(alpha = 0.65f),
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        ),
        label = {
            Text(
                text = label,
                style = TnumText.MicroLabel.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrecisionSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalProcPulsePalette.current
    val inactiveTrack = if (palette.isDark) Color(0xFF27272A) else palette.border
    val activeTrack = if (enabled) palette.accent else palette.textMuted
    val thumbCol = if (enabled) palette.accent else palette.textMuted

    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled,
        modifier = modifier.height(32.dp),
        colors = SliderDefaults.colors(
            thumbColor = thumbCol,
            activeTrackColor = activeTrack,
            inactiveTrackColor = inactiveTrack,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        ),
        thumb = {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(thumbCol)
                    .border(1.5.dp, palette.background, CircleShape)
            )
        },
        track = { sliderState ->
            val span = (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(0.0001f)
            val fraction = ((sliderState.value - sliderState.valueRange.start) / span).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(inactiveTrack)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(activeTrack)
                )
            }
        }
    )
}

@Composable
fun PrecisionSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalProcPulsePalette.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = if (palette.isDark) Color(0xFF09090B) else Color.White,
            checkedTrackColor = palette.accent,
            checkedBorderColor = palette.accent,
            uncheckedThumbColor = palette.textSecondary,
            uncheckedTrackColor = if (palette.isDark) Color(0xFF27272A) else palette.innerSurface,
            uncheckedBorderColor = palette.border
        )
    )
}

@Composable
fun TierBadge(
    tier: CapabilityTier,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalProcPulsePalette.current
    val tint = when (tier) {
        CapabilityTier.ROOT -> palette.critRed
        CapabilityTier.SHIZUKU -> palette.accent
        CapabilityTier.STANDARD -> palette.textSecondary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(palette.innerSurface)
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("tier_badge"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = tier.shortLabel,
            style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
            color = tint
        )
    }
}

@Composable
fun HonestDegradationBanner(
    reason: String,
    shizukuAvailable: Boolean,
    onConnectShizuku: () -> Unit,
    onRequestRoot: () -> Unit,
    modifier: Modifier = Modifier,
    collapsible: Boolean = true
) {
    val palette = LocalProcPulsePalette.current
    var collapsed by rememberSaveable { mutableStateOf(false) }

    if (collapsible && collapsed) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(palette.innerSurface)
                .border(1.dp, palette.border, RoundedCornerShape(6.dp))
                .clickable { collapsed = false }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Outlined.LockOpen,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "TIER 0 (STANDARD) · Права ограничены",
                    style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                    color = palette.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Shizuku",
                    style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                    color = palette.textPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, palette.accent.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .clickable { onConnectShizuku() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = "Развернуть баннер",
                    tint = palette.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(palette.innerSurface)
            .border(1.dp, palette.border, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Outlined.LockOpen,
                    contentDescription = "Ограничение прав Tier 0",
                    tint = palette.accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ОГРАНИЧЕНИЕ TIER 0 (STANDARD)",
                    style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                    color = palette.accent
                )
            }
            if (collapsible) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { collapsed = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Скрыть", style = TnumText.MicroLabel, color = palette.textMuted)
                    Icon(
                        imageVector = Icons.Outlined.ExpandLess,
                        contentDescription = "Свернуть баннер",
                        tint = palette.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = reason,
            style = TnumText.MicroLabel,
            color = palette.textSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onConnectShizuku,
                modifier = Modifier
                    .height(34.dp)
                    .testTag("connect_shizuku_button"),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, palette.accent)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = palette.accent
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (shizukuAvailable) "Подключить Shizuku" else "Shizuku",
                    style = TnumText.MicroLabel,
                    color = palette.accent
                )
            }
            OutlinedButton(
                onClick = onRequestRoot,
                modifier = Modifier
                    .height(34.dp)
                    .testTag("request_root_button"),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, palette.border)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = palette.textPrimary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Запросить Root",
                    style = TnumText.MicroLabel,
                    color = palette.textPrimary
                )
            }
        }
    }
}

/**
 * Native Compose Canvas telemetry chart with 1dp grid, 1.5dp polyline, <=8% area fill,
 * and touch scrubbing that freezes the snapshot under the finger.
 */
@Composable
fun PrecisionLineChart(
    series: ChartSeriesSnapshot,
    unitSuffix: String,
    fixedMax: Float? = null,
    lineColor: Color? = null,
    height: Dp = 96.dp,
    modifier: Modifier = Modifier,
    onScrubIndexChanged: ((Int?) -> Unit)? = null
) {
    val palette = LocalProcPulsePalette.current
    val strokeColor = lineColor ?: palette.accent
    val gridColor = palette.border

    var isScrubbing by remember { mutableStateOf(false) }
    var frozenSeries by remember { mutableStateOf(series) }
    var scrubIndex by remember { mutableIntStateOf(-1) }

    val displaySeries = if (isScrubbing && frozenSeries.values.isNotEmpty()) frozenSeries else series
    val values = displaySeries.values
    val maxVal = fixedMax ?: displaySeries.max.coerceAtLeast(1f)
    val minVal = if (fixedMax != null) 0f else displaySeries.min.coerceAtMost(maxVal)
    val range = (maxVal - minVal).coerceAtLeast(1f)

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val activeVal = if (isScrubbing && scrubIndex in values.indices) {
                values[scrubIndex]
            } else {
                displaySeries.current
            }
            val activeTime = if (isScrubbing && scrubIndex in displaySeries.timestamps.indices) {
                " @ ${timeFmt.format(Date(displaySeries.timestamps[scrubIndex]))}"
            } else ""

            Text(
                text = "CUR ${"%.1f".format(Locale.US, activeVal)}$unitSuffix$activeTime",
                style = TnumText.MicroLabel.copy(fontWeight = FontWeight.Bold),
                color = if (isScrubbing) palette.accent else palette.textPrimary
            )
            Text(
                text = "MIN ${"%.1f".format(Locale.US, displaySeries.min)} / MAX ${"%.1f".format(Locale.US, displaySeries.max)}$unitSuffix",
                style = TnumText.MicroLabel,
                color = palette.textMuted
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(4.dp))
                .background(palette.innerSurface)
                .border(1.dp, gridColor, RoundedCornerShape(4.dp))
                .pointerInput(values.size) {
                    detectTapGestures(
                        onPress = { offset ->
                            if (series.values.isNotEmpty()) {
                                frozenSeries = series
                                isScrubbing = true
                                val idx = ((offset.x / size.width.coerceAtLeast(1)) * (frozenSeries.values.size - 1))
                                    .roundToInt()
                                    .coerceIn(0, frozenSeries.values.lastIndex)
                                scrubIndex = idx
                                onScrubIndexChanged?.invoke(idx)
                                tryAwaitRelease()
                                isScrubbing = false
                                scrubIndex = -1
                                onScrubIndexChanged?.invoke(null)
                            }
                        }
                    )
                }
                .pointerInput(values.size) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            if (series.values.isNotEmpty()) {
                                frozenSeries = series
                                isScrubbing = true
                                val idx = ((offset.x / size.width.coerceAtLeast(1)) * (frozenSeries.values.size - 1))
                                    .roundToInt()
                                    .coerceIn(0, frozenSeries.values.lastIndex)
                                scrubIndex = idx
                                onScrubIndexChanged?.invoke(idx)
                            }
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            if (frozenSeries.values.isNotEmpty()) {
                                val idx = ((change.position.x / size.width.coerceAtLeast(1)) * (frozenSeries.values.size - 1))
                                    .roundToInt()
                                    .coerceIn(0, frozenSeries.values.lastIndex)
                                scrubIndex = idx
                                onScrubIndexChanged?.invoke(idx)
                            }
                        },
                        onDragEnd = {
                            isScrubbing = false
                            scrubIndex = -1
                            onScrubIndexChanged?.invoke(null)
                        },
                        onDragCancel = {
                            isScrubbing = false
                            scrubIndex = -1
                            onScrubIndexChanged?.invoke(null)
                        }
                    )
                }
        ) {
            val linePath = remember { Path() }
            val fillPath = remember { Path() }

            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val gridStroke = 1.dp.toPx()

                for (i in 1..3) {
                    val y = h * (i / 4f)
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = gridStroke)
                }
                for (i in 1..4) {
                    val x = w * (i / 5f)
                    drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = gridStroke)
                }

                if (values.size >= 2) {
                    linePath.reset()
                    fillPath.reset()
                    val stepX = w / (values.size - 1).toFloat()
                    for (i in values.indices) {
                        val x = i * stepX
                        val norm = ((values[i] - minVal) / range).coerceIn(0f, 1f)
                        val y = h - norm * (h - 4.dp.toPx()) - 2.dp.toPx()
                        if (i == 0) {
                            linePath.moveTo(x, y)
                            fillPath.moveTo(x, h)
                            fillPath.lineTo(x, y)
                        } else {
                            linePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }
                    fillPath.lineTo((values.size - 1) * stepX, h)
                    fillPath.close()

                    drawPath(fillPath, color = strokeColor.copy(alpha = 0.08f))
                    drawPath(
                        linePath,
                        color = strokeColor,
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Miter)
                    )

                    if (isScrubbing && scrubIndex in values.indices) {
                        val sx = scrubIndex * stepX
                        val snorm = ((values[scrubIndex] - minVal) / range).coerceIn(0f, 1f)
                        val sy = h - snorm * (h - 4.dp.toPx()) - 2.dp.toPx()
                        drawLine(palette.textPrimary, Offset(sx, 0f), Offset(sx, h), strokeWidth = 1.dp.toPx())
                        drawCircle(palette.accent, radius = 3.5.dp.toPx(), center = Offset(sx, sy))
                    }
                }
            }
        }
    }
}

@Composable
fun SegmentedMemoryBar(
    appAnonMb: Long,
    cachedMb: Long,
    kernelSlabMb: Long,
    freeMb: Long,
    totalMb: Long,
    modifier: Modifier = Modifier
) {
    val palette = LocalProcPulsePalette.current
    val safeTotal = totalMb.coerceAtLeast(1L).toFloat()
    val wAnon = (appAnonMb / safeTotal).coerceIn(0.01f, 1f)
    val wCached = (cachedMb / safeTotal).coerceIn(0f, 1f)
    val wOther = (kernelSlabMb / safeTotal).coerceIn(0f, 1f)
    val wFree = (freeMb / safeTotal).coerceIn(0.01f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(1.dp, palette.border, RoundedCornerShape(3.dp))
        ) {
            Box(Modifier.weight(wAnon).height(10.dp).background(palette.accent))
            if (wCached > 0f) Box(Modifier.weight(wCached).height(10.dp).background(MemoryCyan))
            if (wOther > 0f) Box(Modifier.weight(wOther).height(10.dp).background(MemoryPurple))
            Box(Modifier.weight(wFree).height(10.dp).background(palette.innerSurface))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LegendDot("App $appAnonMb", palette.accent)
            LegendDot("Cache $cachedMb", MemoryCyan)
            LegendDot("Other $kernelSlabMb", MemoryPurple)
            LegendDot("Free $freeMb", palette.textMuted)
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    val palette = LocalProcPulsePalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = TnumText.MicroLabel.copy(fontSize = 10.sp), color = palette.textSecondary)
    }
}
