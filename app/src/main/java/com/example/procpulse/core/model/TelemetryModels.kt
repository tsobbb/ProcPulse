package com.example.procpulse.core.model

import androidx.compose.runtime.Immutable

enum class CapabilityTier(val level: Int, val shortLabel: String, val displayName: String) {
    STANDARD(0, "TIER 0", "Standard (Без привилегий)"),
    SHIZUKU(1, "TIER 1", "Shizuku / ADB (UID 2000)"),
    ROOT(2, "TIER 2", "Root (Superuser)")
}

enum class PreferredTierMode(val label: String) {
    AUTO("Авто"),
    STANDARD("Tier 0: Standard"),
    SHIZUKU("Tier 1: Shizuku"),
    ROOT("Tier 2: Root")
}

enum class ThemeMode(val label: String) {
    AMOLED_DARK("AMOLED Dark"),
    LIGHT("Светлая"),
    SYSTEM("Системная")
}

enum class AccentPreset(val label: String, val hex: Long) {
    SIGNAL_AMBER("Signal Amber", 0xFFF59E0B),
    PHOSPHOR_GREEN("Phosphor Green", 0xFF10B981),
    TECH_BLUE("Tech Blue", 0xFF3B82F6),
    MATERIAL_YOU("Material You", 0xFFF59E0B)
}

enum class CurrentCalibrationMode(val label: String) {
    AUTO("Авто (по статусу)"),
    MICRO_TO_MILLI("мкА → мА (÷ 1000)"),
    MILLI_TO_MILLI("мА → мА (1:1)"),
    DIVIDE_BY_10("Деци-мА (÷ 10)")
}

enum class SysfsAvailability(val label: String) {
    AVAILABLE("Доступно"),
    SELINUX_DENIED("Требуется Root (SELinux)"),
    NOT_IN_KERNEL("Нет в ядре")
}

enum class CpuClusterRole(val label: String) {
    PRIME("Prime"),
    PERF("Perf"),
    EFF("Eff")
}

@Immutable
data class CpuCoreSnapshot(
    val coreIndex: Int,
    val clusterRole: CpuClusterRole,
    val usagePercent: Float?, // null in Tier 0 when /proc/stat is blocked by SELinux
    val currentFreqMhz: Int,
    val maxFreqMhz: Int,
    val governor: String,
    val scalingMinMhz: Int = 0,
    val scalingMaxMhz: Int = maxFreqMhz,
    val cpuinfoMaxMhz: Int = maxFreqMhz,
    val isOnline: Boolean = true
)

@Immutable
data class CpuTelemetry(
    val totalUsagePercent: Float?, // null in Tier 0 if /proc/stat blocked
    val userPercent: Float?,
    val kernelPercent: Float?,
    val ioWaitPercent: Float?,
    val contextSwitchesPerSec: Long?,
    val interruptsPerSec: Long?,
    val activeGovernor: String,
    val cores: List<CpuCoreSnapshot>,
    val isUsageExact: Boolean,
    val procsRunning: Int? = null,
    val procsBlocked: Int? = null
)

@Immutable
data class GpuTelemetry(
    val rendererName: String,
    val vendorName: String,
    val usagePercent: Float?,
    val currentFreqMhz: Int?,
    val maxFreqMhz: Int?,
    val ddrFreqMhz: Int?,
    val gpuAvailability: SysfsAvailability = SysfsAvailability.AVAILABLE,
    val ddrAvailability: SysfsAvailability = SysfsAvailability.AVAILABLE
)

@Immutable
data class MemoryTelemetry(
    val totalMb: Long,
    val appAnonMb: Long,
    val cachedMb: Long,
    val kernelSlabMb: Long,
    val freeMb: Long,
    val availableMb: Long,
    val zramTotalMb: Long,
    val zramUsedMb: Long,
    val zramOrigMb: Long,
    val zramComprMb: Long,
    val zramRatio: Float,
    val psiSomeAvg10: Double?,
    val psiFullAvg10: Double?,
    val isDetailedExact: Boolean,
    val zramAvailability: SysfsAvailability = SysfsAvailability.AVAILABLE,
    val psiAvailability: SysfsAvailability = SysfsAvailability.AVAILABLE
)

@Immutable
data class BatteryTelemetry(
    val percent: Int,
    val currentMa: Float,
    val voltageMv: Int,
    val powerWatts: Float,
    val ratePctPerHour: Float,
    val chargeCounterMah: Int,
    val estimatedFullCapacityMah: Int,
    val designCapacityMah: Int,
    val isCharging: Boolean,
    val statusLabel: String,
    val tempCelsius: Float
)

enum class ThermalLevel(val label: String) {
    NONE("NONE"),
    LIGHT("LIGHT"),
    MODERATE("MODERATE"),
    SEVERE("SEVERE"),
    CRITICAL("CRITICAL"),
    EMERGENCY("EMERGENCY"),
    SHUTDOWN("SHUTDOWN")
}

@Immutable
data class ThermalZoneInfo(
    val id: Int,
    val rawType: String,
    val friendlyCategory: String,
    val tempCelsius: Float
)

@Immutable
data class ThermalTelemetry(
    val status: ThermalLevel,
    val headroom: Float?,
    val zones: List<ThermalZoneInfo>
)

@Immutable
data class NetworkTelemetry(
    val wifiRxBytesPerSec: Long,
    val wifiTxBytesPerSec: Long,
    val cellRxBytesPerSec: Long,
    val cellTxBytesPerSec: Long,
    val sessionWifiTotalBytes: Long,
    val sessionCellTotalBytes: Long,
    val activeSocketCount: Int?,
    val activeTransportLabel: String
)

@Immutable
data class SocketConnection(
    val protocol: String,
    val localAddress: String,
    val localPort: Int,
    val remoteAddress: String,
    val remotePort: Int,
    val state: String,
    val uid: Int,
    val ownerName: String
)

@Immutable
data class FpsTelemetry(
    val fps: Float?,
    val frameTimeMs: Float?,
    val jankCount: Int?,
    val available: Boolean,
    val sourceNote: String
)

// --- Block 3: Lag & Freeze Diagnostics Models ---

@Immutable
data class CpuCapClusterInfo(
    val role: CpuClusterRole,
    val scalingMinMhz: Int,
    val scalingMaxMhz: Int,
    val cpuinfoMaxMhz: Int,
    val governor: String,
    val onlineCores: Int,
    val totalCores: Int,
    val isCapped: Boolean
)

@Immutable
data class CpuSpikeEntry(
    val processName: String,
    val cpuPercent: Float,
    val detail: String
)

@Immutable
data class WakeupEntry(
    val packageName: String,
    val count: Int,
    val source: String // "alarm" / "job"
)

@Immutable
data class VmStatRates(
    val pswpinPerSec: Float = 0f,
    val pswpoutPerSec: Float = 0f,
    val pgmajfaultPerSec: Float = 0f,
    val allocstallPerSec: Float = 0f,
    val compactStallPerSec: Float = 0f,
    val availability: SysfsAvailability = SysfsAvailability.SELINUX_DENIED
)

@Immutable
data class LmkKillEvent(
    val timestampLabel: String,
    val timestampMs: Long,
    val packageName: String,
    val reason: String
)

@Immutable
data class DiskIoDiagnostics(
    val readKbPerSec: Float = 0f,
    val writeKbPerSec: Float = 0f,
    val ioBusyPercent: Float = 0f,
    val dataFreePercent: Float? = null,
    val isIoBottleneck: Boolean = false,
    val availability: SysfsAvailability = SysfsAvailability.SELINUX_DENIED
)

@Immutable
data class JankDiagnostics(
    val foregroundPackage: String? = null,
    val rendererType: String? = null,
    val refreshRateHz: Float = 60f,
    val fps: Float? = null,
    val frameTimeMs: Float? = null,
    val jankyFramesPercent: Float? = null,
    val totalFramesDelta: Int = 0,
    val jankyFramesDelta: Int = 0,
    val slowUiThreadPct: Float = 0f,
    val slowBitmapUploadPct: Float = 0f,
    val slowDrawCommandsPct: Float = 0f,
    val slowGpuDeadlinePct: Float = 0f,
    val dominantCause: String? = null,
    val sourceNote: String = "Требуется Tier 1/2"
)

@Immutable
data class LagDiagnosticsSnapshot(
    val clusterCaps: List<CpuCapClusterInfo> = emptyList(),
    val thermalServiceStatus: String? = null,
    val procsRunning: Int? = null,
    val procsBlocked: Int? = null,
    val runQueueAlert: Boolean = false,
    val minuteCpuSpikes: List<CpuSpikeEntry> = emptyList(),
    val topWakeups: List<WakeupEntry> = emptyList(),
    val vmStatRates: VmStatRates = VmStatRates(),
    val lmkKillsLast5Min: List<LmkKillEvent> = emptyList(),
    val diskIo: DiskIoDiagnostics = DiskIoDiagnostics(),
    val jank: JankDiagnostics = JankDiagnostics()
)

@Immutable
data class SystemSnapshot(
    val timestampMs: Long,
    val tier: CapabilityTier,
    val shizukuAvailable: Boolean,
    val shizukuAuthorized: Boolean,
    val rootAvailable: Boolean,
    val cpu: CpuTelemetry,
    val gpu: GpuTelemetry,
    val memory: MemoryTelemetry,
    val battery: BatteryTelemetry,
    val thermal: ThermalTelemetry,
    val network: NetworkTelemetry,
    val fps: FpsTelemetry,
    val topCpuConsumers: List<ProcessEntry> = emptyList(),
    val topRamConsumers: List<ProcessEntry> = emptyList(),
    val lagDiagnostics: LagDiagnosticsSnapshot? = null
)

enum class ProcessCategory(val label: String) {
    APPS("Приложения"),
    BACKGROUND("Фоновые / Кэш"),
    SYSTEM("Системные"),
    NATIVE("Нативные"),
    FROZEN("Замороженные")
}

enum class ProcessSortBy(val label: String) {
    CPU("CPU %"),
    RAM("RAM (PSS)"),
    RECENT("Недавняя активность"),
    NETWORK("Сеть"),
    OOM("OOM Score"),
    PID("PID"),
    UPTIME("Время работы"),
    NAME("Имя")
}

enum class ProcessStateTag(val tag: String) {
    FG("FG"),
    SVC("SVC"),
    BGD("BGD"),
    CCH("CCH"),
    STP("STP"),
    FRZ("FRZ")
}

@Immutable
data class ProcessEntry(
    val pid: Int?,
    val ppid: Int,
    val uid: Int,
    val packageName: String,
    val displayName: String,
    val category: ProcessCategory,
    val stateTag: ProcessStateTag,
    val cpuPercent: Float?,
    val ramPssMb: Float?,
    val ramUssMb: Float?,
    val netBytesTotal: Long,
    val oomScoreAdj: Int?,
    val uptimeSeconds: Long,
    val isProtected: Boolean,
    val isFrozenOrStopped: Boolean,
    val isEstimatedMemory: Boolean = false,
    val lastActiveTimestampMs: Long = 0L
) {
    fun processKey(): String = "${pid ?: -1}_${uid}_$packageName"
}

@Immutable
data class ProcessComponentInfo(
    val name: String,
    val type: String, // "SERVICE" or "RECEIVER"
    val enabled: Boolean
)

@Immutable
data class LogcatEntry(
    val timestamp: String,
    val level: Char,
    val tag: String,
    val message: String,
    val rawLine: String
)

@Immutable
data class ProcessDetail(
    val entry: ProcessEntry,
    val pssMb: Float?,
    val ussMb: Float?,
    val rssMb: Float?,
    val swapZramMb: Float?,
    val graphicsEglMb: Float?,
    val nativeHeapMb: Float?,
    val artHeapMb: Float?,
    val nice: Int?,
    val oomScoreAdj: Int?,
    val standbyBucket: String,
    val threadsCount: Int?,
    val fdCount: Int?,
    val affinityMask: String?,
    val runningServices: List<String>,
    val wakeLocks: List<String>,
    val jobsAndAlarms: List<String>,
    val sockets: List<SocketConnection>,
    val components: List<ProcessComponentInfo>,
    val isRestrictedBackground: Boolean,
    val tierLimitationReason: String?
)

enum class OverlayPreset(val label: String) {
    SINGLE_LINE("1-строчная полоса"),
    CORNER_SPARKLINE("Угловой спарклайн"),
    GAMING_HUD("Игровой HUD")
}

enum class OverlayWidgetMetric(val label: String) {
    FPS("FPS"),
    FRAME_TIME("FrameTime ms"),
    CPU("CPU %"),
    TEMP("Темп °C"),
    POWER("Питание мА/Вт"),
    RAM_ZRAM("RAM / ZRAM"),
    NET("Сеть Rx/Tx"),
    TOP_PROCESS("Топ-1 CPU процесс")
}

@Immutable
data class OverlayConfig(
    val enabled: Boolean = false,
    val preset: OverlayPreset = OverlayPreset.CORNER_SPARKLINE,
    val metrics: Set<OverlayWidgetMetric> = setOf(
        OverlayWidgetMetric.CPU,
        OverlayWidgetMetric.TEMP,
        OverlayWidgetMetric.POWER,
        OverlayWidgetMetric.RAM_ZRAM,
        OverlayWidgetMetric.FPS
    ),
    val passThrough: Boolean = false,
    val backgroundOpacity: Float = 0.85f,
    val fontSizeSp: Int = 11,
    val snapToEdges: Boolean = true
)

@Immutable
data class WatchdogConfig(
    val enabled: Boolean = false,
    val cpuThresholdPercent: Int = 45,
    val cpuDurationMinutes: Int = 3,
    val tempThresholdCelsius: Int = 44,
    val freeRamThresholdMb: Int = 350,
    val autoKillBlacklisted: Boolean = false,
    val screenOffFreezeDelayMinutes: Int = 5
)

@Immutable
data class ProfilerTopProcess(
    val pid: Int,
    val packageName: String,
    val displayName: String,
    val cpuPercent: Float,
    val ramMb: Float,
    val wakeLockCount: Int = 0
)

// --- Block 4: 1-Click Lag Root-Cause Report Models ---

enum class LagSeverity(val label: String) {
    OK("НОРМА"),
    WARNING("АНОМАЛИЯ"),
    CRITICAL("КРИТИЧНО")
}

@Immutable
data class LagCategoryVerdict(
    val categoryName: String,
    val severity: LagSeverity,
    val summary: String,
    val details: List<String>
)

@Immutable
data class AppJankSummary(
    val packageName: String,
    val avgFps: Float?,
    val avgJankyPercent: Float,
    val peakJankyPercent: Float,
    val dominantCause: String,
    val rendererType: String?,
    val sampleCount: Int
)

@Immutable
data class LagRootCauseReport(
    val sessionId: Long,
    val sessionTitle: String,
    val durationSec: Int,
    val sampleCount: Int,
    val overallVerdict: String,
    val categories: List<LagCategoryVerdict>,
    val appJankSummaries: List<AppJankSummary>,
    val markdownReport: String
)

@Immutable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.AMOLED_DARK,
    val accentPreset: AccentPreset = AccentPreset.SIGNAL_AMBER,
    val preferredTierMode: PreferredTierMode = PreferredTierMode.AUTO,
    val samplingIntervalMs: Long = 1000L,
    val invertCurrentSign: Boolean = false,
    val currentCalibrationMode: CurrentCalibrationMode = CurrentCalibrationMode.AUTO,
    val startWatchdogOnBoot: Boolean = false,
    val whitelistPackages: Set<String> = emptySet(),
    val blacklistPackages: Set<String> = emptySet(),
    val overlayConfig: OverlayConfig = OverlayConfig(),
    val watchdogConfig: WatchdogConfig = WatchdogConfig()
)
