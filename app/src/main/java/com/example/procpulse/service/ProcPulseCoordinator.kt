package com.example.procpulse.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.procpulse.core.bridge.UnifiedTelemetryBridge
import com.example.procpulse.core.database.ProfilerRepository
import com.example.procpulse.core.database.ProfilerSampleEntity
import com.example.procpulse.core.database.SettingsRepository
import com.example.procpulse.core.model.AppSettings
import com.example.procpulse.core.model.CapabilityTier
import com.example.procpulse.core.model.ChartSeriesSnapshot
import com.example.procpulse.core.model.FloatRingBuffer
import com.example.procpulse.core.model.ProfilerTopProcess
import com.example.procpulse.core.model.SystemSnapshot
import com.example.procpulse.core.model.toSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ActiveProfilerState(
    val isRecording: Boolean = false,
    val sessionId: Long? = null,
    val title: String = "",
    val startTimeMs: Long = 0L,
    val targetDurationSec: Int = 0, // 0 = manual unlimited
    val intervalMs: Long = 1000L,
    val samplesRecorded: Int = 0
)

class ProcPulseCoordinator(
    private val context: Context,
    val bridge: UnifiedTelemetryBridge,
    val settingsRepo: SettingsRepository,
    val profilerRepo: ProfilerRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val settingsFlow: StateFlow<AppSettings> = settingsRepo.settingsFlow.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    private val _snapshot = MutableStateFlow<SystemSnapshot?>(null)
    val snapshot: StateFlow<SystemSnapshot?> = _snapshot.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _profilerState = MutableStateFlow(ActiveProfilerState())
    val profilerState: StateFlow<ActiveProfilerState> = _profilerState.asStateFlow()

    // Primitive ring buffers (60 points)
    private val cpuRing = FloatRingBuffer(60)
    private val ramRing = FloatRingBuffer(60)
    private val powerRing = FloatRingBuffer(60)
    private val tempRing = FloatRingBuffer(60)
    private val fpsRing = FloatRingBuffer(60)

    private val _cpuChart = MutableStateFlow(ChartSeriesSnapshot.Empty)
    val cpuChart: StateFlow<ChartSeriesSnapshot> = _cpuChart.asStateFlow()

    private val _ramChart = MutableStateFlow(ChartSeriesSnapshot.Empty)
    val ramChart: StateFlow<ChartSeriesSnapshot> = _ramChart.asStateFlow()

    private val _powerChart = MutableStateFlow(ChartSeriesSnapshot.Empty)
    val powerChart: StateFlow<ChartSeriesSnapshot> = _powerChart.asStateFlow()

    private val _tempChart = MutableStateFlow(ChartSeriesSnapshot.Empty)
    val tempChart: StateFlow<ChartSeriesSnapshot> = _tempChart.asStateFlow()

    private val _fpsChart = MutableStateFlow(ChartSeriesSnapshot.Empty)
    val fpsChart: StateFlow<ChartSeriesSnapshot> = _fpsChart.asStateFlow()

    private val uiSubscribers = AtomicInteger(0)
    private var collectorJob: Job? = null

    // Profiler accumulation
    private var recPeakCpu = 0f
    private var recPeakTemp = 0f
    private var recMinFreeRam = Long.MAX_VALUE
    private var recSumCurrent = 0f
    private var recLastWriteMs = 0L

    // Watchdog tracking
    private val highCpuSinceMs = ConcurrentHashMap<String, Long>()
    private var screenOffSinceMs: Long = 0L
    private var lastAlertTempMs: Long = 0L
    private var lastAlertRamMs: Long = 0L

    init {
        ensureNotificationChannels()
    }

    fun onUiStarted() {
        uiSubscribers.incrementAndGet()
        evaluateCollectorState()
    }

    fun onUiStopped() {
        val left = uiSubscribers.decrementAndGet().coerceAtLeast(0)
        uiSubscribers.set(left)
        evaluateCollectorState()
    }

    fun togglePause() {
        _isPaused.value = !_isPaused.value
    }

    fun evaluateCollectorState() {
        val settings = settingsFlow.value
        val shouldRun = uiSubscribers.get() > 0 ||
            settings.overlayConfig.enabled ||
            _profilerState.value.isRecording ||
            settings.watchdogConfig.enabled

        if (shouldRun && collectorJob?.isActive != true) {
            startCollectorLoop()
        } else if (!shouldRun && collectorJob?.isActive == true) {
            collectorJob?.cancel()
            collectorJob = null
        }
    }

    private fun startCollectorLoop() {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            while (isActive) {
                val settings = settingsFlow.value
                val stillNeeded = uiSubscribers.get() > 0 ||
                    settings.overlayConfig.enabled ||
                    _profilerState.value.isRecording ||
                    settings.watchdogConfig.enabled
                if (!stillNeeded) break

                if (!_isPaused.value || _profilerState.value.isRecording || settings.watchdogConfig.enabled) {
                    val snap = runCatching { bridge.collectSnapshot(settings) }.getOrNull()
                    if (snap != null) {
                        _snapshot.value = snap
                        if (!_isPaused.value) {
                            pushRingCharts(snap)
                        }
                        if (_profilerState.value.isRecording) {
                            handleProfilerTick(snap)
                        }
                        if (settings.watchdogConfig.enabled) {
                            handleWatchdogTick(snap, settings)
                        }
                    }
                }
                delay(settings.samplingIntervalMs.coerceIn(250L, 10_000L))
            }
        }
    }

    private fun pushRingCharts(snap: SystemSnapshot) {
        val ts = snap.timestampMs
        cpuRing.push(snap.cpu.totalUsagePercent ?: 0f, ts)
        val ramPct = if (snap.memory.totalMb > 0) {
            ((snap.memory.totalMb - snap.memory.availableMb).coerceAtLeast(0L) * 100f) / snap.memory.totalMb
        } else 0f
        ramRing.push(ramPct, ts)
        powerRing.push(snap.battery.powerWatts, ts)
        val socTemp = snap.thermal.zones.firstOrNull { it.friendlyCategory == "CPU" }?.tempCelsius
            ?: snap.battery.tempCelsius
        tempRing.push(socTemp, ts)
        fpsRing.push(snap.fps.fps ?: 0f, ts)

        _cpuChart.value = cpuRing.toSnapshot()
        _ramChart.value = ramRing.toSnapshot()
        _powerChart.value = powerRing.toSnapshot()
        _tempChart.value = tempRing.toSnapshot()
        _fpsChart.value = fpsRing.toSnapshot()
    }

    suspend fun startProfilerSession(title: String, intervalMs: Long, targetDurationSec: Int) {
        if (_profilerState.value.isRecording) return
        val tier = bridge.activeTier
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
        val finalTitle = title.ifBlank { "Сессия ${fmt.format(Date())}" }
        val id = profilerRepo.startSession(finalTitle, intervalMs, targetDurationSec, tier.shortLabel)
        recPeakCpu = 0f
        recPeakTemp = 0f
        recMinFreeRam = Long.MAX_VALUE
        recSumCurrent = 0f
        recLastWriteMs = 0L
        _profilerState.value = ActiveProfilerState(
            isRecording = true,
            sessionId = id,
            title = finalTitle,
            startTimeMs = System.currentTimeMillis(),
            targetDurationSec = targetDurationSec,
            intervalMs = intervalMs,
            samplesRecorded = 0
        )
        evaluateCollectorState()
        ProcPulseMonitorService.startOrUpdate(context)
    }

    suspend fun stopProfilerSession() {
        val current = _profilerState.value
        val sid = current.sessionId ?: return
        _profilerState.value = ActiveProfilerState()
        val count = current.samplesRecorded.coerceAtLeast(1)
        profilerRepo.finishSession(
            sessionId = sid,
            peakCpu = recPeakCpu,
            peakTemp = recPeakTemp,
            minFreeRamMb = if (recMinFreeRam == Long.MAX_VALUE) 0L else recMinFreeRam,
            avgCurrentMa = recSumCurrent / count,
            sampleCount = current.samplesRecorded
        )
        evaluateCollectorState()
        ProcPulseMonitorService.startOrUpdate(context)
    }

    private suspend fun handleProfilerTick(snap: SystemSnapshot) {
        val st = _profilerState.value
        val sid = st.sessionId ?: return
        val now = snap.timestampMs
        if (now - recLastWriteMs < st.intervalMs - 100L) return
        recLastWriteMs = now

        val cpu = snap.cpu.totalUsagePercent ?: 0f
        val socTemp = snap.thermal.zones.firstOrNull { it.friendlyCategory == "CPU" }?.tempCelsius
            ?: snap.battery.tempCelsius
        val freeRam = snap.memory.availableMb
        val clusterSummary = snap.cpu.cores
            .groupBy { it.clusterRole }
            .entries
            .joinToString(" | ") { (role, cores) ->
                "${role.label}:${cores.maxOfOrNull { it.currentFreqMhz } ?: 0}MHz"
            }

        val topProcs = snap.topCpuConsumers.ifEmpty { snap.topRamConsumers }.map {
            ProfilerTopProcess(
                pid = it.pid,
                packageName = it.packageName,
                displayName = it.displayName,
                cpuPercent = it.cpuPercent ?: 0f,
                ramMb = it.ramPssMb ?: 0f
            )
        }

        profilerRepo.recordSample(
            ProfilerSampleEntity(
                sessionId = sid,
                timestampMs = now,
                cpuTotalPercent = cpu,
                clusterFreqsSummary = clusterSummary,
                tempCelsius = socTemp,
                batteryTempCelsius = snap.battery.tempCelsius,
                currentMa = snap.battery.currentMa,
                freeRamMb = freeRam,
                zramUsedMb = snap.memory.zramUsedMb,
                fps = snap.fps.fps,
                topProcessesJson = ProfilerRepository.encodeTopProcesses(topProcs)
            )
        )

        if (cpu > recPeakCpu) recPeakCpu = cpu
        if (socTemp > recPeakTemp) recPeakTemp = socTemp
        if (freeRam < recMinFreeRam) recMinFreeRam = freeRam
        recSumCurrent += snap.battery.currentMa

        val newCount = st.samplesRecorded + 1
        _profilerState.value = st.copy(samplesRecorded = newCount)

        if (st.targetDurationSec > 0) {
            val elapsedSec = (now - st.startTimeMs) / 1000L
            if (elapsedSec >= st.targetDurationSec) {
                stopProfilerSession()
            }
        }
    }

    private suspend fun handleWatchdogTick(snap: SystemSnapshot, settings: AppSettings) {
        val wd = settings.watchdogConfig
        val now = snap.timestampMs
        val isScreenOn = powerManager.isInteractive

        if (isScreenOn) {
            screenOffSinceMs = 0L
            highCpuSinceMs.clear()
        } else {
            if (screenOffSinceMs == 0L) screenOffSinceMs = now

            // Check screen-off Blacklist freeze
            if (wd.autoKillBlacklisted && settings.blacklistPackages.isNotEmpty()) {
                val offMin = (now - screenOffSinceMs) / 60_000L
                if (offMin >= wd.screenOffFreezeDelayMinutes && bridge.activeTier != CapabilityTier.STANDARD) {
                    for (pkg in settings.blacklistPackages) {
                        if (!bridge.packageCache.isProtectedPackage(pkg, settings.whitelistPackages)) {
                            val outcome = bridge.setFreeze(pkg, true)
                            if (outcome.success) {
                                profilerRepo.logWatchdogEvent(
                                    "SCREEN_OFF_FREEZE",
                                    pkg,
                                    "Экран выключен > ${wd.screenOffFreezeDelayMinutes} мин",
                                    "Freeze"
                                )
                            }
                        }
                    }
                }
            }

            // Check background CPU hog while screen off
            for (proc in snap.topCpuConsumers) {
                val cpu = proc.cpuPercent ?: 0f
                if (cpu >= wd.cpuThresholdPercent) {
                    val since = highCpuSinceMs.getOrPut(proc.packageName) { now }
                    if (now - since >= wd.cpuDurationMinutes * 60_000L) {
                        highCpuSinceMs[proc.packageName] = now
                        var action = "Уведомление"
                        if (wd.autoKillBlacklisted &&
                            proc.packageName in settings.blacklistPackages &&
                            !proc.isProtected &&
                            bridge.activeTier != CapabilityTier.STANDARD
                        ) {
                            val res = bridge.forceStop(proc.packageName)
                            if (res.success) action = "Auto Force Stop"
                        }
                        val msg = "${proc.displayName} удерживает CPU ${"%.0f".format(cpu)}% при выключенном экране"
                        profilerRepo.logWatchdogEvent("CPU_HOG", proc.packageName, msg, action)
                        postWatchdogAlert("Watchdog: Нагрузка CPU", msg)
                    }
                } else {
                    highCpuSinceMs.remove(proc.packageName)
                }
            }
        }

        // Thermal threshold
        val maxZoneTemp = maxOf(
            snap.battery.tempCelsius,
            snap.thermal.zones.maxOfOrNull { it.tempCelsius } ?: 0f
        )
        if (maxZoneTemp >= wd.tempThresholdCelsius && now - lastAlertTempMs > 120_000L) {
            lastAlertTempMs = now
            val msg = "Температура достигла ${"%.1f".format(Locale.US, maxZoneTemp)}°C (порог ${wd.tempThresholdCelsius}°C)"
            profilerRepo.logWatchdogEvent("THERMAL", null, msg, "Уведомление")
            postWatchdogAlert("Watchdog: Перегрев", msg)
        }

        // Low memory threshold
        val availPlusZram = snap.memory.availableMb + (snap.memory.zramTotalMb - snap.memory.zramUsedMb).coerceAtLeast(0L)
        if (availPlusZram < wd.freeRamThresholdMb && now - lastAlertRamMs > 120_000L) {
            lastAlertRamMs = now
            val msg = "Свободная память ($availPlusZram МБ) ниже порога ${wd.freeRamThresholdMb} МБ"
            profilerRepo.logWatchdogEvent("LOW_RAM", null, msg, "Уведомление")
            postWatchdogAlert("Watchdog: Дефицит RAM", msg)
        }
    }

    private fun ensureNotificationChannels() {
        val chMonitor = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }

        val chProfiler = NotificationChannel(
            CHANNEL_PROFILER,
            context.getString(R.string.channel_profiler_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }

        val chWatchdog = NotificationChannel(
            CHANNEL_WATCHDOG,
            context.getString(R.string.channel_watchdog_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )

        notificationManager.createNotificationChannels(listOf(chMonitor, chProfiler, chWatchdog))
    }

    private fun postWatchdogAlert(title: String, body: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_WATCHDOG)
            .setSmallIcon(R.drawable.ic_notification_pulse)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        runCatching {
            notificationManager.notify((System.currentTimeMillis() % 100_000).toInt() + 2000, notif)
        }
    }

    companion object {
        const val CHANNEL_MONITOR = "procpulse_monitor"
        const val CHANNEL_PROFILER = "procpulse_profiler"
        const val CHANNEL_WATCHDOG = "procpulse_watchdog"
    }
}
