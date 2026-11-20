package com.example.procpulse

import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.procpulse.core.bridge.ProcessActionOutcome
import com.example.procpulse.core.database.ProfilerSampleEntity
import com.example.procpulse.core.database.ProfilerSessionEntity
import com.example.procpulse.core.database.WatchdogEventEntity
import com.example.procpulse.core.model.AccentPreset
import com.example.procpulse.core.model.AppSettings
import com.example.procpulse.core.model.ChartSeriesSnapshot
import com.example.procpulse.core.model.CurrentCalibrationMode
import com.example.procpulse.core.model.LogcatEntry
import com.example.procpulse.core.model.OverlayConfig
import com.example.procpulse.core.model.PreferredTierMode
import com.example.procpulse.core.model.ProcessCategory
import com.example.procpulse.core.model.ProcessDetail
import com.example.procpulse.core.model.ProcessEntry
import com.example.procpulse.core.model.ProcessSortBy
import com.example.procpulse.core.model.SocketConnection
import com.example.procpulse.core.model.SystemSnapshot
import com.example.procpulse.core.model.ThemeMode
import com.example.procpulse.core.model.WatchdogConfig
import com.example.procpulse.service.ActiveProfilerState
import com.example.procpulse.service.ProcPulseMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class ProcPulseViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ProcPulseApp
    private val coordinator = app.coordinator
    private val bridge = app.bridge
    private val settingsRepo = app.settingsRepo
    private val profilerRepo = app.profilerRepo

    val settings: StateFlow<AppSettings> = coordinator.settingsFlow
    val snapshot: StateFlow<SystemSnapshot?> = coordinator.snapshot
    val isPaused: StateFlow<Boolean> = coordinator.isPaused
    val profilerState: StateFlow<ActiveProfilerState> = coordinator.profilerState

    val cpuChart: StateFlow<ChartSeriesSnapshot> = coordinator.cpuChart
    val ramChart: StateFlow<ChartSeriesSnapshot> = coordinator.ramChart
    val powerChart: StateFlow<ChartSeriesSnapshot> = coordinator.powerChart
    val tempChart: StateFlow<ChartSeriesSnapshot> = coordinator.tempChart
    val fpsChart: StateFlow<ChartSeriesSnapshot> = coordinator.fpsChart

    // Process Manager state
    private val _selectedCategory = MutableStateFlow(ProcessCategory.APPS)
    val selectedCategory: StateFlow<ProcessCategory> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filterCpuNonZero = MutableStateFlow(false)
    val filterCpuNonZero: StateFlow<Boolean> = _filterCpuNonZero.asStateFlow()

    private val _filterActiveNet = MutableStateFlow(false)
    val filterActiveNet: StateFlow<Boolean> = _filterActiveNet.asStateFlow()

    private val _filterInMemory = MutableStateFlow(false)
    val filterInMemory: StateFlow<Boolean> = _filterInMemory.asStateFlow()

    private val _sortBy = MutableStateFlow(ProcessSortBy.CPU)
    val sortBy: StateFlow<ProcessSortBy> = _sortBy.asStateFlow()

    private val _rawProcesses = MutableStateFlow<List<ProcessEntry>>(emptyList())
    private val _selectedPids = MutableStateFlow<Set<Int>>(emptySet())
    val selectedPids: StateFlow<Set<Int>> = _selectedPids.asStateFlow()

    private val debouncedQuery = _searchQuery.debounce(100L)

    val filteredProcesses: StateFlow<List<ProcessEntry>> = combine(
        _rawProcesses,
        _selectedCategory,
        debouncedQuery,
        _filterCpuNonZero,
        _filterActiveNet,
        _filterInMemory,
        _sortBy
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val list = arr[0] as List<ProcessEntry>
        val cat = arr[1] as ProcessCategory
        val q = (arr[2] as String).trim().lowercase()
        val cpuOnly = arr[3] as Boolean
        val netOnly = arr[4] as Boolean
        val memOnly = arr[5] as Boolean
        val sort = arr[6] as ProcessSortBy

        val filtered = list.filter { p ->
            val catMatch = p.category == cat
            val queryMatch = q.isEmpty() ||
                p.displayName.lowercase().contains(q) ||
                p.packageName.lowercase().contains(q) ||
                p.pid.toString().contains(q) ||
                p.uid.toString().contains(q)
            val cpuMatch = !cpuOnly || (p.cpuPercent ?: 0f) > 0f
            val netMatch = !netOnly || p.netBytesTotal > 0L
            val memMatch = !memOnly || (p.ramPssMb ?: 0f) > 0f
            catMatch && queryMatch && cpuMatch && netMatch && memMatch
        }

        when (sort) {
            ProcessSortBy.CPU -> filtered.sortedWith(compareByDescending<ProcessEntry> { it.cpuPercent ?: -1f }.thenByDescending { it.ramPssMb ?: -1f })
            ProcessSortBy.RAM -> filtered.sortedByDescending { it.ramPssMb ?: -1f }
            ProcessSortBy.NETWORK -> filtered.sortedByDescending { it.netBytesTotal }
            ProcessSortBy.OOM -> filtered.sortedBy { it.oomScoreAdj ?: Int.MAX_VALUE }
            ProcessSortBy.PID -> filtered.sortedBy { it.pid }
            ProcessSortBy.UPTIME -> filtered.sortedByDescending { it.uptimeSeconds }
            ProcessSortBy.NAME -> filtered.sortedBy { it.displayName.lowercase() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Inspector state
    private val _inspectedEntry = MutableStateFlow<ProcessEntry?>(null)
    val inspectedEntry: StateFlow<ProcessEntry?> = _inspectedEntry.asStateFlow()

    private val _inspectedDetail = MutableStateFlow<ProcessDetail?>(null)
    val inspectedDetail: StateFlow<ProcessDetail?> = _inspectedDetail.asStateFlow()

    private val _logcatEntries = MutableStateFlow<List<LogcatEntry>>(emptyList())
    val logcatEntries: StateFlow<List<LogcatEntry>> = _logcatEntries.asStateFlow()

    private val _logcatPaused = MutableStateFlow(false)
    val logcatPaused: StateFlow<Boolean> = _logcatPaused.asStateFlow()

    private val _logcatLevel = MutableStateFlow('V')
    val logcatLevel: StateFlow<Char> = _logcatLevel.asStateFlow()

    private val _logcatFilter = MutableStateFlow("")
    val logcatFilter: StateFlow<String> = _logcatFilter.asStateFlow()

    // Sockets modal state
    private val _activeSockets = MutableStateFlow<List<SocketConnection>>(emptyList())
    val activeSockets: StateFlow<List<SocketConnection>> = _activeSockets.asStateFlow()

    // Profiler & Watchdog
    val sessions: StateFlow<List<ProfilerSessionEntity>> = profilerRepo.sessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val watchdogEvents: StateFlow<List<WatchdogEventEntity>> = profilerRepo.watchdogEvents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedSessionId = MutableStateFlow<Long?>(null)
    val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    private val _selectedSessionSamples = MutableStateFlow<List<ProfilerSampleEntity>>(emptyList())
    val selectedSessionSamples: StateFlow<List<ProfilerSampleEntity>> = _selectedSessionSamples.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private var processLoopJob: Job? = null
    private var inspectorLoopJob: Job? = null
    private var sessionObserveJob: Job? = null

    init {
        viewModelScope.launch {
            snapshot.collect { snap ->
                if (snap != null) {
                    refreshProcessListOnce()
                }
            }
        }
    }

    fun onUiStarted() = coordinator.onUiStarted()
    fun onUiStopped() = coordinator.onUiStopped()

    fun togglePause() = coordinator.togglePause()

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    fun getAppIcon(packageName: String): ImageBitmap? = bridge.packageCache.getAppIcon(packageName)

    fun hasUsageStatsPermission(): Boolean = bridge.hardwareHelper.hasUsageStatsPermission()

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(app)

    fun isBatteryOptimizationIgnored(): Boolean {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(app.packageName)
    }

    fun refreshProcessListOnce() {
        viewModelScope.launch {
            val list = bridge.collectProcesses(settings.value, null)
            _rawProcesses.value = list
        }
    }

    fun selectCategory(cat: ProcessCategory) {
        _selectedCategory.value = cat
        _selectedPids.value = emptySet()
    }

    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun toggleFilterCpu() { _filterCpuNonZero.value = !_filterCpuNonZero.value }
    fun toggleFilterNet() { _filterActiveNet.value = !_filterActiveNet.value }
    fun toggleFilterMem() { _filterInMemory.value = !_filterInMemory.value }
    fun setSortBy(sort: ProcessSortBy) { _sortBy.value = sort }

    fun toggleSelectPid(pid: Int) {
        val current = _selectedPids.value
        _selectedPids.value = if (pid in current) current - pid else current + pid
    }

    fun clearSelectedPids() { _selectedPids.value = emptySet() }

    fun batchActionForceStop() {
        viewModelScope.launch {
            val targets = _rawProcesses.value.filter { it.pid in _selectedPids.value && !it.isProtected }
            var ok = 0
            for (t in targets) {
                if (bridge.forceStop(t.packageName).success) ok++
            }
            _selectedPids.value = emptySet()
            _actionMessage.value = "Force Stop выполнен для $ok процессов (защищённые пропущены)"
            refreshProcessListOnce()
        }
    }

    fun batchActionFreeze() {
        viewModelScope.launch {
            val targets = _rawProcesses.value.filter { it.pid in _selectedPids.value && !it.isProtected }
            var ok = 0
            for (t in targets) {
                if (bridge.setFreeze(t.packageName, true).success) ok++
            }
            _selectedPids.value = emptySet()
            _actionMessage.value = "Заморожено пакетов: $ok"
            refreshProcessListOnce()
        }
    }

    fun batchAddToWhitelist() {
        viewModelScope.launch {
            val targets = _rawProcesses.value.filter { it.pid in _selectedPids.value }
            for (t in targets) {
                settingsRepo.addToWhitelist(t.packageName)
            }
            _selectedPids.value = emptySet()
            _actionMessage.value = "Добавлено в Whitelist: ${targets.size}"
            refreshProcessListOnce()
        }
    }

    fun inspectProcess(entry: ProcessEntry) {
        _inspectedEntry.value = entry
        _inspectedDetail.value = null
        _logcatEntries.value = emptyList()
        inspectorLoopJob?.cancel()
        inspectorLoopJob = viewModelScope.launch {
            while (isActive) {
                val detail = bridge.collectProcessDetail(entry, settings.value)
                _inspectedDetail.value = detail
                if (!_logcatPaused.value) {
                    _logcatEntries.value = bridge.collectLogcat(entry.pid, 200)
                }
                delay(1500L)
            }
        }
    }

    fun closeInspector() {
        inspectorLoopJob?.cancel()
        inspectorLoopJob = null
        _inspectedEntry.value = null
        _inspectedDetail.value = null
    }

    fun toggleLogcatPause() { _logcatPaused.value = !_logcatPaused.value }
    fun setLogcatLevel(lvl: Char) { _logcatLevel.value = lvl }
    fun setLogcatFilter(q: String) { _logcatFilter.value = q }

    fun loadSocketsTable() {
        viewModelScope.launch {
            _activeSockets.value = bridge.collectSockets(null)
        }
    }

    // --- Process Control Actions ---
    fun performKillBackground(pkg: String) = runAction { bridge.killBackground(pkg) }
    fun performForceStop(pkg: String) = runAction { bridge.forceStop(pkg) }
    fun performFreeze(pkg: String, freeze: Boolean) = runAction { bridge.setFreeze(pkg, freeze) }
    fun performRestrictBackground(pkg: String, restrict: Boolean) = runAction { bridge.setBackgroundRestricted(pkg, restrict) }
    fun performSendSignal(pid: Int, sig: Int) = runAction { bridge.sendSignal(pid, sig) }
    fun performRenice(pid: Int, nice: Int) = runAction { bridge.reniceProcess(pid, nice) }
    fun performTaskset(pid: Int, mask: String) = runAction { bridge.setCpuAffinity(pid, mask) }
    fun performToggleComponent(pkg: String, cls: String, enabled: Boolean) =
        runAction { bridge.setComponentEnabled(pkg, cls, enabled) }

    private fun runAction(block: suspend () -> ProcessActionOutcome) {
        viewModelScope.launch {
            val outcome = block()
            _actionMessage.value = outcome.message
            refreshProcessListOnce()
            _inspectedEntry.value?.let { entry ->
                _inspectedDetail.value = bridge.collectProcessDetail(entry, settings.value)
            }
        }
    }

    // --- Capability Tier Actions ---
    fun connectShizuku() {
        viewModelScope.launch {
            val granted = bridge.requestShizukuPermission()
            if (granted) {
                bridge.refreshCapabilities(settings.value)
                _actionMessage.value = "Shizuku подключён (Tier 1)"
            } else {
                _actionMessage.value = "Shizuku не запущен или доступ отклонён"
            }
        }
    }

    fun requestRoot() {
        viewModelScope.launch {
            val ok = bridge.checkRootAccess()
            if (ok) {
                bridge.refreshCapabilities(settings.value)
                _actionMessage.value = "Root-доступ подтверждён (Tier 2)"
            } else {
                _actionMessage.value = "Root-доступ не получен (su недоступен)"
            }
        }
    }

    // --- Settings & Overlay & Profiler ---
    fun setSamplingInterval(ms: Long) { viewModelScope.launch { settingsRepo.setSamplingIntervalMs(ms) } }
    fun setThemeMode(m: ThemeMode) { viewModelScope.launch { settingsRepo.setThemeMode(m) } }
    fun setAccentPreset(p: AccentPreset) { viewModelScope.launch { settingsRepo.setAccentPreset(p) } }
    fun setPreferredTier(m: PreferredTierMode) {
        viewModelScope.launch {
            settingsRepo.setPreferredTierMode(m)
            bridge.refreshCapabilities(settings.value.copy(preferredTierMode = m))
        }
    }
    fun setInvertCurrent(inv: Boolean) { viewModelScope.launch { settingsRepo.setInvertCurrentSign(inv) } }
    fun setCurrentCalibration(mode: CurrentCalibrationMode) { viewModelScope.launch { settingsRepo.setCurrentCalibrationMode(mode) } }
    fun setStartWatchdogOnBoot(enabled: Boolean) { viewModelScope.launch { settingsRepo.setStartWatchdogOnBoot(enabled) } }

    fun addToWhitelist(pkg: String) { viewModelScope.launch { settingsRepo.addToWhitelist(pkg) } }
    fun removeFromWhitelist(pkg: String) { viewModelScope.launch { settingsRepo.removeFromWhitelist(pkg) } }
    fun toggleBlacklist(pkg: String) { viewModelScope.launch { settingsRepo.toggleBlacklist(pkg) } }

    fun updateOverlayConfig(cfg: OverlayConfig) {
        viewModelScope.launch {
            settingsRepo.updateOverlayConfig(cfg)
            coordinator.evaluateCollectorState()
            if (cfg.enabled) {
                ProcPulseMonitorService.startOrUpdate(app)
            }
        }
    }

    fun updateWatchdogConfig(cfg: WatchdogConfig) {
        viewModelScope.launch {
            settingsRepo.updateWatchdogConfig(cfg)
            coordinator.evaluateCollectorState()
            if (cfg.enabled) {
                ProcPulseMonitorService.startOrUpdate(app)
            }
        }
    }

    fun startProfiler(title: String, intervalMs: Long, durationSec: Int) {
        viewModelScope.launch {
            coordinator.startProfilerSession(title, intervalMs, durationSec)
            _actionMessage.value = "Запись Blackbox запущена"
        }
    }

    fun stopProfiler() {
        viewModelScope.launch {
            coordinator.stopProfilerSession()
            _actionMessage.value = "Сессия Blackbox сохранена"
        }
    }

    fun selectSessionForAnalysis(sessionId: Long?) {
        _selectedSessionId.value = sessionId
        sessionObserveJob?.cancel()
        if (sessionId == null) {
            _selectedSessionSamples.value = emptyList()
            return
        }
        sessionObserveJob = viewModelScope.launch {
            profilerRepo.observeSamples(sessionId).collect { samples ->
                _selectedSessionSamples.value = samples
            }
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            if (_selectedSessionId.value == sessionId) selectSessionForAnalysis(null)
            profilerRepo.deleteSession(sessionId)
        }
    }

    fun clearWatchdogEvents() {
        viewModelScope.launch { profilerRepo.clearWatchdogEvents() }
    }

    suspend fun generateSessionCsv(sessionId: Long): String = profilerRepo.exportSessionAsCsv(sessionId)
    suspend fun generateSessionJson(sessionId: Long): String = profilerRepo.exportSessionAsJson(sessionId)

    fun buildProcessDiagnosticJson(detail: ProcessDetail): String {
        return org.json.JSONObject().apply {
            put("pid", detail.entry.pid)
            put("ppid", detail.entry.ppid)
            put("uid", detail.entry.uid)
            put("packageName", detail.entry.packageName)
            put("displayName", detail.entry.displayName)
            put("stateTag", detail.entry.stateTag.tag)
            put("cpuPercent", detail.entry.cpuPercent ?: org.json.JSONObject.NULL)
            put("pssMb", detail.pssMb ?: org.json.JSONObject.NULL)
            put("ussMb", detail.ussMb ?: org.json.JSONObject.NULL)
            put("rssMb", detail.rssMb ?: org.json.JSONObject.NULL)
            put("swapZramMb", detail.swapZramMb ?: org.json.JSONObject.NULL)
            put("nice", detail.nice ?: org.json.JSONObject.NULL)
            put("oomScoreAdj", detail.oomScoreAdj ?: org.json.JSONObject.NULL)
            put("standbyBucket", detail.standbyBucket)
            put("threads", detail.threadsCount ?: org.json.JSONObject.NULL)
            put("fdCount", detail.fdCount ?: org.json.JSONObject.NULL)
            put("affinityMask", detail.affinityMask ?: org.json.JSONObject.NULL)
            put("wakeLocksCount", detail.wakeLocks.size)
            put("socketsCount", detail.sockets.size)
        }.toString(2)
    }

    fun buildProcessDiagnosticMarkdown(detail: ProcessDetail): String = buildString {
        appendLine("# ProcPulse Diagnostic: ${detail.entry.displayName}")
        appendLine("- **Package:** `${detail.entry.packageName}`")
        appendLine("- **PID / PPID / UID:** ${detail.entry.pid} / ${detail.entry.ppid} / ${detail.entry.uid}")
        appendLine("- **State:** ${detail.entry.stateTag.tag} | **Standby:** ${detail.standbyBucket}")
        appendLine("- **CPU:** ${detail.entry.cpuPercent?.let { "%.1f%%".format(it) } ?: "N/A"}")
        appendLine("- **Memory (PSS / USS / RSS / Swap):** ${detail.pssMb?.let { "%.1f".format(it) } ?: "--"} / ${detail.ussMb?.let { "%.1f".format(it) } ?: "--"} / ${detail.rssMb?.let { "%.1f".format(it) } ?: "--"} / ${detail.swapZramMb?.let { "%.1f".format(it) } ?: "--"} MB")
        appendLine("- **OOM Score Adj:** ${detail.oomScoreAdj ?: "N/A"} | **Nice:** ${detail.nice ?: "N/A"}")
        appendLine("- **Threads / FD:** ${detail.threadsCount ?: "N/A"} / ${detail.fdCount ?: "N/A"}")
        if (detail.wakeLocks.isNotEmpty()) {
            appendLine("## WakeLocks")
            detail.wakeLocks.forEach { appendLine("- `$it`") }
        }
        if (detail.sockets.isNotEmpty()) {
            appendLine("## Active Sockets (${detail.sockets.size})")
            detail.sockets.forEach {
                appendLine("- `${it.protocol}` ${it.localAddress}:${it.localPort} -> ${it.remoteAddress}:${it.remotePort} (${it.state})")
            }
        }
    }

    fun writeTextToUri(uri: Uri, content: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    }
                    true
                }.getOrDefault(false)
            }
            onDone(ok)
        }
    }

    fun openAppDetailsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
