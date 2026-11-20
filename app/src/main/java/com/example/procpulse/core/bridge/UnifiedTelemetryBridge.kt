package com.example.procpulse.core.bridge

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.example.procpulse.core.model.AppSettings
import com.example.procpulse.core.model.CapabilityTier
import com.example.procpulse.core.model.FpsTelemetry
import com.example.procpulse.core.model.LogcatEntry
import com.example.procpulse.core.model.PreferredTierMode
import com.example.procpulse.core.model.ProcessCategory
import com.example.procpulse.core.model.ProcessDetail
import com.example.procpulse.core.model.ProcessEntry
import com.example.procpulse.core.model.ProcessStateTag
import com.example.procpulse.core.model.SocketConnection
import com.example.procpulse.core.model.SystemSnapshot
import com.example.procpulse.core.model.ThermalZoneInfo
import com.example.procpulse.core.nativebridge.NativeProcReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class UnifiedTelemetryBridge(
    private val context: Context,
    val packageCache: PackageCacheManager,
    val hardwareHelper: StandardHardwareHelper
) : SystemTelemetryBridge {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
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
    private var rootCheckedOnce = false

    @Volatile
    private var rootAvailableFlag = false

    // Previous process CPU jiffies map: pid -> (procTicks, totalSystemTicks)
    private val prevProcCpu = ConcurrentHashMap<Int, Pair<Long, Long>>()
    private var prevSysTotalJiffies = 0L

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, TelemetryUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("telemetry_service")
            .debuggable(false)
            .version(1)
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuService = ITelemetryUserService.Stub.asInterface(service)
            shizukuBound = shizukuService != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuService = null
            shizukuBound = false
            if (activeTier == CapabilityTier.SHIZUKU) {
                activeTier = CapabilityTier.STANDARD
            }
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
            // Brief wait for Binder connection
            repeat(8) {
                shizukuService?.let { return@withLock it }
                kotlinx.coroutines.delay(50L)
            }
            shizukuService
        }
    }

    override suspend fun refreshCapabilities(settings: AppSettings): CapabilityTier = withContext(Dispatchers.IO) {
        val shizukuAuth = isShizukuAuthorized()
        val chosen = when (settings.preferredTierMode) {
            PreferredTierMode.STANDARD -> CapabilityTier.STANDARD
            PreferredTierMode.SHIZUKU -> {
                if (shizukuAuth && bindShizukuServiceIfNeeded() != null) CapabilityTier.SHIZUKU else CapabilityTier.STANDARD
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
                } else if (shizukuAuth && bindShizukuServiceIfNeeded() != null) {
                    CapabilityTier.SHIZUKU
                } else {
                    CapabilityTier.STANDARD
                }
            }
        }
        activeTier = chosen
        chosen
    }

    override suspend fun collectSnapshot(settings: AppSettings): SystemSnapshot = withContext(Dispatchers.IO) {
        val tier = refreshCapabilities(settings)
        val shizukuAlive = isShizukuBinderAlive()
        val shizukuAuth = isShizukuAuthorized()

        var statOverride: String? = null
        var memOverride: String? = null
        var zramOverride: String? = null
        var psiOverride: String? = null
        var gpuBusyOverride: Float? = null
        var gpuClkOverride: Int? = null
        var gpuMaxOverride: Int? = null
        var ddrFreqOverride: Int? = null
        var zonesOverride: List<ThermalZoneInfo>? = null
        var fpsTelemetry = FpsTelemetry(null, null, null, false, "Требуется Shizuku или Root")
        var socketCount: Int? = null

        if (tier == CapabilityTier.ROOT) {
            val res = rootWorker.exec(
                "cat /proc/stat; echo '---MEM---'; cat /proc/meminfo; echo '---PSI---'; cat /proc/pressure/memory 2>/dev/null; " +
                    "echo '---ZRAM---'; cat /sys/block/zram0/mm_stat 2>/dev/null; echo '---GPU---'; " +
                    "cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null; cat /sys/class/kgsl/kgsl-3d0/clock_mhz 2>/dev/null; " +
                    "echo '---FPS---'; dumpsys SurfaceFlinger --latency 2>/dev/null | tail -n 30"
            )
            if (res.isFailure) {
                // Failover < 500ms
                rootAvailableFlag = false
                activeTier = CapabilityTier.STANDARD
            } else {
                val raw = res.getOrDefault("")
                statOverride = raw.substringBefore("---MEM---").trim()
                val afterMem = raw.substringAfter("---MEM---", "")
                memOverride = afterMem.substringBefore("---PSI---").trim()
                val afterPsi = afterMem.substringAfter("---PSI---", "")
                psiOverride = afterPsi.substringBefore("---ZRAM---").trim().takeIf { it.isNotEmpty() }
                val afterZram = afterPsi.substringAfter("---ZRAM---", "")
                zramOverride = afterZram.substringBefore("---GPU---").trim().takeIf { it.isNotEmpty() }
                val afterGpu = afterZram.substringAfter("---GPU---", "")
                val gpuPart = afterGpu.substringBefore("---FPS---").trim().lines()
                gpuBusyOverride = gpuPart.getOrNull(0)?.replace("%", "")?.trim()?.toFloatOrNull()
                gpuClkOverride = gpuPart.getOrNull(1)?.trim()?.toIntOrNull()
                val fpsPart = afterGpu.substringAfter("---FPS---", "")
                fpsTelemetry = parseSurfaceFlingerLatency(fpsPart, "Root SurfaceFlinger")
                socketCount = collectSockets(null).size
            }
        } else if (tier == CapabilityTier.SHIZUKU) {
            val svc = shizukuService
            if (svc != null) {
                try {
                    val jsonStr = svc.readSystemMetricsSnapshot()
                    val obj = JSONObject(jsonStr)
                    statOverride = obj.optString("stat").takeIf { it.isNotEmpty() }
                    memOverride = obj.optString("meminfo").takeIf { it.isNotEmpty() }
                    psiOverride = obj.optString("psi").takeIf { it.isNotEmpty() }
                    zramOverride = obj.optString("zram").takeIf { it.isNotEmpty() }
                    gpuBusyOverride = obj.optString("gpuBusy").replace("%", "").trim().toFloatOrNull()
                    gpuClkOverride = obj.optString("gpuClock").trim().toIntOrNull()
                    gpuMaxOverride = obj.optString("gpuMax").trim().toIntOrNull()
                    ddrFreqOverride = obj.optInt("ddrFreq", -1).takeIf { it > 0 }
                    val thermArr = obj.optJSONArray("thermals")
                    if (thermArr != null && thermArr.length() > 0) {
                        val mapped = mutableListOf<ThermalZoneInfo>()
                        for (i in 0 until thermArr.length()) {
                            val t = thermArr.getJSONObject(i)
                            val rawType = t.optString("type")
                            val rawVal = t.optLong("temp")
                            val tempC = if (kotlin.math.abs(rawVal) > 200) rawVal / 1000f else rawVal.toFloat()
                            val friendly = hardwareHelper.mapThermalType(rawType)
                            if (friendly != null && tempC in -20f..130f) {
                                mapped.add(ThermalZoneInfo(t.optInt("id"), rawType, friendly, tempC))
                            }
                        }
                        if (mapped.isNotEmpty()) zonesOverride = mapped.distinctBy { it.friendlyCategory + "_" + it.rawType }.take(12)
                    }
                    val sfOut = svc.readSurfaceFlingerFps()
                    fpsTelemetry = parseSurfaceFlingerLatency(sfOut, "Shizuku SurfaceFlinger")
                    socketCount = collectSockets(null).size
                } catch (_: DeadObjectException) {
                    shizukuService = null
                    shizukuBound = false
                    activeTier = CapabilityTier.STANDARD
                } catch (_: RemoteException) {
                    shizukuService = null
                    shizukuBound = false
                    activeTier = CapabilityTier.STANDARD
                }
            }
        }

        val cpu = hardwareHelper.readCpuTelemetry(statOverride)
        val gpu = hardwareHelper.readGpuTelemetry(gpuBusyOverride, gpuClkOverride, gpuMaxOverride, ddrFreqOverride)
        val memory = hardwareHelper.readMemoryTelemetry(memOverride, zramOverride, psiOverride)
        val battery = hardwareHelper.readBatteryTelemetry(settings)
        val thermal = hardwareHelper.readThermalTelemetry(battery.tempCelsius, zonesOverride)
        val network = hardwareHelper.readNetworkTelemetry(socketCount)

        val processes = collectProcesses(settings, null)
        val topCpu = processes.filter { (it.cpuPercent ?: 0f) > 0f }
            .sortedByDescending { it.cpuPercent ?: 0f }
            .take(5)
        val topRam = processes.filter { (it.ramPssMb ?: 0f) > 0f }
            .sortedByDescending { it.ramPssMb ?: 0f }
            .take(5)

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
            topCpuConsumers = topCpu,
            topRamConsumers = topRam
        )
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
        val svc = shizukuService ?: return hardwareHelper.collectTier0Processes(settings)
        return try {
            val rawJson = svc.listProcessesSnapshot()
            parsePsAndProcJson(rawJson, settings)
        } catch (_: RemoteException) {
            shizukuService = null
            activeTier = CapabilityTier.STANDARD
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
        val sysTotal = hardwareHelper.getLastProcStatTotalJiffies()
        val entries = parsePsTable(psOutput, null, sysTotal, settings)
        // Enhance with direct native C++ /proc/<pid>/stat & smaps_rollup when in Root/readable
        return entries
    }

    private fun parsePsAndProcJson(rawJson: String, settings: AppSettings): List<ProcessEntry> {
        val obj = JSONObject(rawJson)
        val psOut = obj.optString("ps")
        val procStats = obj.optJSONObject("procStats")
        val sysTotal = hardwareHelper.getLastProcStatTotalJiffies()
        return parsePsTable(psOut, procStats, sysTotal, settings)
    }

    private fun parsePsTable(
        psOut: String,
        procStatsJson: JSONObject?,
        sysTotalJiffies: Long,
        settings: AppSettings
    ): List<ProcessEntry> {
        packageCache.refreshIfNeeded()
        val dSysTotal = (sysTotalJiffies - prevSysTotalJiffies).coerceAtLeast(1L)
        if (sysTotalJiffies > 0L) prevSysTotalJiffies = sysTotalJiffies

        val result = ArrayList<ProcessEntry>(256)
        val lines = psOut.lineSequence().drop(1)
        val procDetailBuf = LongArray(12)

        for (line in lines) {
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

            val pkgName = cmd.substringBefore(' ').substringBefore(':')
            val meta = packageCache.getMeta(pkgName) ?: packageCache.getPackageForUid(uid)?.let { packageCache.getMeta(it) }
            val resolvedPkg = meta?.packageName ?: pkgName
            val displayName = meta?.displayName ?: cmd.substringAfterLast('/').substringBefore(' ')

            val isStopped = statCode.contains('T')
            val isNative = uid < 10000 && meta == null

            // Compute exact delta CPU% if /proc/<pid>/stat is available
            var cpuPct: Float? = null
            var pssMb: Float? = if (rssKb > 0) rssKb / 1024f else null
            var ussMb: Float? = null
            var oomAdj: Int? = null

            val statStr = procStatsJson?.optString(pid.toString())?.takeIf { it.isNotEmpty() }
            if (statStr != null) {
                val ticks = extractUtmStmFromStatLine(statStr)
                val prev = prevProcCpu[pid]
                if (prev != null && sysTotalJiffies > prev.second) {
                    val dProc = (ticks - prev.first).coerceAtLeast(0L)
                    cpuPct = ((dProc.toFloat() / dSysTotal.toFloat()) * 100f).coerceIn(0f, 100f)
                }
                prevProcCpu[pid] = ticks to sysTotalJiffies
            } else if (activeTier == CapabilityTier.ROOT && NativeProcReader.readProcessDetail(pid, procDetailBuf)) {
                val ticks = procDetailBuf[1] + procDetailBuf[2]
                val prev = prevProcCpu[pid]
                if (prev != null && sysTotalJiffies > prev.second) {
                    val dProc = (ticks - prev.first).coerceAtLeast(0L)
                    cpuPct = ((dProc.toFloat() / dSysTotal.toFloat()) * 100f).coerceIn(0f, 100f)
                }
                prevProcCpu[pid] = ticks to sysTotalJiffies
                if (procDetailBuf[9] > 0) pssMb = procDetailBuf[9] / 1024f
                if (procDetailBuf[10] > 0) ussMb = procDetailBuf[10] / 1024f
                if (procDetailBuf[7] != Long.MIN_VALUE) oomAdj = procDetailBuf[7].toInt()
            }

            val stateTag = when {
                isStopped -> ProcessStateTag.STP
                statCode.contains('R') -> ProcessStateTag.FG
                isNative -> ProcessStateTag.SVC
                else -> ProcessStateTag.BGD
            }

            val category = when {
                isStopped -> ProcessCategory.FROZEN
                isNative -> ProcessCategory.NATIVE
                meta?.isSystem == true -> ProcessCategory.SYSTEM
                stateTag == ProcessStateTag.FG -> ProcessCategory.APPS
                else -> ProcessCategory.BACKGROUND
            }

            result.add(
                ProcessEntry(
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
                    uptimeSeconds = parseElapsedSeconds(elapsedStr),
                    isProtected = packageCache.isProtectedPackage(resolvedPkg, settings.whitelistPackages),
                    isFrozenOrStopped = isStopped,
                    isEstimatedMemory = ussMb == null && pssMb != null
                )
            )
        }
        return result
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

        if (activeTier == CapabilityTier.STANDARD) {
            val memInfo = runCatching {
                activityManager.getProcessMemoryInfo(intArrayOf(entry.pid)).firstOrNull()
            }.getOrNull()

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
                tierLimitationReason = "Для просмотра USS, oom_score_adj, FD, сокетов, WakeLocks и Live Logcat подключите Shizuku или Root."
            )
        }

        // Tier 1 / Tier 2 detailed inspection
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
                "cat /proc/${entry.pid}/smaps_rollup 2>/dev/null; echo '---OOM---'; " +
                    "cat /proc/${entry.pid}/oom_score_adj 2>/dev/null; echo '---STAT---'; " +
                    "cat /proc/${entry.pid}/stat 2>/dev/null; echo '---FD---'; " +
                    "ls /proc/${entry.pid}/fd 2>/dev/null | wc -l; echo '---AFF---'; " +
                    "taskset -p ${entry.pid} 2>/dev/null; echo '---MEMINFO---'; " +
                    "dumpsys meminfo ${entry.pid} 2>/dev/null | head -n 40; echo '---WL---'; " +
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
                    val obj = JSONObject(svc.readProcessDetail(entry.pid, entry.packageName))
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
        if (activeTier == CapabilityTier.STANDARD) return@withContext emptyList()
        val raw = when (activeTier) {
            CapabilityTier.SHIZUKU -> runCatching { shizukuService?.readLogcatSnapshot(pid, maxLines) }.getOrNull().orEmpty()
            CapabilityTier.ROOT -> rootWorker.exec("logcat -d -v threadtime --pid=$pid -t $maxLines").getOrDefault("")
            CapabilityTier.STANDARD -> ""
        }
        raw.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("---------") }
            .map { line ->
                // format: MM-DD HH:MM:SS.mmm  PID  TID L TAG: message
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
            if (restrict) "Фоновая активность ограничена ($packageName)" else "Фоновое ограничение снято ($packageName)"
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
                val svc = shizukuService
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
                        activeTier = CapabilityTier.STANDARD
                        ProcessActionOutcome(false, "Связь с Shizuku потеряна, откат на Tier 0", CapabilityTier.STANDARD)
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
        val jank = diffsMs.count { it > 20f }
        return FpsTelemetry(fps = fps, frameTimeMs = avgFrameMs, jankCount = jank, available = true, sourceNote = source)
    }

    private fun extractUtmStmFromStatLine(statLine: String): Long {
        val rparen = statLine.lastIndexOf(')')
        if (rparen < 0 || rparen + 2 >= statLine.length) return 0L
        val parts = statLine.substring(rparen + 2).trim().split(Regex("\\s+"))
        // index 11 = utime (field 14), index 12 = stime (field 15)
        val utime = parts.getOrNull(11)?.toLongOrNull() ?: 0L
        val stime = parts.getOrNull(12)?.toLongOrNull() ?: 0L
        return utime + stime
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
            val owner = if (uidMatch >= 0) packageCache.getPackageForUid(uidMatch) ?: "UID $uidMatch" else "Kernel"
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
            val owner = if (uid >= 0) packageCache.getPackageForUid(uid) ?: "UID $uid" else "System"
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
        // formats: MM:SS, HH:MM:SS, D-HH:MM:SS
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
