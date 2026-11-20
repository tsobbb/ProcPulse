package com.example.procpulse.core.database

import com.example.procpulse.core.model.AppJankSummary
import com.example.procpulse.core.model.LagCategoryVerdict
import com.example.procpulse.core.model.LagRootCauseReport
import com.example.procpulse.core.model.LagSeverity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LagReportAnalyzer {

    fun analyze(
        session: ProfilerSessionEntity,
        samples: List<ProfilerSampleEntity>
    ): LagRootCauseReport {
        if (samples.isEmpty()) {
            return LagRootCauseReport(
                sessionId = session.id,
                sessionTitle = session.title,
                durationSec = 0,
                sampleCount = 0,
                overallVerdict = "Недостаточно срезов для анализа.",
                categories = emptyList(),
                appJankSummaries = emptyList(),
                markdownReport = "# ProcPulse: ${session.title}\nНет срезов для анализа."
            )
        }

        val durationSec = (((session.endTimeMs ?: samples.last().timestampMs) - session.startTimeMs) / 1000L)
            .toInt()
            .coerceAtLeast(1)

        // 1. CPU Cap / Thermal Throttling
        val cappedCount = samples.count { it.isCpuCapped }
        val cappedRatio = cappedCount.toFloat() / samples.size.toFloat()
        val peakTemp = samples.maxOf { it.tempCelsius }
        val peakBattTemp = samples.maxOf { it.batteryTempCelsius }
        val lastCapSummary = samples.lastOrNull { it.scalingMaxSummary.isNotBlank() }?.scalingMaxSummary ?: ""
        val cpuCapSeverity = when {
            cappedRatio >= 0.35f || peakTemp >= 78f || peakBattTemp >= 45f -> LagSeverity.CRITICAL
            cappedCount > 0 || peakTemp >= 68f || peakBattTemp >= 42f -> LagSeverity.WARNING
            else -> LagSeverity.OK
        }
        val cpuCapSummary = when (cpuCapSeverity) {
            LagSeverity.CRITICAL -> "Зафиксирован скрытый CPU Cap ($cappedCount/${samples.size} ср.) или перегрев (${"%.1f".format(Locale.US, peakTemp)}°C)."
            LagSeverity.WARNING -> "Кратковременное урезание частот или нагрев до ${"%.1f".format(Locale.US, peakTemp)}°C."
            LagSeverity.OK -> "Частотные лимиты в норме (пик ${"%.1f".format(Locale.US, peakTemp)}°C)."
        }
        val cpuCapDetails = buildList {
            add("Срезов с scaling_max < cpuinfo_max: $cappedCount из ${samples.size} (${(cappedRatio * 100).toInt()}%)")
            add("Пик SoC / Батареи: ${"%.1f".format(Locale.US, peakTemp)}°C / ${"%.1f".format(Locale.US, peakBattTemp)}°C")
            if (lastCapSummary.isNotBlank()) add("Лимиты кластеров: $lastCapSummary")
        }
        val catCpuCap = LagCategoryVerdict("Скрытый CPU Cap и Троттлинг", cpuCapSeverity, cpuCapSummary, cpuCapDetails)

        // 2. Run Queue & CPU Saturation
        val peakCpu = samples.maxOf { it.cpuTotalPercent }
        val avgCpu = samples.map { it.cpuTotalPercent }.average().toFloat()
        val peakRunning = samples.maxOf { it.procsRunning }
        val peakBlocked = samples.maxOf { it.procsBlocked }
        val cpuHogCounts = HashMap<String, MutableList<Float>>()
        for (s in samples) {
            for (p in ProfilerRepository.decodeTopProcesses(s.topProcessesJson)) {
                if (p.cpuPercent >= 12f) {
                    cpuHogCounts.getOrPut(p.packageName.ifBlank { p.displayName }) { mutableListOf() }.add(p.cpuPercent)
                }
            }
        }
        val topHogs = cpuHogCounts.entries
            .sortedByDescending { it.value.size * (it.value.average()) }
            .take(3)
            .map { (pkg, vals) -> "$pkg (средн. ${"%.1f".format(Locale.US, vals.average())}%, пиков: ${vals.size})" }

        val runQueueSeverity = when {
            peakBlocked >= 3 || peakRunning >= 18 || avgCpu >= 75f -> LagSeverity.CRITICAL
            peakBlocked >= 1 || peakRunning >= 12 || peakCpu >= 85f -> LagSeverity.WARNING
            else -> LagSeverity.OK
        }
        val runQueueSummary = when (runQueueSeverity) {
            LagSeverity.CRITICAL -> "Перегрузка очереди планировщика: running=$peakRunning, blocked=$peakBlocked, avg CPU=${"%.0f".format(Locale.US, avgCpu)}%."
            LagSeverity.WARNING -> "Периодические пики CPU (пик ${"%.0f".format(Locale.US, peakCpu)}%, running=$peakRunning)."
            LagSeverity.OK -> "Очередь задач без заторов (avg CPU ${"%.1f".format(Locale.US, avgCpu)}%)."
        }
        val runQueueDetails = buildList {
            add("CPU avg / peak: ${"%.1f".format(Locale.US, avgCpu)}% / ${"%.1f".format(Locale.US, peakCpu)}%")
            add("Очередь ядра (пик): runnable=$peakRunning, blocked=$peakBlocked")
            if (topHogs.isNotEmpty()) add("Главные потребители CPU: ${topHogs.joinToString("; ")}")
        }
        val catRunQueue = LagCategoryVerdict("Очередь задач и CPU-нагрузка", runQueueSeverity, runQueueSummary, runQueueDetails)

        // 3. Memory Pressure, Swap Thrashing & LMKD
        val minFreeRam = samples.minOf { it.freeRamMb }
        val peakSwapRate = samples.maxOf { it.pswpinPerSec + it.pswpoutPerSec }
        val peakMajFault = samples.maxOf { it.pgmajfaultPerSec }
        val peakAllocStall = samples.maxOf { it.allocstallPerSec }
        val allKills = LinkedHashMap<String, Int>()
        for (s in samples) {
            for (ev in ProfilerRepository.decodeLmkEvents(s.amKillEventsJson)) {
                allKills[ev.packageName] = (allKills[ev.packageName] ?: 0) + 1
            }
        }
        val memSeverity = when {
            peakAllocStall >= 5f || peakSwapRate >= 250f || allKills.values.sum() >= 3 -> LagSeverity.CRITICAL
            peakAllocStall > 0.5f || peakMajFault >= 80f || minFreeRam < 350L || allKills.isNotEmpty() -> LagSeverity.WARNING
            else -> LagSeverity.OK
        }
        val memSummary = when (memSeverity) {
            LagSeverity.CRITICAL -> "Жёсткий дефицит памяти / Swap-трэшинг (allocstall=${"%.1f".format(Locale.US, peakAllocStall)}/с, LMK kills=${allKills.values.sum()})."
            LagSeverity.WARNING -> "Повышенный Swap I/O или выгрузки LMKD (min avail=${minFreeRam} МБ)."
            LagSeverity.OK -> "Давление памяти и ZRAM в норме (min avail=${minFreeRam} МБ)."
        }
        val memDetails = buildList {
            add("Мин. свободная RAM: $minFreeRam МБ | Пик ZRAM swap: ${samples.maxOf { it.zramUsedMb }} МБ")
            add("Пик Swap (in+out): ${"%.1f".format(Locale.US, peakSwapRate)} стр/с | Major Faults: ${"%.1f".format(Locale.US, peakMajFault)}/с | AllocStall: ${"%.1f".format(Locale.US, peakAllocStall)}/с")
            if (allKills.isNotEmpty()) {
                add("Убито LMKD/am_kill: ${allKills.entries.joinToString { "${it.key} (x${it.value})" }}")
            }
        }
        val catMem = LagCategoryVerdict("Память, ZRAM-трэшинг и LMKD", memSeverity, memSummary, memDetails)

        // 4. Disk I/O Bottlenecks
        val peakIoWait = samples.maxOf { it.ioWaitPercent }
        val peakIoBusy = samples.maxOf { it.ioBusyPercent }
        val ioSeverity = when {
            peakIoWait >= 22f || peakIoBusy >= 75f -> LagSeverity.CRITICAL
            peakIoWait >= 10f || peakIoBusy >= 45f -> LagSeverity.WARNING
            else -> LagSeverity.OK
        }
        val ioSummary = when (ioSeverity) {
            LagSeverity.CRITICAL -> "Заторы флеш-памяти UFS/eMMC (iowait=${"%.1f".format(Locale.US, peakIoWait)}%, busy=${"%.0f".format(Locale.US, peakIoBusy)}%)."
            LagSeverity.WARNING -> "Умеренная нагрузка на диск (iowait=${"%.1f".format(Locale.US, peakIoWait)}%)."
            LagSeverity.OK -> "Дисковая подсистема работает без блокирующих очередей."
        }
        val catIo = LagCategoryVerdict(
            "Дисковый I/O (UFS / eMMC)",
            ioSeverity,
            ioSummary,
            listOf("Пиковый iowait: ${"%.1f".format(Locale.US, peakIoWait)}% | Занятость блочного устройства: ${"%.1f".format(Locale.US, peakIoBusy)}%")
        )

        // 5. Frame Jank Profiler (UI vs GPU)
        val jankSamples = samples.filter { it.jankPercent != null || it.fps != null }
        val groupedByApp = jankSamples.groupBy { it.foregroundPackage ?: "system" }
        val appJanks = groupedByApp.entries.mapNotNull { (pkg, list) ->
            val janks = list.mapNotNull { it.jankPercent }
            val fpsVals = list.mapNotNull { it.fps }
            if (janks.isEmpty() && fpsVals.isEmpty()) return@mapNotNull null
            val avgJank = if (janks.isNotEmpty()) janks.average().toFloat() else 0f
            val peakJank = janks.maxOrNull() ?: 0f
            val avgFps = if (fpsVals.isNotEmpty()) fpsVals.average().toFloat() else null
            val dominant = list.mapNotNull { it.jankStage }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }?.key ?: "Нет выраженного затора"
            val renderer = list.lastNotNullOrNull { it.rendererType }
            AppJankSummary(
                packageName = pkg,
                avgFps = avgFps,
                avgJankyPercent = avgJank,
                peakJankyPercent = peakJank,
                dominantCause = dominant,
                rendererType = renderer,
                sampleCount = list.size
            )
        }.sortedByDescending { it.peakJankyPercent }

        val worstJank = appJanks.maxOfOrNull { it.peakJankyPercent } ?: 0f
        val jankSeverity = when {
            worstJank >= 20f -> LagSeverity.CRITICAL
            worstJank >= 8f -> LagSeverity.WARNING
            else -> LagSeverity.OK
        }
        val jankSummary = when {
            appJanks.isEmpty() -> "Профилирование кадров недоступно (использовался Tier 0)."
            jankSeverity == LagSeverity.CRITICAL -> "Выраженные фризы UI/GPU до ${"%.1f".format(Locale.US, worstJank)}% Janky frames (${appJanks.first().packageName})."
            jankSeverity == LagSeverity.WARNING -> "Отмечены просадки кадров до ${"%.1f".format(Locale.US, worstJank)}% в ${appJanks.first().packageName}."
            else -> "Рендеринг стабильный (макс. Janky ${"%.1f".format(Locale.US, worstJank)}%)."
        }
        val jankDetails = appJanks.take(4).map { app ->
            "${app.packageName}: FPS=${app.avgFps?.let { "%.1f".format(Locale.US, it) } ?: "--"}, " +
                "Janky avg=${"%.1f".format(Locale.US, app.avgJankyPercent)}% (пик ${"%.1f".format(Locale.US, app.peakJankyPercent)}%), " +
                "причина: ${app.dominantCause}${app.rendererType?.let { " [$it]" } ?: ""}"
        }
        val catJank = LagCategoryVerdict("Фризы рендеринга и Jank", jankSeverity, jankSummary, jankDetails)

        val categories = listOf(catCpuCap, catRunQueue, catMem, catIo, catJank)
        val criticals = categories.filter { it.severity == LagSeverity.CRITICAL }
        val warnings = categories.filter { it.severity == LagSeverity.WARNING }

        val overall = when {
            criticals.isNotEmpty() -> "Основная причина лагов: ${criticals.joinToString("; ") { it.categoryName }}"
            warnings.isNotEmpty() -> "Обнаружены умеренные аномалии: ${warnings.joinToString("; ") { it.categoryName }}"
            else -> "Критических причин лагов за время теста не выявлено — система работала стабильно."
        }

        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val md = buildString {
            appendLine("# Отчёт диагностики лагов ProcPulse")
            appendLine("- **Сессия:** ${session.title}")
            appendLine("- **Начало:** ${fmt.format(Date(session.startTimeMs))} | **Длительность:** ${durationSec}с | **Срезов:** ${samples.size} (${session.tierUsed})")
            appendLine("- **Итог:** $overall")
            appendLine()
            for (c in categories) {
                appendLine("## [${c.severity.label}] ${c.categoryName}")
                appendLine(c.summary)
                c.details.forEach { d -> appendLine("- $d") }
                appendLine()
            }
            if (appJanks.isNotEmpty()) {
                appendLine("## Разбивка по приложениям (Jank & FPS)")
                appJanks.forEach { a ->
                    appendLine("- `${a.packageName}`: FPS=${a.avgFps?.let { "%.1f".format(Locale.US, it) } ?: "--"}, Janky=${"%.1f".format(Locale.US, a.avgJankyPercent)}% (peak ${"%.1f".format(Locale.US, a.peakJankyPercent)}%), Причина: ${a.dominantCause}")
                }
            }
        }

        return LagRootCauseReport(
            sessionId = session.id,
            sessionTitle = session.title,
            durationSec = durationSec,
            sampleCount = samples.size,
            overallVerdict = overall,
            categories = categories,
            appJankSummaries = appJanks,
            markdownReport = md
        )
    }

    private inline fun <T, R : Any> List<T>.lastNotNullOrNull(transform: (T) -> R?): R? {
        for (i in indices.reversed()) {
            val v = transform(this[i])
            if (v != null) return v
        }
        return null
    }
}
