package com.example.procpulse.core.bridge

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.view.Display
import com.example.procpulse.core.model.AppSettings
import com.example.procpulse.core.model.CapabilityTier
import com.example.procpulse.core.model.CpuCapClusterInfo
import com.example.procpulse.core.model.CpuClusterRole
import com.example.procpulse.core.model.CpuSpikeEntry
import com.example.procpulse.core.model.DiskIoDiagnostics
import com.example.procpulse.core.model.FpsTelemetry
import com.example.procpulse.core.model.JankDiagnostics
import com.example.procpulse.core.model.LagDiagnosticsSnapshot
import com.example.procpulse.core.model.LmkKillEvent
import com.example.procpulse.core.model.LogcatEntry
import com.example.procpulse.core.model.PreferredTierMode
import com.example.procpulse.core.model.ProcessCategory
import com.example.procpulse.core.model.ProcessDetail
import com.example.procpulse.core.model.ProcessEntry
import com.example.procpulse.core.model.ProcessStateTag
import com.example.procpulse.core.model.SocketConnection
import com.example.procpulse.core.model.SysfsAvailability
import com.example.procpulse.core.model.SystemSnapshot
import com.example.procpulse.core.model.ThermalZoneInfo
import com.example.procpulse.core.model.VmStatRates
import com.example.procpulse.core.model.WakeupEntry
import com.example.procpulse.core.nativebridge.NativeProcReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class UnifiedTelemetryBridge(
    private val context: Context,
    val packageCache: PackageCacheManager,
    val hardwareHelper: StandardHardwareHelper
) : SystemTelemetryBridge {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val rootWorker = PersistentRootWorker()
    private val shizukuMutex = Mutex()

    @Volatile
    override var activeTier: CapabilityTier = CapabilityTier.STANDARD
        private set

    @Volatile
    private var shizukuService: ITelemetryUserService? = null

    @Volatile
    private var shizukuBound = false

    @Volatile
    private var shizukuExplicitlyUnbound = false

    @Volatile
    private var rootCheckedOnce = false

    @Volatile
    private var rootAvailableFlag = false

    @Volatile
    private var lastForegroundPkg: String = ""

    // Cached top consumers between Monitor tab throttled refreshes
    @Volatile
    private var cachedTopCpu: List<ProcessEntry> = emptyList()

    @Volatile
    private var cachedTopRam: List<ProcessEntry> = emptyList()

    // Previous process CPU jiffies map for Root fallback: pid -> (procTicks, totalSystemTicks)
    private val prevProcCpu = ConcurrentHashMap<Int, Pair<Long, Long>>()
    private var prevSysTotalJiffies = 0L

    // Block 3 vmstat / diskstats / gfxinfo / slow dumpsys delta caches
    private var prevVmTimeMs = 0L
    private var prevPswpin = 0L
    private var prevPswpout = 0L
    private var prevPgmajfault = 0L
    private var prevAllocstall = 0L
    private var prevCompactStall = 0L

    private var prevDiskTimeMs = 0L
    private var prevDiskReadSectors = 0L
    private var prevDiskWriteSectors = 0L
    private var prevDiskIoTicks = 0L

    private var prevGfxPkg = ""
    private var prevGfxTotal = 0
    private var prevGfxJanky = 0
    private var prevGfxSlowUi = 0
    private var prevGfxSlowBmp = 0
    private var prevGfxSlowDraw = 0
    private var prevGfxSlowGpu = 0

    private var lastSlowDumpsysMs = 0L
    private var cachedCpuSpikes: List<CpuSpikeEntry> = emptyList()
    private var cachedWakeups: List<WakeupEntry> = emptyList()
    private val recentLmkKills = ArrayList<LmkKillEvent>()
    private var lastLagSnapshot: LagDiagnosticsSnapshot? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, TelemetryUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("telemetry_service")
            .debuggable(false)
            .version(2)
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuService = ITelemetryUserService.Stub.asInterface(service)
            shizukuBound = shizukuService != null
            shizukuExplicitlyUnbound = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuService = null
            shizukuBound = false
        }
    }

    private fun isShizukuBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun isShizukuAuthorized(): Boolean {
        if (!isShizukuBinderAlive()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    override suspend fun requestShizukuPermission(): Boolean = withContext(Dispatchers.Main) {
        if (!isShizukuBinderAlive()) return@withContext false
        if (isShizukuAuthorized()) {
            bindShizukuServiceIfNeeded()
            return@withContext true
        }
        val deferred = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            deferred.complete(grantResult == PackageManager.PERMISSION_GRANTED)
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(1001)
        }.onFailure {
            Shizuku.removeRequestPermissionResultListener(listener)
            deferred.complete(false)
        }
        val granted = withTimeoutOrNull(15_000L) { deferred.await() } ?: false
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
        if (granted) {
            bindShizukuServiceIfNeeded()
        }
        granted
    }

    override suspend fun checkRootAccess(): Boolean {
        val ok = rootWorker.ensureConnected()
        rootCheckedOnce = true
        rootAvailableFlag = ok
        return ok
    }

    override fun unbindShizukuService() {
        shizukuExplicitlyUnbound = true
        shizukuService = null
        shizukuBound = false
        runCatching {
            Shizuku.unbindUserService(userServiceArgs, shizukuConnection, true)
        }
    }

    override suspend fun reconnectShizukuIfAlive(): Boolean = withContext(Dispatchers.IO) {
        shizukuExplicitlyUnbound = false
        if (!isShizukuAuthorized()) return@withContext false
        bindShizukuServiceWithRetry(maxAttempts = 2) != null
    }

    private suspend fun bindShizukuServiceIfNeeded(): ITelemetryUserService? {
        if (!isShizukuAuthorized()) return null
        shizukuService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        return shizukuMutex.withLock {
            shizukuService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            withContext(Dispatchers.Main) {
                runCatching {
                    Shizuku.bindUserService(userServiceArgs, shizukuConnection)
                }
            }
            repeat(10) {
                shizukuService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
                delay(50L)
            }
            shizukuService?.takeIf { it.asBinder().isBinderAlive }
        }
    }

    private suspend fun bindShizukuServiceWithRetry(maxAttempts: Int = 2): ITelemetryUserService? {
        repeat(maxAttempts) { attempt ->
            val svc = bindShizukuServiceIfNeeded()
            if (svc != null) return svc
            shizukuService = null
            shizukuBound = false
            if (attempt + 1 < maxAttempts) {
                delay(150L)
            }
        }
        return null
    }

    override suspend fun refreshCapabilities(settings: AppSettings): CapabilityTier = withContext(Dispatchers.IO) {
        val shizukuAuth = isShizukuAuthorized()
        val chosen = when (settings.preferredTierMode) {
            PreferredTierMode.STANDARD -> CapabilityTier.STANDARD
            PreferredTierMode.SHIZUKU -> {
                if (shizukuAuth && bindShizukuServiceWithRetry(2) != null) CapabilityTier.SHIZUKU else CapabilityTier.STANDARD
            }
            PreferredTierMode.ROOT -> {
                if (rootWorker.ensureConnected()) {
                    rootAvailableFlag = true
                    CapabilityTier.ROOT
                } else CapabilityTier.STANDARD
            }
            PreferredTierMode.AUTO -> {
                if (rootCheckedOnce && rootAvailableFlag && rootWorker.isConnected) {
                    CapabilityTier.ROOT
                } else if (shizukuAuth && bindShizukuServiceWithRetry(2) != null) {
                    CapabilityTier.SHIZUKU
                } else {
                    CapabilityTier.STANDARD
                }
            }
        }
        activeTier = chosen
        chosen
    }

    override suspend fun controlTimestats(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val action = if (enable) 1 else 3
        when (activeTier) {
            CapabilityTier.SHIZUKU -> {
                val svc = bindShizukuServiceWithRetry(2) ?: return@withContext false
                runCatching {
                    svc.readLagDiagnosticsSnapshot(false, lastForegroundPkg, action)
                    true
                }.getOrDefault(false)
            }
            CapabilityTier.ROOT -> {
                val cmd = if (enable) {
                    "dumpsys SurfaceFlinger --timestats -enable -clear"
                } else {
                    "dumpsys SurfaceFlinger --timestats -disable"
                }
                rootWorker.exec(cmd).isSuccess
            }
            CapabilityTier.STANDARD -> false
        }
    }

    override suspend fun collectSnapshot(
        settings: AppSettings,
        includeTopConsumers: Boolean,
        includeLagDiagnostics: Boolean
    ): SystemSnapshot = withContext(Dispatchers.IO) {
        val tier = refreshCapabilities(settings)
        val shizukuAlive = isShizukuBinderAlive()
        val shizukuAuth = isShizukuAuthorized()

        var statOverride: String? = null
        var memOverride: String? = null
        var zramOverride: String? = null
        var psiOverride: String? = null
        var zramAvailOverride: SysfsAvailability? = null
        var psiAvailOverride: SysfsAvailability? = null
        var gpuBusyOverride: Float? = null
        var gpuClkOverride: Int? = null
        var gpuMaxOverride: Int? = null
        var ddrFreqOverride: Int? = null
        var gpuAvailOverride: SysfsAvailability? = null
        var ddrAvailOverride: SysfsAvailability? = null
        var clusterCapsOverride: List<CpuCapClusterInfo>? = null
        var zonesOverride: List<ThermalZoneInfo>? = null
        var fpsTelemetry = FpsTelemetry(null, null, null, false, "Требуется Shizuku или Root")
        var socketCount: Int? = null
        var vmStatOverride: String? = null
        var diskStatsOverride: String? = null
        var lagDiagnostics: LagDiagnosticsSnapshot? = null

        if (tier == CapabilityTier.ROOT) {
            val res = rootWorker.exec(
                "cat /proc/stat; echo '---MEM---'; cat /proc/meminfo; echo '---PSI---'; cat /proc/pressure/memory 2>&1; " +
                    "echo '---ZRAM---'; cat /sys/block/zram0/mm_stat 2>&1; echo '---VM---'; " +
                    "grep -E '^(pswpin|pswpout|pgmajfault|allocstall|compact_stall)' /proc/vmstat 2>/dev/null; echo '---DISK---'; " +
                    "grep -E ' (mmcblk0|sda|nvme0n1) ' /proc/diskstats 2>/dev/null; echo '---GPU---'; " +
                    "cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null; cat /sys/class/kgsl/kgsl-3d0/clock_mhz 2>/dev/null; " +
                    "echo '---FPS---'; dumpsys SurfaceFlinger --latency 2>/dev/null | tail -n 30"
            )
            if (res.isFailure) {
                rootAvailableFlag = false
                activeTier = CapabilityTier.STANDARD
            } else {
                val raw = res.getOrDefault("")
                statOverride = raw.substringBefore("---MEM---").trim()
                val afterMem = raw.substringAfter("---MEM---", "")
                memOverride = afterMem.substringBefore("---PSI---").trim()
                val afterPsi = afterMem.substringAfter("---PSI---", "")
                val psiRaw = afterPsi.substringBefore("---ZRAM---").trim()
                val psiParsed = interpretCatOutcome(psiRaw)
                psiOverride = psiParsed.first
                psiAvailOverride = psiParsed.second

                val afterZram = afterPsi.substringAfter("---ZRAM---", "")
                val zramRaw = afterZram.substringBefore("---VM---").trim()
                val zramParsed = interpretCatOutcome(zramRaw)
                zramOverride = zramParsed.first
                zramAvailOverride = zramParsed.second

                val afterVm = afterZram.substringAfter("---VM---", "")
                vmStatOverride = afterVm.substringBefore("---DISK---").trim()
                val afterDisk = afterVm.substringAfter("---DISK---", "")
                diskStatsOverride = afterDisk.substringBefore("---GPU---").trim()

                val afterGpu = afterDisk.substringAfter("---GPU---", "")
                val gpuPart = afterGpu.substringBefore("---FPS---").trim().lines()
                gpuBusyOverride = gpuPart.getOrNull(0)?.replace("%", "")?.trim()?.toFloatOrNull()
                gpuClkOverride = gpuPart.getOrNull(1)?.trim()?.toIntOrNull()
                gpuAvailOverride = if (gpuBusyOverride != null || gpuClkOverride != null) {
                    SysfsAvailability.AVAILABLE
                } else SysfsAvailability.NOT_IN_KERNEL

                val fpsPart = afterGpu.substringAfter("---FPS---", "")
                fpsTelemetry = parseSurfaceFlingerLatency(fpsPart, "Root SurfaceFlinger")
                socketCount = collectSockets(null).size
            }
        } else if (tier == CapabilityTier.SHIZUKU) {
            var svc = shizukuService
            var jsonStr: String? = null
            for (attempt in 0 until 2) {
                if (svc == null || !svc.asBinder().isBinderAlive) {
                    svc = bindShizukuServiceWithRetry(2)
                }
                if (svc == null) break
                try {
                    jsonStr = svc.readSystemMetricsSnapshot()
                    break
                } catch (_: DeadObjectException) {
                    shizukuService = null
                    shizukuBound = false
                    svc = null
                } catch (_: RemoteException) {
                    shizukuService = null
                    shizukuBound = false
                    svc = null
                }
            }

            if (jsonStr == null) {
                if (!isShizukuBinderAlive()) {
                    activeTier = CapabilityTier.STANDARD
                }
            } else {
                val obj = JSONObject(jsonStr)
                statOverride = obj.optString("stat").takeIf { it.isNotEmpty() }
                memOverride = obj.optString("meminfo").takeIf { it.isNotEmpty() }
                vmStatOverride = obj.optString("vmstat").takeIf { it.isNotEmpty() }
                diskStatsOverride = obj.optString("diskstats").takeIf { it.isNotEmpty() }

                psiAvailOverride = parseStatusTag(obj.optString("psiStatus"))
                psiOverride = obj.optString("psi").takeIf { it.isNotEmpty() }

                zramAvailOverride = parseStatusTag(obj.optString("zramStatus"))
                zramOverride = obj.optString("zram").takeIf { it.isNotEmpty() }

                gpuAvailOverride = parseStatusTag(obj.optString("gpuStatus"))
                gpuBusyOverride = obj.optString("gpuBusy").replace("%", "").trim().toFloatOrNull()
                gpuClkOverride = obj.optString("gpuClock").trim().toIntOrNull()
                gpuMaxOverride = obj.optString("gpuMax").trim().toIntOrNull()

                ddrAvailOverride = parseStatusTag(obj.optString("ddrStatus"))
                ddrFreqOverride = obj.optInt("ddrFreq", -1).takeIf { it > 0 }

                val capsArr = obj.optJSONArray("cpuCaps")
                if (capsArr != null && capsArr.length() > 0) {
                    clusterCapsOverride = parseClusterCaps(capsArr)
                }

                val thermArr = obj.optJSONArray("thermals")
                if (thermArr != null && thermArr.length() > 0) {
                    val mapped = mutableListOf<ThermalZoneInfo>()
                    for (i in 0 until thermArr.length()) {
                        val t = thermArr.getJSONObject(i)
                        val rawType = t.optString("type")
                        val rawVal = t.optLong("temp")
                        val tempC = if (abs(rawVal) > 200) rawVal / 1000f else rawVal.toFloat()
                        val friendly = hardwareHelper.mapThermalType(rawType)
                        if (friendly != null && tempC in -20f..130f) {
                            mapped.add(ThermalZoneInfo(t.optInt("id"), rawType, friendly, tempC))
                        }
                    }
                    if (mapped.isNotEmpty()) {
                        zonesOverride = mapped.distinctBy { it.friendlyCategory + "_" + it.rawType }.take(12)
                    }
                }

                val sfOut = runCatching { svc?.readSurfaceFlingerFps().orEmpty() }.getOrDefault("")
                fpsTelemetry = parseSurfaceFlingerLatency(sfOut, "Shizuku SurfaceFlinger")
                socketCount = collectSockets(null).size
            }
        }

        val cpu = hardwareHelper.readCpuTelemetry(statOverride, clusterCapsOverride)
        val gpu = hardwareHelper.readGpuTelemetry(
            overrideBusyPct = gpuBusyOverride,
            overrideCurMhz = gpuClkOverride,
            overrideMaxMhz = gpuMaxOverride,
            overrideDdrMhz = ddrFreqOverride,
            overrideGpuAvail = gpuAvailOverride,
            overrideDdrAvail = ddrAvailOverride
        )
        val memory = hardwareHelper.readMemoryTelemetry(
            overrideMemInfoText = memOverride,
            overrideZramText = zramOverride,
            overridePsiText = psiOverride,
            overrideZramAvail = zramAvailOverride,
            overridePsiAvail = psiAvailOverride
        )
        val battery = hardwareHelper.readBatteryTelemetry(settings)
        val thermal = hardwareHelper.readThermalTelemetry(battery.tempCelsius, zonesOverride)
        val network = hardwareHelper.readNetworkTelemetry(socketCount)

        if (includeTopConsumers) {
            val procs = collectTopConsumersForDashboard(settings, limit = 10)
            cachedTopCpu = procs
                .filter { (it.cpuPercent ?: 0f) > 0f }
                .sortedByDescending { it.cpuPercent ?: 0f }
                .take(5)
            cachedTopRam = procs
                .filter { (it.ramPssMb ?: 0f) > 0f }
                .sortedByDescending { it.ramPssMb ?: 0f }
                .take(5)
        }

        if (includeLagDiagnostics) {
            lagDiagnostics = collectLagDiagnostics(
                cpu = cpu,
                clusterCaps = clusterCapsOverride ?: deriveClusterCapsFromCores(cpu),
                vmStatOverride = vmStatOverride,
                diskStatsOverride = diskStatsOverride,
                fpsTelemetry = fpsTelemetry
            )
            lastLagSnapshot = lagDiagnostics
        } else {
            lagDiagnostics = lastLagSnapshot
        }

        SystemSnapshot(
            timestampMs = System.currentTimeMillis(),
            tier = activeTier,
            shizukuAvailable = shizukuAlive,
            shizukuAuthorized = shizukuAuth,
            rootAvailable = rootAvailableFlag,
            cpu = cpu,
            gpu = gpu,
            memory = memory,
            battery = battery,
            thermal = thermal,
            network = network,
            fps = fpsTelemetry,
            topCpuConsumers = cachedTopCpu,
            topRamConsumers = cachedTopRam,
            lagDiagnostics = lagDiagnostics
        )
    }

    private suspend fun collectTopConsumersForDashboard(settings: AppSettings, limit: Int): List<ProcessEntry> {
        return when (activeTier) {
            CapabilityTier.SHIZUKU -> {
                val svc = shizukuService ?: return hardwareHelper.collectTier0Processes(settings)
                runCatching {
                    val obj = JSONObject(svc.listProcessesSnapshot(limit))
                    val fgPkg = obj.optString("fgPkg")
                    if (fgPkg.isNotBlank()) lastForegroundPkg = fgPkg
                    val combined = ArrayList<ProcessEntry>(limit * 2)
                    obj.optJSONArray("topCpu")?.let { combined.addAll(parseProcJsonArray(it, fgPkg, settings)) }
                    obj.optJSONArray("topRam")?.let { ramArr ->
                        val parsed = parseProcJsonArray(ramArr, fgPkg, settings)
                        val seen = combined.map { it.pid }.toHashSet()
                        parsed.filterTo(combined) { it.pid !in seen }
                    }
                    if (combined.isEmpty()) {
                        val topFb = obj.optString("topFallback")
                        if (topFb.isNotBlank()) combined.addAll(parseTopFallback(topFb, fgPkg, settings))
                    }
                    combined
                }.getOrElse { hardwareHelper.collectTier0Processes(settings) }
            }
            CapabilityTier.ROOT -> collectPrivilegedProcessesViaRoot(settings)
            CapabilityTier.STANDARD -> hardwareHelper.collectTier0Processes(settings)
        }
    }

    override suspend fun collectProcesses(
        settings: AppSettings,
        category: ProcessCategory?
    ): List<ProcessEntry> = withContext(Dispatchers.IO) {
        val list = when (activeTier) {
            CapabilityTier.ROOT -> collectPrivilegedProcessesViaRoot(settings)
            CapabilityTier.SHIZUKU -> collectPrivilegedProcessesViaShizuku(settings)
            CapabilityTier.STANDARD -> hardwareHelper.collectTier0Processes(settings)
        }
        if (category == null) list else list.filter { it.category == category }
    }

    private suspend fun collectPrivilegedProcessesViaShizuku(settings: AppSettings): List<ProcessEntry> {
        val svc = bindShizukuServiceWithRetry(2) ?: return hardwareHelper.collectTier0Processes(settings)
        return try {
            val rawJson = svc.listProcessesSnapshot(0)
            val obj = JSONObject(rawJson)
            val fgPkg = obj.optString("fgPkg")
            if (fgPkg.isNotBlank()) lastForegroundPkg = fgPkg

            val arr = obj.optJSONArray("procs")
            if (arr != null && arr.length() > 0) {
                val parsed = parseProcJsonArray(arr, fgPkg, settings)
                val topFb = obj.optString("topFallback")
                if (topFb.isNotBlank() && parsed.none { (it.cpuPercent ?: 0f) > 0f }) {
                    val topCpuByPid = parseTopCpuMap(topFb)
                    parsed.map { p ->
                        val c = p.pid?.let { topCpuByPid[it] }
                        if (c != null) p.copy(cpuPercent = c) else p
                    }
                } else {
                    parsed
                }
            } else {
                val psFb = obj.optString("psFallback")
                if (psFb.isNotBlank()) {
                    parsePsTable(psFb, settings, fgPkg)
                } else {
                    hardwareHelper.collectTier0Processes(settings)
                }
            }
        } catch (_: RemoteException) {
            val rebound = bindShizukuServiceWithRetry(2)
            if (rebound == null && !isShizukuBinderAlive()) {
                shizukuService = null
                activeTier = CapabilityTier.STANDARD
            }
            hardwareHelper.collectTier0Processes(settings)
        }
    }

    private suspend fun collectPrivilegedProcessesViaRoot(settings: AppSettings): List<ProcessEntry> {
        val res = rootWorker.exec("ps -A -o PID,PPID,UID,NI,RSS,S,ELAPSED,ARGS")
        if (res.isFailure) {
            rootAvailableFlag = false
            activeTier = CapabilityTier.STANDARD
            return hardwareHelper.collectTier0Processes(settings)
        }
        val psOutput = res.getOrDefault("")
        return parsePsTable(psOutput, settings, lastForegroundPkg)
    }

    private fun parseProcJsonArray(
        arr: JSONArray,
        fgPkg: String,
        settings: AppSettings
    ): List<ProcessEntry> {
        packageCache.refreshIfNeeded()
        val result = ArrayList<ProcessEntry>(arr.length())
        val myPid = Process.myPid()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val pid = item.optInt("pid", -1).takeIf { it > 0 } ?: continue
            val ppid = item.optInt("ppid", 0)
            val uid = item.optInt("uid", 0)
            val comm = item.optString("comm")
            val cmdline = item.optString("cmd")
            val state = item.optString("st", "S")
            val rssKb = item.optLong("rss", 0L)
            val oomAdj = if (item.has("oom")) item.optInt("oom") else null
            val cpuPct = if (item.has("cpu")) item.optDouble("cpu").toFloat() else null
            val uptimeSec = item.optLong("up", 0L)

            val entry = buildClassifiedProcessEntry(
                pid = pid,
                ppid = ppid,
                uid = uid,
                cmdline = cmdline,
                comm = comm,
                stateCode = state,
                rssKb = rssKb,
                oomAdj = oomAdj,
                cpuPct = cpuPct,
                uptimeSec = uptimeSec,
                fgPkg = fgPkg,
                myPid = myPid,
                settings = settings
            )
            result.add(entry)
        }
        return result
    }

    private fun parsePsTable(
        psOut: String,
        settings: AppSettings,
        fgPkg: String
    ): List<ProcessEntry> {
        packageCache.refreshIfNeeded()
        val sysTotal = hardwareHelper.getLastProcStatTotalJiffies()
        val dSysTotal = (sysTotal - prevSysTotalJiffies).coerceAtLeast(1L)
        if (sysTotal > 0L) prevSysTotalJiffies = sysTotal

        val result = ArrayList<ProcessEntry>(256)
        val procDetailBuf = LongArray(12)
        val myPid = Process.myPid()

        for (line in psOut.lineSequence().drop(1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("\\s+"), limit = 8)
            if (parts.size < 8) continue

            val pid = parts[0].toIntOrNull() ?: continue
            val ppid = parts[1].toIntOrNull() ?: 0
            val uid = parts[2].toIntOrNull() ?: 0
            val rssKb = parts[4].toLongOrNull() ?: 0L
            val statCode = parts[5]
            val elapsedStr = parts[6]
            val cmd = parts[7]

            var cpuPct: Float? = null
            var pssKbOverride = rssKb
            var ussMb: Float? = null
            var oomAdj: Int? = null

            if (activeTier == CapabilityTier.ROOT && NativeProcReader.readProcessDetail(pid, procDetailBuf)) {
                val ticks = procDetailBuf[1] + procDetailBuf[2]
                val prev = prevProcCpu[pid]
                if (prev != null && sysTotal > prev.second) {
                    val dProc = (ticks - prev.first).coerceAtLeast(0L)
                    cpuPct = ((dProc.toFloat() / dSysTotal.toFloat()) * 100f).coerceIn(0f, 100f)
                }
                prevProcCpu[pid] = ticks to sysTotal
                if (procDetailBuf[9] > 0) pssKbOverride = procDetailBuf[9]
                if (procDetailBuf[10] > 0) ussMb = procDetailBuf[10] / 1024f
                if (procDetailBuf[7] != Long.MIN_VALUE) oomAdj = procDetailBuf[7].toInt()
            }

            result.add(
                buildClassifiedProcessEntry(
                    pid = pid,
                    ppid = ppid,
                    uid = uid,
                    cmdline = cmd,
                    comm = cmd.substringAfterLast('/').substringBefore(' '),
                    stateCode = statCode,
                    rssKb = pssKbOverride,
                    oomAdj = oomAdj,
                    cpuPct = cpuPct,
                    uptimeSec = parseElapsedSeconds(elapsedStr),
                    fgPkg = fgPkg,
                    myPid = myPid,
                    settings = settings,
                    ussMb = ussMb
                )
            )
        }
        return result
    }

    private fun buildClassifiedProcessEntry(
        pid: Int,
        ppid: Int,
        uid: Int,
        cmdline: String,
        comm: String,
        stateCode: String,
        rssKb: Long,
        oomAdj: Int?,
        cpuPct: Float?,
        uptimeSec: Long,
        fgPkg: String,
        myPid: Int,
        settings: AppSettings,
        ussMb: Float? = null
    ): ProcessEntry {
        val firstToken = cmdline.trim().substringBefore(' ').ifBlank { comm }
        val isSystemServer = firstToken == "system_server" || comm == "system_server"

        val resolvedPkg: String
        val displayName: String
        val meta: CachedAppMeta?

        if (isSystemServer) {
            resolvedPkg = "system_server"
            displayName = "Система Android (system_server)"
            meta = null
        } else {
            val basePkg = firstToken.substringBefore(':')
            val directMeta = if (basePkg.contains('.')) packageCache.getMeta(basePkg) else null
            // Strict UID fallback ONLY for user app UIDs (>= 10000), never for shared UID < 10000
            meta = directMeta ?: if (uid % 100000 >= 10000 && !firstToken.startsWith("/")) {
                packageCache.getUserPackageForUid(uid)?.let { packageCache.getMeta(it) }
            } else null

            resolvedPkg = meta?.packageName ?: firstToken.ifBlank { comm }
            displayName = meta?.displayName ?: comm.ifBlank {
                firstToken.substringAfterLast('/').ifBlank { "pid_$pid" }
            }
        }

        val isStopped = stateCode.contains('T')
        val isUserUid = uid % 100000 >= 10000
        val isNative = !isSystemServer && meta == null && (!isUserUid || firstToken.startsWith("[") || firstToken.startsWith("/"))

        val stateTag = when {
            isStopped -> ProcessStateTag.STP
            (fgPkg.isNotBlank() && resolvedPkg == fgPkg) ||
                (oomAdj != null && oomAdj <= 0) ||
                stateCode.contains('R') -> ProcessStateTag.FG
            isNative || (oomAdj != null && oomAdj in 1..250) -> ProcessStateTag.SVC
            oomAdj != null && oomAdj >= 900 -> ProcessStateTag.CCH
            else -> ProcessStateTag.BGD
        }

        val category = when {
            isStopped -> ProcessCategory.FROZEN
            isNative -> ProcessCategory.NATIVE
            isSystemServer || meta?.isSystem == true -> ProcessCategory.SYSTEM
            else -> ProcessCategory.APPS // Keep ALL running user apps visible on Apps tab (FG/SVC/BGD/CCH)
        }

        val pssMb = when {
            pid == myPid -> hardwareHelper.getSelfPssMb()
            rssKb > 0 -> rssKb / 1024f
            else -> null
        }

        return ProcessEntry(
            pid = pid,
            ppid = ppid,
            uid = uid,
            packageName = resolvedPkg,
            displayName = displayName,
            category = category,
            stateTag = stateTag,
            cpuPercent = cpuPct,
            ramPssMb = pssMb,
            ramUssMb = ussMb,
            netBytesTotal = 0L,
            oomScoreAdj = oomAdj,
            uptimeSeconds = uptimeSec,
            isProtected = packageCache.isProtectedPackage(resolvedPkg, settings.whitelistPackages),
            isFrozenOrStopped = isStopped,
            isEstimatedMemory = pid != myPid && ussMb == null && pssMb != null
        )
    }

    private fun parseTopCpuMap(topOut: String): Map<Int, Float> {
        val map = HashMap<Int, Float>()
        val cores = hardwareHelper.coreCount.toFloat().coerceAtLeast(1f)
        for (line in topOut.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val rawCpu = parts[2].replace("%", "").toFloatOrNull() ?: continue
            map[pid] = (rawCpu / cores).coerceIn(0f, 100f)
        }
        return map
    }

    private fun parseTopFallback(topOut: String, fgPkg: String, settings: AppSettings): List<ProcessEntry> {
        packageCache.refreshIfNeeded()
        val cores = hardwareHelper.coreCount.toFloat().coerceAtLeast(1f)
        val myPid = Process.myPid()
        val list = mutableListOf<ProcessEntry>()
        for (line in topOut.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            if (parts.size < 6) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val uid = parts[1].toIntOrNull() ?: 0
            val cpu = ((parts[2].replace("%", "").toFloatOrNull() ?: 0f) / cores).coerceIn(0f, 100f)
            val resKb = parseMemoryStringKb(parts[3])
            val st = parts[4]
            val args = parts[5]
            list.add(
                buildClassifiedProcessEntry(
                    pid = pid,
                    ppid = 0,
                    uid = uid,
                    cmdline = args,
                    comm = args.substringAfterLast('/').substringBefore(' '),
                    stateCode = st,
                    rssKb = resKb,
                    oomAdj = null,
                    cpuPct = cpu,
                    uptimeSec = 0L,
                    fgPkg = fgPkg,
                    myPid = myPid,
                    settings = settings
                )
            )
        }
        return list
    }

    private fun parseMemoryStringKb(raw: String): Long {
        val s = raw.trim().uppercase(Locale.US)
        val num = s.dropLastWhile { !it.isDigit() && it != '.' }.toFloatOrNull() ?: return 0L
        return when {
            s.endsWith("G") -> (num * 1024f * 1024f).toLong()
            s.endsWith("M") -> (num * 1024f).toLong()
            s.endsWith("K") -> num.toLong()
            else -> num.toLong()
        }
    }

    // --- Block 3: Lag Diagnostics Assembly ---

    private suspend fun collectLagDiagnostics(
        cpu: com.example.procpulse.core.model.CpuTelemetry,
        clusterCaps: List<CpuCapClusterInfo>,
        vmStatOverride: String?,
        diskStatsOverride: String?,
        fpsTelemetry: FpsTelemetry
    ): LagDiagnosticsSnapshot {
        val now = System.currentTimeMillis()
        val includeSlow = (now - lastSlowDumpsysMs) >= 18_000L
        if (includeSlow) lastSlowDumpsysMs = now

        val vmRates = computeVmStatRates(vmStatOverride, now)
        val diskIo = computeDiskIoDiagnostics(diskStatsOverride, cpu.ioWaitPercent, now)

        var thermalStatusStr: String? = null
        var fgPkg = lastForegroundPkg
        var gfxRaw = ""
        var timestatsRaw = ""

        if (activeTier == CapabilityTier.SHIZUKU) {
            val svc = shizukuService
            if (svc != null) {
                runCatching {
                    val jsonStr = svc.readLagDiagnosticsSnapshot(includeSlow, lastForegroundPkg, 2)
                    val obj = JSONObject(jsonStr)
                    fgPkg = obj.optString("fgPackage").ifBlank { fgPkg }
                    if (fgPkg.isNotBlank()) lastForegroundPkg = fgPkg
                    gfxRaw = obj.optString("gfxinfo")
                    timestatsRaw = obj.optString("timestats")
                    thermalStatusStr = parseThermalServiceStatus(obj.optString("thermalservice"))

                    if (includeSlow) {
                        val cpuInfo = obj.optString("cpuinfo")
                        if (cpuInfo.isNotBlank()) cachedCpuSpikes = parseDumpsysCpuInfo(cpuInfo)
                        val wakeups = parseWakeups(obj.optString("alarms"), obj.optString("jobs"))
                        cachedWakeups = wakeups
                        val lmkRaw = obj.optString("lmkLogcat")
                        if (lmkRaw.isNotBlank()) ingestLmkEvents(lmkRaw, now)
                    }
                }
            }
        } else if (activeTier == CapabilityTier.ROOT) {
            val cmds = StringBuilder()
            cmds.append("dumpsys activity top | grep -E 'ACTIVITY ' | head -n 3; echo '---THERM---'; ")
            cmds.append("dumpsys thermalservice | head -n 25; echo '---GFX---'; ")
            if (fgPkg.isNotBlank()) cmds.append("dumpsys gfxinfo $fgPkg | head -n 55; ")
            if (includeSlow) {
                cmds.append("echo '---CPUINFO---'; dumpsys cpuinfo | head -n 30; ")
                cmds.append("echo '---LMK---'; logcat -b events -d -v epoch -t 150 | grep -E 'am_kill|lmkd'")
            }
            val raw = rootWorker.exec(cmds.toString()).getOrDefault("")
            val topPart = raw.substringBefore("---THERM---")
            val detected = Regex("""ACTIVITY\s+([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/""").find(topPart)?.groupValues?.getOrNull(1)
            if (!detected.isNullOrBlank()) {
                fgPkg = detected
                lastForegroundPkg = detected
            }
            val afterTherm = raw.substringAfter("---THERM---", "")
            thermalStatusStr = parseThermalServiceStatus(afterTherm.substringBefore("---GFX---"))
            val afterGfx = afterTherm.substringAfter("---GFX---", "")
            gfxRaw = afterGfx.substringBefore("---CPUINFO---")
            if (includeSlow) {
                val afterCpu = afterGfx.substringAfter("---CPUINFO---", "")
                val cpuInfo = afterCpu.substringBefore("---LMK---")
                if (cpuInfo.isNotBlank()) cachedCpuSpikes = parseDumpsysCpuInfo(cpuInfo)
                val lmkRaw = afterCpu.substringAfter("---LMK---", "")
                if (lmkRaw.isNotBlank()) ingestLmkEvents(lmkRaw, now)
            }
        }

        // Prune LMK events older than 5 min
        recentLmkKills.removeAll { now - it.timestampMs > 300_000L }

        val refreshRateHz = queryDisplayRefreshRateHz()
        val jank = buildJankDiagnostics(fgPkg, gfxRaw, timestatsRaw, fpsTelemetry, refreshRateHz)

        val procsRunning = cpu.procsRunning
        val procsBlocked = cpu.procsBlocked
        val runQueueAlert = (procsRunning ?: 0) > hardwareHelper.coreCount * 2 || (procsBlocked ?: 0) >= 2

        return LagDiagnosticsSnapshot(
            clusterCaps = clusterCaps,
            thermalServiceStatus = thermalStatusStr,
            procsRunning = procsRunning,
            procsBlocked = procsBlocked,
            runQueueAlert = runQueueAlert,
            minuteCpuSpikes = cachedCpuSpikes,
            topWakeups = cachedWakeups,
            vmStatRates = vmRates,
            lmkKillsLast5Min = recentLmkKills.toList(),
            diskIo = diskIo,
            jank = jank
        )
    }

    private fun computeVmStatRates(vmOverride: String?, nowMs: Long): VmStatRates {
        val values = LongArray(5)
        val readOk = if (!vmOverride.isNullOrBlank()) {
            parseVmStatText(vmOverride, values)
        } else {
            NativeProcReader.readVmStat(values)
        }
        if (!readOk) {
            return VmStatRates(availability = if (activeTier == CapabilityTier.STANDARD) SysfsAvailability.SELINUX_DENIED else SysfsAvailability.NOT_IN_KERNEL)
        }

        var pswpinRate = 0f
        var pswpoutRate = 0f
        var majFaultRate = 0f
        var allocStallRate = 0f
        var compactRate = 0f

        if (prevVmTimeMs > 0L && nowMs > prevVmTimeMs) {
            val dtSec = ((nowMs - prevVmTimeMs).coerceAtLeast(200L)) / 1000f
            pswpinRate = (values[0] - prevPswpin).coerceAtLeast(0L) / dtSec
            pswpoutRate = (values[1] - prevPswpout).coerceAtLeast(0L) / dtSec
            majFaultRate = (values[2] - prevPgmajfault).coerceAtLeast(0L) / dtSec
            allocStallRate = (values[3] - prevAllocstall).coerceAtLeast(0L) / dtSec
            compactRate = (values[4] - prevCompactStall).coerceAtLeast(0L) / dtSec
        }

        prevVmTimeMs = nowMs
        prevPswpin = values[0]
        prevPswpout = values[1]
        prevPgmajfault = values[2]
        prevAllocstall = values[3]
        prevCompactStall = values[4]

        return VmStatRates(
            pswpinPerSec = pswpinRate,
            pswpoutPerSec = pswpoutRate,
            pgmajfaultPerSec = majFaultRate,
            allocstallPerSec = allocStallRate,
            compactStallPerSec = compactRate,
            availability = SysfsAvailability.AVAILABLE
        )
    }

    private fun computeDiskIoDiagnostics(diskOverride: String?, ioWaitPct: Float?, nowMs: Long): DiskIoDiagnostics {
        val values = LongArray(3)
        val readOk = if (!diskOverride.isNullOrBlank()) {
            parseDiskStatsText(diskOverride, values)
        } else {
            NativeProcReader.readDiskStats(values)
        }
        val dataFree = hardwareHelper.getDataPartitionFreePercent()
        if (!readOk) {
            return DiskIoDiagnostics(
                dataFreePercent = dataFree,
                isIoBottleneck = (ioWaitPct ?: 0f) > 20f || (dataFree ?: 100f) < 10f,
                availability = if (activeTier == CapabilityTier.STANDARD) SysfsAvailability.SELINUX_DENIED else SysfsAvailability.NOT_IN_KERNEL
            )
        }

        var readKbSec = 0f
        var writeKbSec = 0f
        var busyPct = 0f

        if (prevDiskTimeMs > 0L && nowMs > prevDiskTimeMs) {
            val dtMs = (nowMs - prevDiskTimeMs).coerceAtLeast(200L).toFloat()
            val dtSec = dtMs / 1000f
            // Linux /proc/diskstats sectors are 512 bytes => sectors * 0.5f = KB
            readKbSec = ((values[0] - prevDiskReadSectors).coerceAtLeast(0L) * 0.5f) / dtSec
            writeKbSec = ((values[1] - prevDiskWriteSectors).coerceAtLeast(0L) * 0.5f) / dtSec
            busyPct = (((values[2] - prevDiskIoTicks).coerceAtLeast(0L).toFloat() / dtMs) * 100f).coerceIn(0f, 100f)
        }

        prevDiskTimeMs = nowMs
        prevDiskReadSectors = values[0]
        prevDiskWriteSectors = values[1]
        prevDiskIoTicks = values[2]

        return DiskIoDiagnostics(
            readKbPerSec = readKbSec,
            writeKbPerSec = writeKbSec,
            ioBusyPercent = busyPct,
            dataFreePercent = dataFree,
            isIoBottleneck = busyPct > 70f || (ioWaitPct ?: 0f) > 20f || (dataFree ?: 100f) < 10f,
            availability = SysfsAvailability.AVAILABLE
        )
    }

    private fun buildJankDiagnostics(
        fgPkg: String,
        gfxRaw: String,
        timestatsRaw: String,
        fpsTelemetry: FpsTelemetry,
        refreshRateHz: Float
    ): JankDiagnostics {
        if (activeTier == CapabilityTier.STANDARD) {
            return JankDiagnostics(
                refreshRateHz = refreshRateHz,
                sourceNote = "Требуется Tier 1 (Shizuku) или Root"
            )
        }

        var totalFrames = 0
        var jankyFrames = 0
        var slowUi = 0
        var slowBmp = 0
        var slowDraw = 0
        var slowGpu = 0
        var renderer: String? = null

        for (line in gfxRaw.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("Pipeline:") -> renderer = t.substringAfter("Pipeline:").trim()
                t.startsWith("Total frames rendered:") -> totalFrames = t.substringAfter(':').trim().substringBefore(' ').toIntOrNull() ?: 0
                t.startsWith("Janky frames:") -> jankyFrames = t.substringAfter(':').trim().substringBefore(' ').toIntOrNull() ?: 0
                t.startsWith("Number Slow UI thread:") -> slowUi = t.substringAfter(':').trim().substringBefore(' ').toIntOrNull() ?: 0
                t.startsWith("Number Slow bitmap uploads:") -> slowBmp = t.substringAfter(':').trim().substringBefore(' ').toIntOrNull() ?: 0
                t.startsWith("Number Slow issue draw commands:") -> slowDraw = t.substringAfter(':').trim().substringBefore(' ').toIntOrNull() ?: 0
                t.startsWith("Number Frame deadline missed:") -> slowGpu = t.substringAfter(':').trim().substringBefore(' ').toIntOrNull() ?: 0
            }
        }

        var dTotal = 0
        var dJanky = 0
        var dUi = 0
        var dBmp = 0
        var dDraw = 0
        var dGpu = 0

        if (fgPkg == prevGfxPkg && totalFrames >= prevGfxTotal) {
            dTotal = (totalFrames - prevGfxTotal).coerceAtLeast(0)
            dJanky = (jankyFrames - prevGfxJanky).coerceAtLeast(0)
            dUi = (slowUi - prevGfxSlowUi).coerceAtLeast(0)
            dBmp = (slowBmp - prevGfxSlowBmp).coerceAtLeast(0)
            dDraw = (slowDraw - prevGfxSlowDraw).coerceAtLeast(0)
            dGpu = (slowGpu - prevGfxSlowGpu).coerceAtLeast(0)
        }
        prevGfxPkg = fgPkg
        prevGfxTotal = totalFrames
        prevGfxJanky = jankyFrames
        prevGfxSlowUi = slowUi
        prevGfxSlowBmp = slowBmp
        prevGfxSlowDraw = slowDraw
        prevGfxSlowGpu = slowGpu

        // Optional timestats FPS override if available
        val tsFps = Regex("""averageFPS\s*=\s*([0-9.]+)""", RegexOption.IGNORE_CASE).find(timestatsRaw)?.groupValues?.get(1)?.toFloatOrNull()
        val effFps = tsFps ?: fpsTelemetry.fps
        val effFrameMs = fpsTelemetry.frameTimeMs

        val jankyPct = when {
            dTotal > 0 -> ((dJanky.toFloat() / dTotal.toFloat()) * 100f).coerceIn(0f, 100f)
            totalFrames > 0 -> ((jankyFrames.toFloat() / totalFrames.toFloat()) * 100f).coerceIn(0f, 100f)
            else -> null
        }

        val den = dTotal.takeIf { it > 0 } ?: totalFrames.takeIf { it > 0 } ?: 1
        val uiPct = (if (dTotal > 0) dUi else slowUi) * 100f / den
        val bmpPct = (if (dTotal > 0) dBmp else slowBmp) * 100f / den
        val drawPct = (if (dTotal > 0) dDraw else slowDraw) * 100f / den
        val gpuPct = (if (dTotal > 0) dGpu else slowGpu) * 100f / den

        val stages = listOf(
            "Slow UI thread (Main Thread)" to uiPct,
            "Slow GPU / Frame deadline missed" to gpuPct,
            "Slow bitmap uploads" to bmpPct,
            "Slow issue draw commands" to drawPct
        )
        val topStage = stages.maxByOrNull { it.second }
        val dominantCause = if (topStage != null && topStage.second > 0.5f) topStage.first else "Кадры в бюджете (${refreshRateHz.toInt()} Hz)"

        return JankDiagnostics(
            foregroundPackage = fgPkg.ifBlank { null },
            rendererType = renderer,
            refreshRateHz = refreshRateHz,
            fps = effFps,
            frameTimeMs = effFrameMs,
            jankyFramesPercent = jankyPct,
            totalFramesDelta = dTotal,
            jankyFramesDelta = dJanky,
            slowUiThreadPct = uiPct,
            slowBitmapUploadPct = bmpPct,
            slowDrawCommandsPct = drawPct,
            slowGpuDeadlinePct = gpuPct,
            dominantCause = dominantCause,
            sourceNote = if (fgPkg.isNotBlank()) "gfxinfo ($fgPkg)" else "SurfaceFlinger"
        )
    }

    private fun queryDisplayRefreshRateHz(): Float {
        return runCatching {
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            display?.refreshRate?.takeIf { it > 30f } ?: 60f
        }.getOrDefault(60f)
    }

    private fun parseClusterCaps(arr: JSONArray): List<CpuCapClusterInfo> {
        val freqsByCore = ArrayList<Pair<Int, Int>>()
        val items = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            items.add(obj)
            freqsByCore.add(obj.optInt("core") to obj.optInt("cMax"))
        }
        val distinct = freqsByCore.map { it.second }.filter { it > 0 }.distinct().sortedDescending()
        val grouped = HashMap<CpuClusterRole, MutableList<JSONObject>>()
        for (obj in items) {
            val cMax = obj.optInt("cMax")
            val role = when {
                distinct.size >= 3 -> when (cMax) {
                    distinct[0] -> CpuClusterRole.PRIME
                    distinct[1] -> CpuClusterRole.PERF
                    else -> CpuClusterRole.EFF
                }
                distinct.size == 2 -> if (cMax == distinct[0]) CpuClusterRole.PERF else CpuClusterRole.EFF
                else -> CpuClusterRole.PERF
            }
            grouped.getOrPut(role) { mutableListOf() }.add(obj)
        }
        return grouped.entries.map { (role, cores) ->
            val online = cores.count { it.optBoolean("online", true) }
            val sMin = cores.minOfOrNull { it.optInt("sMin") } ?: 0
            val sMax = cores.maxOfOrNull { it.optInt("sMax") } ?: 0
            val cMax = cores.maxOfOrNull { it.optInt("cMax") } ?: sMax
            val gov = cores.firstOrNull()?.optString("gov").orEmpty().ifBlank { "schedutil" }
            CpuCapClusterInfo(
                role = role,
                scalingMinMhz = sMin,
                scalingMaxMhz = sMax,
                cpuinfoMaxMhz = cMax,
                governor = gov,
                onlineCores = online,
                totalCores = cores.size,
                isCapped = cMax > 0 && sMax in 1 until cMax
            )
        }.sortedBy {
            when (it.role) {
                CpuClusterRole.PRIME -> 0
                CpuClusterRole.PERF -> 1
                CpuClusterRole.EFF -> 2
            }
        }
    }

    private fun deriveClusterCapsFromCores(cpu: com.example.procpulse.core.model.CpuTelemetry): List<CpuCapClusterInfo> {
        return cpu.cores.groupBy { it.clusterRole }.entries.map { (role, list) ->
            val sMax = list.maxOfOrNull { it.scalingMaxMhz } ?: 0
            val cMax = list.maxOfOrNull { it.cpuinfoMaxMhz } ?: sMax
            CpuCapClusterInfo(
                role = role,
                scalingMinMhz = list.minOfOrNull { it.scalingMinMhz } ?: 0,
                scalingMaxMhz = sMax,
                cpuinfoMaxMhz = cMax,
                governor = list.firstOrNull()?.governor ?: cpu.activeGovernor,
                onlineCores = list.count { it.isOnline },
                totalCores = list.size,
                isCapped = cMax > 0 && sMax in 1 until cMax
            )
        }
    }

    private fun parseThermalServiceStatus(raw: String): String? {
        if (raw.isBlank()) return null
        val match = Regex("""(?:ThermalStatus|mStatus)[:=]\s*(\w+)""", RegexOption.IGNORE_CASE).find(raw)
        return match?.groupValues?.getOrNull(1)
    }

    private fun parseDumpsysCpuInfo(raw: String): List<CpuSpikeEntry> {
        val list = mutableListOf<CpuSpikeEntry>()
        val lineRegex = Regex("""^\s*([0-9.]+)%\s+\d+/([^\s:]+):?\s*(.*)$""")
        for (line in raw.lineSequence()) {
            val m = lineRegex.find(line) ?: continue
            val pct = m.groupValues[1].toFloatOrNull() ?: continue
            val proc = m.groupValues[2]
            val detail = m.groupValues[3].trim()
            if (pct >= 1.5f) {
                list.add(CpuSpikeEntry(processName = proc, cpuPercent = pct, detail = detail))
            }
        }
        return list.sortedByDescending { it.cpuPercent }.take(5)
    }

    private fun parseWakeups(alarms: String, jobs: String): List<WakeupEntry> {
        val list = mutableListOf<WakeupEntry>()
        val alarmRe = Regex("""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+).*?(\d+)\s+wakeup""", RegexOption.IGNORE_CASE)
        for (line in alarms.lineSequence()) {
            val m = alarmRe.find(line)
            if (m != null) {
                list.add(WakeupEntry(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 1, "alarm"))
            }
        }
        val jobCounts = HashMap<String, Int>()
        val jobRe = Regex("""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/""")
        for (line in jobs.lineSequence()) {
            val pkg = jobRe.find(line)?.groupValues?.getOrNull(1) ?: continue
            jobCounts[pkg] = (jobCounts[pkg] ?: 0) + 1
        }
        jobCounts.entries.sortedByDescending { it.value }.take(4).forEach { (pkg, cnt) ->
            list.add(WakeupEntry(pkg, cnt, "job"))
        }
        return list.sortedByDescending { it.count }.take(6)
    }

    private fun ingestLmkEvents(raw: String, nowMs: Long) {
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for (line in raw.lineSequence()) {
            if (!line.contains("am_kill") && !line.contains("lmkd") && !line.contains("lowmemorykiller")) continue
            val pkg = Regex("""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)""").find(line)?.groupValues?.getOrNull(1)
                ?: "unknown"
            val tsMatch = Regex("""^\s*(\d+)\.\d+""").find(line)?.groupValues?.get(1)?.toLongOrNull()
            val eventMs = if (tsMatch != null && tsMatch > 1_000_000_000L) tsMatch * 1000L else nowMs
            if (nowMs - eventMs > 300_000L) continue
            val reason = if (line.contains("am_kill")) {
                line.substringAfterLast(',').removeSuffix("]").trim().ifBlank { "am_kill" }
            } else "lmkd"
            val key = "${eventMs / 1000L}_$pkg"
            if (recentLmkKills.none { "${it.timestampMs / 1000L}_${it.packageName}" == key }) {
                recentLmkKills.add(
                    LmkKillEvent(
                        timestampLabel = timeFmt.format(Date(eventMs)),
                        timestampMs = eventMs,
                        packageName = pkg,
                        reason = reason
                    )
                )
            }
        }
    }

    private fun parseVmStatText(text: String, out: LongArray): Boolean {
        out.fill(0L)
        var found = false
        for (line in text.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val v = parts[1].toLongOrNull() ?: continue
            when {
                parts[0] == "pswpin" -> { out[0] = v; found = true }
                parts[0] == "pswpout" -> { out[1] = v; found = true }
                parts[0] == "pgmajfault" -> { out[2] = v; found = true }
                parts[0].startsWith("allocstall") -> { out[3] += v; found = true }
                parts[0] == "compact_stall" -> { out[4] = v; found = true }
            }
        }
        return found
    }

    private fun parseDiskStatsText(text: String, out: LongArray): Boolean {
        out.fill(0L)
        var found = false
        for (line in text.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 13) continue
            val dev = parts[2]
            if (dev == "mmcblk0" || dev == "sda" || dev == "sdb" || dev == "nvme0n1") {
                out[0] += parts[5].toLongOrNull() ?: 0L
                out[1] += parts[9].toLongOrNull() ?: 0L
                out[2] += parts[12].toLongOrNull() ?: 0L
                found = true
            }
        }
        return found
    }

    private fun parseStatusTag(status: String): SysfsAvailability = when (status) {
        "OK" -> SysfsAvailability.AVAILABLE
        "EACCES" -> SysfsAvailability.SELINUX_DENIED
        else -> SysfsAvailability.NOT_IN_KERNEL
    }

    private fun interpretCatOutcome(raw: String): Pair<String?, SysfsAvailability> {
        val low = raw.lowercase()
        return when {
            raw.isBlank() -> null to SysfsAvailability.NOT_IN_KERNEL
            low.contains("permission denied") -> null to SysfsAvailability.SELINUX_DENIED
            low.contains("no such file") -> null to SysfsAvailability.NOT_IN_KERNEL
            else -> raw to SysfsAvailability.AVAILABLE
        }
    }

    override suspend fun collectProcessDetail(
        entry: ProcessEntry,
        settings: AppSettings
    ): ProcessDetail = withContext(Dispatchers.IO) {
        val components = if (entry.packageName.contains('.')) {
            packageCache.getComponents(entry.packageName)
        } else emptyList()

        val standbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            when (usm.appStandbyBucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
                else -> "STANDARD"
            }
        } else "N/A"

        val pid = entry.pid
        if (activeTier == CapabilityTier.STANDARD || pid == null) {
            val memInfo = if (pid != null) {
                runCatching { activityManager.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull() }.getOrNull()
            } else null

            val note = if (pid == null) {
                "В Tier 0 ОС скрывает PID сторонних процессов; показана статистика активности по UID ${entry.uid}. Подключите Shizuku или Root для полной инспекции."
            } else {
                "Для просмотра USS, oom_score_adj, FD, сокетов, WakeLocks и Live Logcat подключите Shizuku или Root."
            }

            return@withContext ProcessDetail(
                entry = entry,
                pssMb = memInfo?.totalPss?.let { it / 1024f } ?: entry.ramPssMb,
                ussMb = memInfo?.let { (it.totalPrivateDirty + it.totalPrivateClean) / 1024f },
                rssMb = null,
                swapZramMb = null,
                graphicsEglMb = memInfo?.getMemoryStat("summary.graphics")?.toFloatOrNull()?.div(1024f),
                nativeHeapMb = memInfo?.nativePrivateDirty?.let { it / 1024f },
                artHeapMb = memInfo?.dalvikPrivateDirty?.let { it / 1024f },
                nice = null,
                oomScoreAdj = null,
                standbyBucket = standbyBucket,
                threadsCount = null,
                fdCount = null,
                affinityMask = null,
                runningServices = emptyList(),
                wakeLocks = emptyList(),
                jobsAndAlarms = emptyList(),
                sockets = emptyList(),
                components = components,
                isRestrictedBackground = false,
                tierLimitationReason = note
            )
        }

        // Tier 1 / Tier 2 detailed inspection with non-null PID
        var pssMb = entry.ramPssMb
        var ussMb = entry.ramUssMb
        var rssMb = entry.ramPssMb
        var swapMb: Float? = null
        var gfxMb: Float? = null
        var nativeMb: Float? = null
        var artMb: Float? = null
        var niceVal: Int? = null
        var oomAdj = entry.oomScoreAdj
        var threads: Int? = null
        var fdCount: Int? = null
        var affinity: String? = null
        val services = mutableListOf<String>()
        val wakeLocks = mutableListOf<String>()
        val jobs = mutableListOf<String>()
        var bgRestricted = false

        if (activeTier == CapabilityTier.ROOT) {
            val res = rootWorker.exec(
                "cat /proc/$pid/smaps_rollup 2>/dev/null; echo '---OOM---'; " +
                    "cat /proc/$pid/oom_score_adj 2>/dev/null; echo '---STAT---'; " +
                    "cat /proc/$pid/stat 2>/dev/null; echo '---FD---'; " +
                    "ls /proc/$pid/fd 2>/dev/null | wc -l; echo '---AFF---'; " +
                    "taskset -p $pid 2>/dev/null; echo '---MEMINFO---'; " +
                    "dumpsys meminfo $pid 2>/dev/null | head -n 40; echo '---WL---'; " +
                    "dumpsys power 2>/dev/null | grep -i '${entry.packageName}' | head -n 15"
            )
            val text = res.getOrDefault("")
            val smaps = text.substringBefore("---OOM---")
            parseSmapsRollup(smaps)?.let { (pss, uss, rss, swp) ->
                pssMb = pss; ussMb = uss; rssMb = rss; swapMb = swp
            }
            val afterOom = text.substringAfter("---OOM---", "")
            oomAdj = afterOom.substringBefore("---STAT---").trim().toIntOrNull() ?: oomAdj
            val afterStat = afterOom.substringAfter("---STAT---", "")
            val statLine = afterStat.substringBefore("---FD---").trim()
            parseStatMeta(statLine)?.let { (n, t) -> niceVal = n; threads = t }
            val afterFd = afterStat.substringAfter("---FD---", "")
            fdCount = afterFd.substringBefore("---AFF---").trim().toIntOrNull()
            val afterAff = afterFd.substringAfter("---AFF---", "")
            affinity = afterAff.substringBefore("---MEMINFO---").trim().substringAfterLast(": ").takeIf { it.isNotBlank() }
            val afterMem = afterAff.substringAfter("---MEMINFO---", "")
            val memDump = afterMem.substringBefore("---WL---")
            parseMeminfoDump(memDump)?.let { (g, nat, art) -> gfxMb = g; nativeMb = nat; artMb = art }
            val wlDump = afterMem.substringAfter("---WL---", "")
            wlDump.lines().filter { it.isNotBlank() }.forEach { wakeLocks.add(it.trim()) }
        } else if (activeTier == CapabilityTier.SHIZUKU) {
            val svc = shizukuService
            if (svc != null) {
                runCatching {
                    val obj = JSONObject(svc.readProcessDetail(pid, entry.packageName))
                    parseSmapsRollup(obj.optString("smaps"))?.let { (pss, uss, rss, swp) ->
                        pssMb = pss; ussMb = uss; rssMb = rss; swapMb = swp
                    }
                    oomAdj = obj.optString("oomAdj").trim().toIntOrNull() ?: oomAdj
                    parseStatMeta(obj.optString("stat"))?.let { (n, t) -> niceVal = n; threads = t }
                    fdCount = obj.optInt("fdCount", -1).takeIf { it >= 0 }
                    parseMeminfoDump(obj.optString("meminfo"))?.let { (g, nat, art) ->
                        gfxMb = g; nativeMb = nat; artMb = art
                    }
                    val svcDump = obj.optString("activityServices")
                    svcDump.lines().filter { it.contains("ServiceRecord{") }.take(15).forEach {
                        services.add(it.trim())
                    }
                    val wlDump = obj.optString("wakeLocks")
                    wlDump.lines().filter { it.isNotBlank() }.take(15).forEach {
                        wakeLocks.add(it.trim())
                    }
                    bgRestricted = obj.optString("appops").contains("ignore")
                }
            }
        }

        val sockets = collectSockets(entry.uid)
        ProcessDetail(
            entry = entry,
            pssMb = pssMb,
            ussMb = ussMb,
            rssMb = rssMb,
            swapZramMb = swapMb,
            graphicsEglMb = gfxMb,
            nativeHeapMb = nativeMb,
            artHeapMb = artMb,
            nice = niceVal,
            oomScoreAdj = oomAdj,
            standbyBucket = standbyBucket,
            threadsCount = threads,
            fdCount = fdCount,
            affinityMask = affinity,
            runningServices = services,
            wakeLocks = wakeLocks,
            jobsAndAlarms = jobs,
            sockets = sockets,
            components = components,
            isRestrictedBackground = bgRestricted,
            tierLimitationReason = null
        )
    }

    override suspend fun collectSockets(filterUid: Int?): List<SocketConnection> = withContext(Dispatchers.IO) {
        if (activeTier == CapabilityTier.STANDARD) return@withContext emptyList()
        val result = mutableListOf<SocketConnection>()
        if (activeTier == CapabilityTier.SHIZUKU) {
            val raw = runCatching { shizukuService?.readSocketsSnapshot(filterUid ?: -1) }.getOrNull() ?: return@withContext emptyList()
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext emptyList()
            if (obj.optString("mode") == "ss") {
                result.addAll(parseSsOutput(obj.optString("data"), filterUid))
            } else {
                result.addAll(parseProcNetTable(obj.optString("tcp"), "TCP", filterUid))
                result.addAll(parseProcNetTable(obj.optString("udp"), "UDP", filterUid))
            }
        } else if (activeTier == CapabilityTier.ROOT) {
            val res = rootWorker.exec("cat /proc/net/tcp; echo '---UDP---'; cat /proc/net/udp")
            val text = res.getOrDefault("")
            result.addAll(parseProcNetTable(text.substringBefore("---UDP---"), "TCP", filterUid))
            result.addAll(parseProcNetTable(text.substringAfter("---UDP---", ""), "UDP", filterUid))
        }
        result.take(150)
    }

    override suspend fun collectLogcat(pid: Int, maxLines: Int): List<LogcatEntry> = withContext(Dispatchers.IO) {
        if (activeTier == CapabilityTier.STANDARD || pid <= 0) return@withContext emptyList()
        val raw = when (activeTier) {
            CapabilityTier.SHIZUKU -> runCatching { shizukuService?.readLogcatSnapshot(pid, maxLines) }.getOrNull().orEmpty()
            CapabilityTier.ROOT -> rootWorker.exec("logcat -d -v threadtime --pid=$pid -t $maxLines").getOrDefault("")
            CapabilityTier.STANDARD -> ""
        }
        raw.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("---------") }
            .map { line ->
                val tokens = line.trim().split(Regex("\\s+"), limit = 6)
                if (tokens.size >= 6) {
                    val ts = "${tokens[0]} ${tokens[1]}"
                    val lvl = tokens[4].firstOrNull() ?: 'I'
                    val rest = tokens[5]
                    val tag = rest.substringBefore(':').trim()
                    val msg = rest.substringAfter(':', "").trim()
                    LogcatEntry(ts, lvl, tag, msg, line)
                } else {
                    LogcatEntry("", 'I', "logcat", line, line)
                }
            }
            .toList()
    }

    // --- Task Control Actions ---

    override suspend fun killBackground(packageName: String): ProcessActionOutcome = withContext(Dispatchers.IO) {
        runCatching {
            activityManager.killBackgroundProcesses(packageName)
            ProcessActionOutcome(true, "Запрос очистки кэш-процесса отправлен ($packageName)")
        }.getOrElse {
            ProcessActionOutcome(false, "Не удалось очистить процесс: ${it.message}")
        }
    }

    override suspend fun forceStop(packageName: String): ProcessActionOutcome =
        execPrivileged("am force-stop $packageName", "Force Stop выполнен для $packageName")

    override suspend fun setFreeze(packageName: String, freeze: Boolean): ProcessActionOutcome {
        val cmd = if (freeze) "pm suspend $packageName" else "pm unsuspend $packageName"
        return execPrivileged(cmd, if (freeze) "Пакет заморожен: $packageName" else "Пакет разморожен: $packageName")
    }

    override suspend fun setBackgroundRestricted(packageName: String, restrict: Boolean): ProcessActionOutcome {
        val mode = if (restrict) "ignore" else "allow"
        return execPrivileged(
            "cmd appops set $packageName RUN_ANY_IN_BACKGROUND $mode",
            if (restrict) "Фоновая активность ограничена ($packageName)" else "Фовое ограничение снято ($packageName)"
        )
    }

    override suspend fun sendSignal(pid: Int, signal: Int): ProcessActionOutcome {
        val sigName = when (signal) {
            9 -> "SIGKILL (-9)"
            19 -> "SIGSTOP"
            18 -> "SIGCONT"
            else -> "SIG($signal)"
        }
        return execPrivileged("kill -$signal $pid", "Сигнал $sigName отправлен PID $pid", requiresRoot = true)
    }

    override suspend fun reniceProcess(pid: Int, nice: Int): ProcessActionOutcome {
        val clamped = nice.coerceIn(-20, 19)
        return execPrivileged("renice -n $clamped -p $pid", "Приоритет nice=$clamped установлен для PID $pid", requiresRoot = true)
    }

    override suspend fun setCpuAffinity(pid: Int, hexMask: String): ProcessActionOutcome {
        return execPrivileged("taskset -p $hexMask $pid", "CPU маска $hexMask применена к PID $pid", requiresRoot = true)
    }

    override suspend fun setComponentEnabled(packageName: String, componentClass: String, enabled: Boolean): ProcessActionOutcome {
        val action = if (enabled) "enable" else "disable"
        return execPrivileged(
            "pm $action $packageName/$componentClass",
            "Компонент ${componentClass.substringAfterLast('.')} ${if (enabled) "включён" else "отключён"}",
            requiresRoot = true
        )
    }

    private suspend fun execPrivileged(
        cmd: String,
        successMsg: String,
        requiresRoot: Boolean = false
    ): ProcessActionOutcome = withContext(Dispatchers.IO) {
        if (activeTier == CapabilityTier.STANDARD) {
            return@withContext ProcessActionOutcome(false, "Операция недоступна в Tier 0. Подключите Shizuku или Root.")
        }
        if (requiresRoot && activeTier != CapabilityTier.ROOT) {
            return@withContext ProcessActionOutcome(false, "Операция требует Root (Tier 2).")
        }
        return@withContext when (activeTier) {
            CapabilityTier.ROOT -> {
                val res = rootWorker.exec(cmd)
                if (res.isSuccess) ProcessActionOutcome(true, successMsg)
                else {
                    rootAvailableFlag = false
                    activeTier = CapabilityTier.STANDARD
                    ProcessActionOutcome(false, "Root-сессия прервана, выполнен откат на Tier 0", CapabilityTier.STANDARD)
                }
            }
            CapabilityTier.SHIZUKU -> {
                val svc = bindShizukuServiceWithRetry(2)
                if (svc == null) {
                    activeTier = CapabilityTier.STANDARD
                    ProcessActionOutcome(false, "Сервис Shizuku отключён", CapabilityTier.STANDARD)
                } else {
                    try {
                        val code = svc.executeCommand(cmd)
                        if (code == 0) ProcessActionOutcome(true, successMsg)
                        else ProcessActionOutcome(false, "Команда завершилась с кодом $code")
                    } catch (_: RemoteException) {
                        shizukuService = null
                        if (!isShizukuBinderAlive()) activeTier = CapabilityTier.STANDARD
                        ProcessActionOutcome(false, "Связь с Shizuku потеряна", activeTier)
                    }
                }
            }
            CapabilityTier.STANDARD -> ProcessActionOutcome(false, "Требуется Tier 1/2")
        }
    }

    // --- Parsing Helpers ---

    private fun parseSurfaceFlingerLatency(raw: String, source: String): FpsTelemetry {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 4) return FpsTelemetry(null, null, null, false, "$source: нет активных кадров")
        val refreshPeriodNs = lines.firstOrNull()?.toLongOrNull()?.takeIf { it in 1_000_000L..50_000_000L } ?: 16_666_666L
        val thresholdMs = (refreshPeriodNs / 1_000_000f) * 1.25f

        val timestamps = mutableListOf<Long>()
        for (line in lines.drop(1)) {
            val cols = line.split(Regex("\\s+"))
            if (cols.size >= 3) {
                val presentNs = cols[1].toLongOrNull() ?: continue
                if (presentNs > 0L && presentNs != Long.MAX_VALUE) {
                    timestamps.add(presentNs)
                }
            }
        }
        if (timestamps.size < 3) return FpsTelemetry(null, null, null, false, "$source: статичный экран")
        val diffsMs = mutableListOf<Float>()
        for (i in 1 until timestamps.size) {
            val dtMs = (timestamps[i] - timestamps[i - 1]) / 1_000_000f
            if (dtMs in 1f..500f) diffsMs.add(dtMs)
        }
        if (diffsMs.isEmpty()) return FpsTelemetry(null, null, null, false, "$source: недостаточно выборок")
        val avgFrameMs = diffsMs.average().toFloat()
        val fps = (1000f / avgFrameMs).coerceIn(1f, 240f)
        val jank = diffsMs.count { it > thresholdMs }
        return FpsTelemetry(fps = fps, frameTimeMs = avgFrameMs, jankCount = jank, available = true, sourceNote = source)
    }

    private fun parseStatMeta(statLine: String): Pair<Int, Int>? {
        val rparen = statLine.lastIndexOf(')')
        if (rparen < 0 || rparen + 2 >= statLine.length) return null
        val parts = statLine.substring(rparen + 2).trim().split(Regex("\\s+"))
        val nice = parts.getOrNull(16)?.toIntOrNull() ?: 0
        val threads = parts.getOrNull(17)?.toIntOrNull() ?: 1
        return nice to threads
    }

    private fun parseSmapsRollup(smaps: String): List<Float>? {
        if (smaps.isBlank()) return null
        var pss = 0f
        var pclean = 0f
        var pdirty = 0f
        var rss = 0f
        var swap = 0f
        for (line in smaps.lineSequence()) {
            when {
                line.startsWith("Pss:") -> pss = line.substringAfter(':').trim().substringBefore(' ').toFloatOrNull() ?: 0f
                line.startsWith("Rss:") -> rss = line.substringAfter(':').trim().substringBefore(' ').toFloatOrNull() ?: 0f
                line.startsWith("Private_Clean:") -> pclean = line.substringAfter(':').trim().substringBefore(' ').toFloatOrNull() ?: 0f
                line.startsWith("Private_Dirty:") -> pdirty = line.substringAfter(':').trim().substringBefore(' ').toFloatOrNull() ?: 0f
                line.startsWith("SwapPss:") -> swap = line.substringAfter(':').trim().substringBefore(' ').toFloatOrNull() ?: 0f
            }
        }
        return listOf(pss / 1024f, (pclean + pdirty) / 1024f, rss / 1024f, swap / 1024f)
    }

    private fun parseMeminfoDump(dump: String): Triple<Float?, Float?, Float?>? {
        if (dump.isBlank()) return null
        var gfx: Float? = null
        var nat: Float? = null
        var art: Float? = null
        for (line in dump.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("Graphics:") -> gfx = t.substringAfter("Graphics:").trim().substringBefore(' ').toFloatOrNull()?.div(1024f)
                t.startsWith("Native Heap") -> nat = t.split(Regex("\\s+")).getOrNull(2)?.toFloatOrNull()?.div(1024f)
                t.startsWith("Dalvik Heap") -> art = t.split(Regex("\\s+")).getOrNull(2)?.toFloatOrNull()?.div(1024f)
            }
        }
        return Triple(gfx, nat, art)
    }

    private fun parseSsOutput(data: String, filterUid: Int?): List<SocketConnection> {
        val list = mutableListOf<SocketConnection>()
        for (line in data.lineSequence().drop(1)) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 6) continue
            val proto = cols[0].uppercase()
            val state = cols[1]
            val local = cols[4]
            val remote = cols[5]
            val uidMatch = Regex("""uid:(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (filterUid != null && uidMatch != filterUid) continue
            val lAddr = local.substringBeforeLast(':')
            val lPort = local.substringAfterLast(':').toIntOrNull() ?: 0
            val rAddr = remote.substringBeforeLast(':')
            val rPort = remote.substringAfterLast(':').toIntOrNull() ?: 0
            val owner = if (uidMatch >= 0) packageCache.getUserPackageForUid(uidMatch) ?: "UID $uidMatch" else "Kernel"
            list.add(SocketConnection(proto, lAddr, lPort, rAddr, rPort, state, uidMatch, owner))
        }
        return list
    }

    private fun parseProcNetTable(text: String, proto: String, filterUid: Int?): List<SocketConnection> {
        val list = mutableListOf<SocketConnection>()
        for (line in text.lineSequence().drop(1)) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 8) continue
            val uid = cols[7].toIntOrNull() ?: -1
            if (filterUid != null && uid != filterUid) continue
            val (lIp, lPort) = decodeHexEndpoint(cols[1])
            val (rIp, rPort) = decodeHexEndpoint(cols[2])
            val stateHex = cols[3].toIntOrNull(16) ?: 0
            val state = tcpStateName(stateHex)
            val owner = if (uid >= 0) packageCache.getUserPackageForUid(uid) ?: "UID $uid" else "System"
            list.add(SocketConnection(proto, lIp, lPort, rIp, rPort, state, uid, owner))
        }
        return list
    }

    private fun decodeHexEndpoint(hexPair: String): Pair<String, Int> {
        val ipHex = hexPair.substringBefore(':')
        val portHex = hexPair.substringAfter(':', "0")
        val port = portHex.toIntOrNull(16) ?: 0
        if (ipHex.length == 8) {
            val b4 = ipHex.substring(0, 2).toIntOrNull(16) ?: 0
            val b3 = ipHex.substring(2, 4).toIntOrNull(16) ?: 0
            val b2 = ipHex.substring(4, 6).toIntOrNull(16) ?: 0
            val b1 = ipHex.substring(6, 8).toIntOrNull(16) ?: 0
            return "$b1.$b2.$b3.$b4" to port
        }
        return ipHex to port
    }

    private fun tcpStateName(code: Int): String = when (code) {
        1 -> "ESTABLISHED"
        2 -> "SYN_SENT"
        3 -> "SYN_RECV"
        4 -> "FIN_WAIT1"
        5 -> "FIN_WAIT2"
        6 -> "TIME_WAIT"
        7 -> "CLOSE"
        8 -> "CLOSE_WAIT"
        9 -> "LAST_ACK"
        10 -> "LISTEN"
        11 -> "CLOSING"
        else -> "STATE_$code"
    }

    private fun parseElapsedSeconds(etime: String): Long {
        return try {
            val daysPart = if (etime.contains('-')) etime.substringBefore('-').toLong() else 0L
            val timePart = etime.substringAfter('-')
            val parts = timePart.split(':').map { it.toLongOrNull() ?: 0L }
            when (parts.size) {
                2 -> daysPart * 86400 + parts[0] * 60 + parts[1]
                3 -> daysPart * 86400 + parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> 0L
            }
        } catch (_: Throwable) {
            0L
        }
    }
}
