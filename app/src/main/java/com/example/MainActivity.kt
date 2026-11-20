package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.procpulse.ProcPulseViewModel
import com.example.procpulse.core.model.CapabilityTier
import com.example.procpulse.core.ui.TierBadge
import com.example.procpulse.feature.dashboard.DashboardScreen
import com.example.procpulse.feature.inspector.InspectorScreen
import com.example.procpulse.feature.overlay.OverlayToolsScreen
import com.example.procpulse.feature.profiler.ProfilerScreen
import com.example.procpulse.feature.processes.ProcessesScreen
import com.example.procpulse.feature.settings.SettingsScreen
import com.example.ui.theme.LocalProcPulsePalette
import com.example.ui.theme.ProcPulseTheme
import com.example.ui.theme.TnumText
import kotlinx.coroutines.launch

enum class MainTab(val label: String, val icon: ImageVector) {
    MONITOR("Монитор", Icons.Outlined.MonitorHeart),
    PROCESSES("Процессы", Icons.Outlined.Apps),
    OVERLAY("Оверлей", Icons.Outlined.Layers),
    PROFILER("Профайлер", Icons.Outlined.Analytics)
}

class MainActivity : ComponentActivity() {

    private val viewModel: ProcPulseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            ProcPulseTheme(
                themeMode = settings.themeMode,
                accentPreset = settings.accentPreset
            ) {
                ProcPulseRootShell(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun ProcPulseRootShell(viewModel: ProcPulseViewModel) {
    val palette = LocalProcPulsePalette.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Lifecycle-aware zero-overhead telemetry activation
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onUiStarted()
                Lifecycle.Event.ON_STOP -> viewModel.onUiStopped()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Request POST_NOTIFICATIONS on Android 13+ when needed
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // SAF Document Export Launcher
    var pendingExportText by remember { mutableStateOf("") }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null && pendingExportText.isNotEmpty()) {
            viewModel.writeTextToUri(uri, pendingExportText) { ok ->
                scope.launch {
                    snackbarHostState.showSnackbar(if (ok) "Файл успешно экспортирован" else "Ошибка сохранения файла")
                }
            }
        }
    }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val cpuChart by viewModel.cpuChart.collectAsStateWithLifecycle()
    val ramChart by viewModel.ramChart.collectAsStateWithLifecycle()
    val powerChart by viewModel.powerChart.collectAsStateWithLifecycle()
    val tempChart by viewModel.tempChart.collectAsStateWithLifecycle()
    val profilerState by viewModel.profilerState.collectAsStateWithLifecycle()

    val processes by viewModel.filteredProcesses.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filterCpu by viewModel.filterCpuNonZero.collectAsStateWithLifecycle()
    val filterNet by viewModel.filterActiveNet.collectAsStateWithLifecycle()
    val filterMem by viewModel.filterInMemory.collectAsStateWithLifecycle()
    val sortBy by viewModel.sortBy.collectAsStateWithLifecycle()
    val selectedKeys by viewModel.selectedKeys.collectAsStateWithLifecycle()

    val inspectedEntry by viewModel.inspectedEntry.collectAsStateWithLifecycle()
    val inspectedDetail by viewModel.inspectedDetail.collectAsStateWithLifecycle()
    val logcatEntries by viewModel.logcatEntries.collectAsStateWithLifecycle()
    val logcatPaused by viewModel.logcatPaused.collectAsStateWithLifecycle()
    val logcatLevel by viewModel.logcatLevel.collectAsStateWithLifecycle()
    val logcatFilter by viewModel.logcatFilter.collectAsStateWithLifecycle()

    val sockets by viewModel.activeSockets.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val selectedSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()
    val selectedSamples by viewModel.selectedSessionSamples.collectAsStateWithLifecycle()
    val selectedLagReport by viewModel.selectedLagReport.collectAsStateWithLifecycle()
    val watchdogEvents by viewModel.watchdogEvents.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()

    LaunchedEffect(actionMessage) {
        actionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearActionMessage()
        }
    }

    var currentTab by rememberSaveable { mutableStateOf(MainTab.MONITOR) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Tell Coordinator/ViewModel which heavy polling tabs are active so background tabs stay idle
    LaunchedEffect(currentTab, showSettings) {
        viewModel.setActiveScreenContext(
            isMonitor = !showSettings && currentTab == MainTab.MONITOR,
            isProcesses = !showSettings && currentTab == MainTab.PROCESSES
        )
    }

    val navItemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = palette.accent,
        selectedTextColor = palette.accent,
        indicatorColor = palette.accent.copy(alpha = 0.16f),
        unselectedIconColor = palette.textSecondary,
        unselectedTextColor = palette.textSecondary
    )
    val railItemColors = NavigationRailItemDefaults.colors(
        selectedIconColor = palette.accent,
        selectedTextColor = palette.accent,
        indicatorColor = palette.accent.copy(alpha = 0.16f),
        unselectedIconColor = palette.textSecondary,
        unselectedTextColor = palette.textSecondary
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        val isWideScreen = maxWidth >= 600.dp

        Scaffold(
            containerColor = palette.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                if (!showSettings) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.card)
                            .windowInsetsPadding(WindowInsets.statusBars)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "PROCPULSE",
                                    style = TnumText.SectionHeader.copy(fontWeight = FontWeight.Bold),
                                    color = palette.accent
                                )
                                Spacer(Modifier.width(10.dp))
                                TierBadge(
                                    tier = snapshot?.tier ?: CapabilityTier.STANDARD,
                                    onClick = { showSettings = true }
                                )
                            }
                            IconButton(
                                onClick = { showSettings = true },
                                modifier = Modifier.testTag("open_settings_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = "Настройки",
                                    tint = palette.textPrimary
                                )
                            }
                        }
                        HorizontalDivider(color = palette.border)
                    }
                }
            },
            bottomBar = {
                if (!isWideScreen && !showSettings && inspectedEntry == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.card)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        HorizontalDivider(color = palette.border)
                        NavigationBar(
                            containerColor = palette.card,
                            tonalElevation = 0.dp
                        ) {
                            MainTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = {
                                        currentTab = tab
                                        showSettings = false
                                    },
                                    colors = navItemColors,
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label, style = TnumText.MicroLabel) },
                                    modifier = Modifier.testTag("nav_${tab.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail(
                        containerColor = palette.card,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        MainTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = !showSettings && currentTab == tab,
                                onClick = {
                                    currentTab = tab
                                    showSettings = false
                                },
                                colors = railItemColors,
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label, style = TnumText.MicroLabel) },
                                modifier = Modifier.testTag("rail_${tab.name.lowercase()}")
                            )
                        }
                    }
                    VerticalDivider(color = palette.border)
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    if (showSettings) {
                        SettingsScreen(
                            settings = settings,
                            snapshot = snapshot,
                            isBatteryOptIgnored = viewModel.isBatteryOptimizationIgnored(),
                            loadInstalledApps = viewModel::getAllInstalledApps,
                            onBack = { showSettings = false },
                            onSelectTheme = viewModel::setThemeMode,
                            onSelectAccent = viewModel::setAccentPreset,
                            onSelectPreferredTier = viewModel::setPreferredTier,
                            onConnectShizuku = viewModel::connectShizuku,
                            onRequestRoot = viewModel::requestRoot,
                            onInvertCurrent = viewModel::setInvertCurrent,
                            onSelectCalibration = viewModel::setCurrentCalibration,
                            onBootWatchdogToggle = viewModel::setStartWatchdogOnBoot,
                            onAddWhitelist = viewModel::addToWhitelist,
                            onRemoveWhitelist = viewModel::removeFromWhitelist,
                            onToggleBlacklist = viewModel::toggleBlacklist
                        )
                    } else if (!isWideScreen && inspectedEntry != null) {
                        InspectorScreen(
                            detail = inspectedDetail,
                            activeTier = snapshot?.tier ?: CapabilityTier.STANDARD,
                            shizukuAvailable = snapshot?.shizukuAvailable == true,
                            isWhitelisted = inspectedEntry?.packageName in settings.whitelistPackages,
                            isBlacklisted = inspectedEntry?.packageName in settings.blacklistPackages,
                            logcatEntries = logcatEntries,
                            logcatPaused = logcatPaused,
                            logcatLevel = logcatLevel,
                            logcatFilter = logcatFilter,
                            showBackButton = true,
                            onBack = viewModel::closeInspector,
                            onConnectShizuku = viewModel::connectShizuku,
                            onRequestRoot = viewModel::requestRoot,
                            onOpenAppInfo = { pkg -> context.startActivity(viewModel.openAppDetailsIntent(pkg)) },
                            onKillBackground = viewModel::performKillBackground,
                            onForceStop = viewModel::performForceStop,
                            onFreezeToggle = viewModel::performFreeze,
                            onRestrictBackgroundToggle = viewModel::performRestrictBackground,
                            onSendSignal = viewModel::performSendSignal,
                            onRenice = viewModel::performRenice,
                            onTaskset = viewModel::performTaskset,
                            onToggleComponent = viewModel::performToggleComponent,
                            onToggleWhitelist = { pkg ->
                                if (pkg in settings.whitelistPackages) viewModel.removeFromWhitelist(pkg)
                                else viewModel.addToWhitelist(pkg)
                            },
                            onToggleBlacklist = viewModel::toggleBlacklist,
                            onToggleLogcatPause = viewModel::toggleLogcatPause,
                            onLogcatLevelChange = viewModel::setLogcatLevel,
                            onLogcatFilterChange = viewModel::setLogcatFilter,
                            onExportJson = { det ->
                                val idTag = det.entry.pid?.let { "pid$it" } ?: "uid${det.entry.uid}"
                                pendingExportText = viewModel.buildProcessDiagnosticJson(det)
                                exportLauncher.launch("${det.entry.packageName}_$idTag.json")
                            },
                            onExportMarkdown = { det ->
                                val idTag = det.entry.pid?.let { "pid$it" } ?: "uid${det.entry.uid}"
                                pendingExportText = viewModel.buildProcessDiagnosticMarkdown(det)
                                exportLauncher.launch("${det.entry.packageName}_$idTag.md")
                            },
                            onExportLogcat = { pkg, logs ->
                                pendingExportText = logs.joinToString("\n") { it.rawLine }
                                exportLauncher.launch("${pkg}_logcat.log")
                            }
                        )
                    } else {
                        when (currentTab) {
                            MainTab.MONITOR -> DashboardScreen(
                                snapshot = snapshot,
                                isPaused = isPaused,
                                samplingIntervalMs = settings.samplingIntervalMs,
                                cpuChart = cpuChart,
                                ramChart = ramChart,
                                powerChart = powerChart,
                                tempChart = tempChart,
                                sockets = sockets,
                                onTogglePause = viewModel::togglePause,
                                onSelectSamplingInterval = viewModel::setSamplingInterval,
                                onConnectShizuku = viewModel::connectShizuku,
                                onRequestRoot = viewModel::requestRoot,
                                onOpenCalibration = { showSettings = true },
                                onLoadSockets = viewModel::loadSocketsTable,
                                onInspectProcess = { proc ->
                                    viewModel.inspectProcess(proc)
                                    currentTab = MainTab.PROCESSES
                                }
                            )

                            MainTab.PROCESSES -> {
                                if (isWideScreen) {
                                    // Canonical two-pane List-Detail layout for tablets & foldables (>= 600dp)
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        Box(modifier = Modifier.weight(0.52f).fillMaxHeight()) {
                                            ProcessesScreen(
                                                processes = processes,
                                                activeTier = snapshot?.tier ?: CapabilityTier.STANDARD,
                                                shizukuAvailable = snapshot?.shizukuAvailable == true,
                                                hasUsageStatsPermission = viewModel.hasUsageStatsPermission(),
                                                selectedCategory = selectedCategory,
                                                searchQuery = searchQuery,
                                                filterCpuNonZero = filterCpu,
                                                filterActiveNet = filterNet,
                                                filterInMemory = filterMem,
                                                sortBy = sortBy,
                                                selectedKeys = selectedKeys,
                                                inspectedKey = inspectedEntry?.processKey(),
                                                getCachedAppIcon = viewModel::getCachedAppIcon,
                                                loadAppIcon = viewModel::loadAppIcon,
                                                onSelectCategory = viewModel::selectCategory,
                                                onSearchQueryChange = viewModel::setSearchQuery,
                                                onToggleFilterCpu = viewModel::toggleFilterCpu,
                                                onToggleFilterNet = viewModel::toggleFilterNet,
                                                onToggleFilterMem = viewModel::toggleFilterMem,
                                                onSelectSort = viewModel::setSortBy,
                                                onToggleSelectProcess = viewModel::toggleSelectProcess,
                                                onClearSelection = viewModel::clearSelectedProcesses,
                                                onBatchForceStop = viewModel::batchActionForceStop,
                                                onBatchFreeze = viewModel::batchActionFreeze,
                                                onBatchWhitelist = viewModel::batchAddToWhitelist,
                                                onInspectProcess = viewModel::inspectProcess,
                                                onConnectShizuku = viewModel::connectShizuku,
                                                onRequestRoot = viewModel::requestRoot
                                            )
                                        }
                                        VerticalDivider(color = palette.border)
                                        Box(modifier = Modifier.weight(0.48f).fillMaxHeight()) {
                                            InspectorScreen(
                                                detail = inspectedDetail,
                                                activeTier = snapshot?.tier ?: CapabilityTier.STANDARD,
                                                shizukuAvailable = snapshot?.shizukuAvailable == true,
                                                isWhitelisted = inspectedEntry?.packageName in settings.whitelistPackages,
                                                isBlacklisted = inspectedEntry?.packageName in settings.blacklistPackages,
                                                logcatEntries = logcatEntries,
                                                logcatPaused = logcatPaused,
                                                logcatLevel = logcatLevel,
                                                logcatFilter = logcatFilter,
                                                showBackButton = false,
                                                onBack = viewModel::closeInspector,
                                                onConnectShizuku = viewModel::connectShizuku,
                                                onRequestRoot = viewModel::requestRoot,
                                                onOpenAppInfo = { pkg -> context.startActivity(viewModel.openAppDetailsIntent(pkg)) },
                                                onKillBackground = viewModel::performKillBackground,
                                                onForceStop = viewModel::performForceStop,
                                                onFreezeToggle = viewModel::performFreeze,
                                                onRestrictBackgroundToggle = viewModel::performRestrictBackground,
                                                onSendSignal = viewModel::performSendSignal,
                                                onRenice = viewModel::performRenice,
                                                onTaskset = viewModel::performTaskset,
                                                onToggleComponent = viewModel::performToggleComponent,
                                                onToggleWhitelist = { pkg ->
                                                    if (pkg in settings.whitelistPackages) viewModel.removeFromWhitelist(pkg)
                                                    else viewModel.addToWhitelist(pkg)
                                                },
                                                onToggleBlacklist = viewModel::toggleBlacklist,
                                                onToggleLogcatPause = viewModel::toggleLogcatPause,
                                                onLogcatLevelChange = viewModel::setLogcatLevel,
                                                onLogcatFilterChange = viewModel::setLogcatFilter,
                                                onExportJson = { det ->
                                                    val idTag = det.entry.pid?.let { "pid$it" } ?: "uid${det.entry.uid}"
                                                    pendingExportText = viewModel.buildProcessDiagnosticJson(det)
                                                    exportLauncher.launch("${det.entry.packageName}_$idTag.json")
                                                },
                                                onExportMarkdown = { det ->
                                                    val idTag = det.entry.pid?.let { "pid$it" } ?: "uid${det.entry.uid}"
                                                    pendingExportText = viewModel.buildProcessDiagnosticMarkdown(det)
                                                    exportLauncher.launch("${det.entry.packageName}_$idTag.md")
                                                },
                                                onExportLogcat = { pkg, logs ->
                                                    pendingExportText = logs.joinToString("\n") { it.rawLine }
                                                    exportLauncher.launch("${pkg}_logcat.log")
                                                }
                                            )
                                        }
                                    }
                                } else {
                                    ProcessesScreen(
                                        processes = processes,
                                        activeTier = snapshot?.tier ?: CapabilityTier.STANDARD,
                                        shizukuAvailable = snapshot?.shizukuAvailable == true,
                                        hasUsageStatsPermission = viewModel.hasUsageStatsPermission(),
                                        selectedCategory = selectedCategory,
                                        searchQuery = searchQuery,
                                        filterCpuNonZero = filterCpu,
                                        filterActiveNet = filterNet,
                                        filterInMemory = filterMem,
                                        sortBy = sortBy,
                                        selectedKeys = selectedKeys,
                                        inspectedKey = inspectedEntry?.processKey(),
                                        getCachedAppIcon = viewModel::getCachedAppIcon,
                                        loadAppIcon = viewModel::loadAppIcon,
                                        onSelectCategory = viewModel::selectCategory,
                                        onSearchQueryChange = viewModel::setSearchQuery,
                                        onToggleFilterCpu = viewModel::toggleFilterCpu,
                                        onToggleFilterNet = viewModel::toggleFilterNet,
                                        onToggleFilterMem = viewModel::toggleFilterMem,
                                        onSelectSort = viewModel::setSortBy,
                                        onToggleSelectProcess = viewModel::toggleSelectProcess,
                                        onClearSelection = viewModel::clearSelectedProcesses,
                                        onBatchForceStop = viewModel::batchActionForceStop,
                                        onBatchFreeze = viewModel::batchActionFreeze,
                                        onBatchWhitelist = viewModel::batchAddToWhitelist,
                                        onInspectProcess = viewModel::inspectProcess,
                                        onConnectShizuku = viewModel::connectShizuku,
                                        onRequestRoot = viewModel::requestRoot
                                    )
                                }
                            }

                            MainTab.OVERLAY -> OverlayToolsScreen(
                                config = settings.overlayConfig,
                                canDrawOverlays = viewModel.canDrawOverlays(),
                                snapshot = snapshot,
                                onUpdateConfig = viewModel::updateOverlayConfig
                            )

                            MainTab.PROFILER -> ProfilerScreen(
                                profilerState = profilerState,
                                sessions = sessions,
                                selectedSessionId = selectedSessionId,
                                selectedSamples = selectedSamples,
                                selectedLagReport = selectedLagReport,
                                watchdogConfig = settings.watchdogConfig,
                                watchdogEvents = watchdogEvents,
                                onStartRecording = viewModel::startProfiler,
                                onStartLagDiagnostic = viewModel::startLagDiagnosticPreset,
                                onStopRecording = viewModel::stopProfiler,
                                onSelectSession = viewModel::selectSessionForAnalysis,
                                onDeleteSession = viewModel::deleteSession,
                                onExportCsv = { sid ->
                                    scope.launch {
                                        pendingExportText = viewModel.generateSessionCsv(sid)
                                        exportLauncher.launch("procpulse_session_$sid.csv")
                                    }
                                },
                                onExportJson = { sid ->
                                    scope.launch {
                                        pendingExportText = viewModel.generateSessionJson(sid)
                                        exportLauncher.launch("procpulse_session_$sid.json")
                                    }
                                },
                                onCopyReportMarkdown = { md ->
                                    viewModel.copyTextToClipboard("ProcPulse Lag Report", md)
                                },
                                onUpdateWatchdog = viewModel::updateWatchdogConfig,
                                onClearWatchdogEvents = viewModel::clearWatchdogEvents
                            )
                        }
                    }
                }
            }
        }
    }
}
