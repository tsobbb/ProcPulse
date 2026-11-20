package com.example.procpulse.core.bridge

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.example.procpulse.core.model.AppSettings
import com.example.procpulse.core.model.BatteryTelemetry
import com.example.procpulse.core.model.CpuClusterRole
import com.example.procpulse.core.model.CpuCoreSnapshot
import com.example.procpulse.core.model.CpuTelemetry
import com.example.procpulse.core.model.CurrentCalibrationMode
import com.example.procpulse.core.model.GpuTelemetry
import com.example.procpulse.core.model.MemoryTelemetry
import com.example.procpulse.core.model.NetworkTelemetry
import com.example.procpulse.core.model.ProcessCategory
import com.example.procpulse.core.model.ProcessEntry
import com.example.procpulse.core.model.ProcessStateTag
import com.example.procpulse.core.model.ThermalLevel
import com.example.procpulse.core.model.ThermalTelemetry
import com.example.procpulse.core.model.ThermalZoneInfo
import com.example.procpulse.core.nativebridge.NativeProcReader
import java.io.File
import kotlin.math.abs

class StandardHardwareHelper(
    private val context: Context,
    private val packageCache: PackageCacheManager
) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val cachedMaxFreqsMhz = IntArray(coreCount)
    private val cachedClusterRoles = Array(coreCount) { CpuClusterRole.EFF }

    private var cachedGpuVendor: String = "Unknown GPU"
    private var cachedGpuRenderer: String = "GLES"
    private var gpuQueried = false

    // Session network & battery tracking
    private var prevNetTimeMs = 0L
    private var prevMobileRx = 0L
    private var prevMobileTx = 0L
    private var prevTotalRx = 0L
    private var prevTotalTx = 0L
    private var sessionWifiStartTotal = -1L
    private var sessionCellStartTotal = -1L

    private var batterySessionStartMs = System.currentTimeMillis()
    private var batterySessionStartPct = -1

    // Reusable native buffers
    private val procStatBuf = LongArray(3 + (coreCount + 1) * 8)
    private val prevCoreActive = LongArray(coreCount + 1)
    private val prevCoreTotal = LongArray(coreCount + 1)
    private val prevUser = LongArray(1)
    private val prevSys = LongArray(1)
    private val prevIoWait = LongArray(1)
    private var prevCtxt = 0L
    private var prevIntr = 0L
    private var prevStatTimeMs = 0L

    private val memInfoBuf = LongArray(11)
    private val zramBuf = LongArray(3)
    private val psiBuf = DoubleArray(2)

    init {
        detectCpuTopology()
    }

    private fun detectCpuTopology() {
        for (i in 0 until coreCount) {
            val maxKhz = NativeProcReader.readLongFile("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                ?: NativeProcReader.readLongFile("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq")
                ?: 0L
            cachedMaxFreqsMhz[i] = (maxKhz / 1000L).toInt()
        }
        val distinctFreqs = cachedMaxFreqsMhz.filter { it > 0 }.distinct().sortedDescending()
        for (i in 0 until coreCount) {
            val f = cachedMaxFreqsMhz[i]
            cachedClusterRoles[i] = when {
                distinctFreqs.size >= 3 -> when (f) {
                    distinctFreqs[0] -> CpuClusterRole.PRIME
                    distinctFreqs[1] -> CpuClusterRole.PERF
                    else -> CpuClusterRole.EFF
                }
                distinctFreqs.size == 2 -> if (f == distinctFreqs[0]) CpuClusterRole.PERF else CpuClusterRole.EFF
                else -> CpuClusterRole.PERF
            }
        }
    }

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun queryGpuInfoOnce() {
        if (gpuQueried) return
        gpuQueried = true
        runCatching {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return
            val vers = IntArray(2)
            if (!EGL14.eglInitialize(display, vers, 0, vers, 1)) return
            val configAttr = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, configAttr, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0] ?: return
            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val eglCtx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
            val surfAttr = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val surface = EGL14.eglCreatePbufferSurface(display, config, surfAttr, 0)
            EGL14.eglMakeCurrent(display, surface, surface, eglCtx)
            cachedGpuRenderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "GLES20"
            cachedGpuVendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "SoC"
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, eglCtx)
        }
    }

    fun readCpuTelemetry(overrideStatLines: String? = null): CpuTelemetry {
        val now = System.currentTimeMillis()
        val statRead = if (overrideStatLines != null) {
            parseStatTextIntoBuffer(overrideStatLines, procStatBuf)
        } else {
            NativeProcReader.readProcStat(procStatBuf)
        }

        val isExact = statRead > 0
        var totalUsage: Float? = null
        var userPct: Float? = null
        var kernelPct: Float? = null
        var ioWaitPct: Float? = null
        var ctxtRate: Long? = null
        var intrRate: Long? = null

        val dtSec = if (prevStatTimeMs > 0) ((now - prevStatTimeMs).coerceAtLeast(100L) / 1000.0) else 1.0

        if (isExact) {
            // slot 0 = total CPU
            val u = procStatBuf[3] + procStatBuf[4]
            val s = procStatBuf[5] + procStatBuf[8] + procStatBuf[9]
            val idle = procStatBuf[6]
            val io = procStatBuf[7]
            val active = u + s + procStatBuf[10]
            val total = active + idle + io

            if (prevCoreTotal[0] > 0 && total > prevCoreTotal[0]) {
                val dTotal = (total - prevCoreTotal[0]).toFloat()
                val dActive = (active - prevCoreActive[0]).coerceAtLeast(0L).toFloat()
                totalUsage = ((dActive / dTotal) * 100f).coerceIn(0f, 100f)
                userPct = (((u - prevUser[0]).coerceAtLeast(0L) / dTotal) * 100f).coerceIn(0f, 100f)
                kernelPct = (((s - prevSys[0]).coerceAtLeast(0L) / dTotal) * 100f).coerceIn(0f, 100f)
                ioWaitPct = (((io - prevIoWait[0]).coerceAtLeast(0L) / dTotal) * 100f).coerceIn(0f, 100f)
            } else {
                totalUsage = 0f
                userPct = 0f
                kernelPct = 0f
                ioWaitPct = 0f
            }
            prevCoreActive[0] = active
            prevCoreTotal[0] = total
            prevUser[0] = u
            prevSys[0] = s
            prevIoWait[0] = io

            if (prevCtxt > 0 && procStatBuf[1] >= prevCtxt) {
                ctxtRate = ((procStatBuf[1] - prevCtxt) / dtSec).toLong()
            }
            if (prevIntr > 0 && procStatBuf[2] >= prevIntr) {
                intrRate = ((procStatBuf[2] - prevIntr) / dtSec).toLong()
            }
            prevCtxt = procStatBuf[1]
            prevIntr = procStatBuf[2]
            prevStatTimeMs = now
        }

        var governor = "schedutil"
        val cores = ArrayList<CpuCoreSnapshot>(coreCount)
        for (i in 0 until coreCount) {
            val curKhz = NativeProcReader.readLongFile("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") ?: 0L
            val curMhz = (curKhz / 1000L).toInt()
            if (cachedMaxFreqsMhz[i] == 0) {
                val maxKhz = NativeProcReader.readLongFile("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq") ?: 0L
                cachedMaxFreqsMhz[i] = (maxKhz / 1000L).toInt()
            }
            val gov = NativeProcReader.readTextFile("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor")
            if (gov != null && i == 0) governor = gov

            var coreUsage: Float? = null
            if (isExact && i + 1 <= statRead) {
                val base = 3 + (i + 1) * 8
                val cu = procStatBuf[base] + procStatBuf[base + 1]
                val cs = procStatBuf[base + 2] + procStatBuf[base + 5] + procStatBuf[base + 6]
                val cidle = procStatBuf[base + 3]
                val cio = procStatBuf[base + 4]
                val cactive = cu + cs + procStatBuf[base + 7]
                val ctotal = cactive + cidle + cio
                if (prevCoreTotal[i + 1] > 0 && ctotal > prevCoreTotal[i + 1]) {
                    val dTot = (ctotal - prevCoreTotal[i + 1]).toFloat()
                    val dAct = (cactive - prevCoreActive[i + 1]).coerceAtLeast(0L).toFloat()
                    coreUsage = ((dAct / dTot) * 100f).coerceIn(0f, 100f)
                } else {
                    coreUsage = 0f
                }
                prevCoreActive[i + 1] = cactive
                prevCoreTotal[i + 1] = ctotal
            }

            cores.add(
                CpuCoreSnapshot(
                    coreIndex = i,
                    clusterRole = cachedClusterRoles[i],
                    usagePercent = coreUsage,
                    currentFreqMhz = curMhz,
                    maxFreqMhz = cachedMaxFreqsMhz[i],
                    governor = gov ?: governor
                )
            )
        }

        return CpuTelemetry(
            totalUsagePercent = totalUsage,
            userPercent = userPct,
            kernelPercent = kernelPct,
            ioWaitPercent = ioWaitPct,
            contextSwitchesPerSec = ctxtRate,
            interruptsPerSec = intrRate,
            activeGovernor = governor,
            cores = cores,
            isUsageExact = isExact
        )
    }

    fun readGpuTelemetry(
        overrideBusyPct: Float? = null,
        overrideCurMhz: Int? = null,
        overrideMaxMhz: Int? = null,
        overrideDdrMhz: Int? = null
    ): GpuTelemetry {
        queryGpuInfoOnce()
        val busy = overrideBusyPct
            ?: NativeProcReader.readTextFile("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
                ?.replace("%", "")?.trim()?.toFloatOrNull()
        val curMhz = overrideCurMhz
            ?: NativeProcReader.readLongFile("/sys/class/kgsl/kgsl-3d0/clock_mhz")?.toInt()
            ?: NativeProcReader.readLongFile("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")?.let { (it / 1_000_000L).toInt() }
        val maxMhz = overrideMaxMhz
            ?: NativeProcReader.readLongFile("/sys/class/kgsl/kgsl-3d0/max_clock_mhz")?.toInt()
            ?: NativeProcReader.readLongFile("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq")?.let { (it / 1_000_000L).toInt() }

        return GpuTelemetry(
            rendererName = cachedGpuRenderer,
            vendorName = cachedGpuVendor,
            usagePercent = busy,
            currentFreqMhz = curMhz,
            maxFreqMhz = maxMhz,
            ddrFreqMhz = overrideDdrMhz
        )
    }

    fun readMemoryTelemetry(
        overrideMemInfoText: String? = null,
        overrideZramText: String? = null,
        overridePsiText: String? = null
    ): MemoryTelemetry {
        val sysMemInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(sysMemInfo)
        val totalAmMb = sysMemInfo.totalMem / (1024L * 1024L)
        val availAmMb = sysMemInfo.availMem / (1024L * 1024L)

        val memRead = if (overrideMemInfoText != null) {
            parseMemInfoTextIntoBuffer(overrideMemInfoText, memInfoBuf)
        } else {
            NativeProcReader.readMemInfo(memInfoBuf)
        }

        val zramRead = if (overrideZramText != null) {
            val parts = overrideZramText.trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                zramBuf[0] = parts[0].toLongOrNull() ?: 0L
                zramBuf[1] = parts[1].toLongOrNull() ?: 0L
                zramBuf[2] = parts[2].toLongOrNull() ?: 0L
                true
            } else false
        } else {
            NativeProcReader.readZramMmStat(zramBuf)
        }

        val psiRead = if (overridePsiText != null) {
            val some = Regex("""some avg10=([0-9.]+)""").find(overridePsiText)?.groupValues?.get(1)?.toDoubleOrNull()
            val full = Regex("""full avg10=([0-9.]+)""").find(overridePsiText)?.groupValues?.get(1)?.toDoubleOrNull()
            if (some != null) {
                psiBuf[0] = some
                psiBuf[1] = full ?: 0.0
                true
            } else false
        } else {
            NativeProcReader.readPsiMemory(psiBuf)
        }

        val totalMb = if (memRead && memInfoBuf[0] > 0) memInfoBuf[0] / 1024L else totalAmMb
        val freeMb = if (memRead) memInfoBuf[1] / 1024L else availAmMb
        val availMb = if (memRead && memInfoBuf[2] > 0) memInfoBuf[2] / 1024L else availAmMb
        val cachedMb = if (memRead) (memInfoBuf[3] + memInfoBuf[4] + memInfoBuf[5]) / 1024L else 0L
        val slabMb = if (memRead) memInfoBuf[10] / 1024L else 0L
        val anonMb = if (memRead && (memInfoBuf[8] + memInfoBuf[9]) > 0) {
            (memInfoBuf[8] + memInfoBuf[9]) / 1024L
        } else {
            (totalMb - freeMb - cachedMb - slabMb).coerceAtLeast(0L)
        }

        val zramTotalMb = if (memRead) memInfoBuf[6] / 1024L else 0L
        val zramSwapUsedMb = if (memRead) (memInfoBuf[6] - memInfoBuf[7]).coerceAtLeast(0L) / 1024L else 0L
        val zramOrigMb = if (zramRead) zramBuf[0] / (1024L * 1024L) else zramSwapUsedMb
        val zramComprMb = if (zramRead) zramBuf[1] / (1024L * 1024L) else 0L
        val zramRatio = if (zramRead && zramBuf[1] > 0) {
            (zramBuf[0].toFloat() / zramBuf[1].toFloat()).coerceIn(0.5f, 15f)
        } else 1.0f

        return MemoryTelemetry(
            totalMb = totalMb,
            appAnonMb = anonMb,
            cachedMb = cachedMb,
            kernelSlabMb = slabMb,
            freeMb = freeMb,
            availableMb = availMb,
            zramTotalMb = zramTotalMb,
            zramUsedMb = zramSwapUsedMb,
            zramOrigMb = zramOrigMb,
            zramComprMb = zramComprMb,
            zramRatio = zramRatio,
            psiSomeAvg10 = if (psiRead) psiBuf[0] else null,
            psiFullAvg10 = if (psiRead) psiBuf[1] else null,
            isDetailedExact = memRead
        )
    }

    fun readBatteryTelemetry(settings: AppSettings): BatteryTelemetry {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val pct = if (level >= 0) (level * 100) / scale else batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = tempTenths / 10f
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val rawCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toFloat()
        var currentMa = when (settings.currentCalibrationMode) {
            CurrentCalibrationMode.AUTO -> if (abs(rawCurrent) > 15_000f) rawCurrent / 1000f else rawCurrent
            CurrentCalibrationMode.MICRO_TO_MILLI -> rawCurrent / 1000f
            CurrentCalibrationMode.MILLI_TO_MILLI -> rawCurrent
            CurrentCalibrationMode.DIVIDE_BY_10 -> rawCurrent / 10f
        }
        if (settings.invertCurrentSign) {
            currentMa = -currentMa
        }

        val powerWatts = abs(currentMa * voltageMv) / 1_000_000f
        val chargeCounterUah = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val chargeCounterMah = if (chargeCounterUah > 0) {
            if (chargeCounterUah > 100_000) chargeCounterUah / 1000 else chargeCounterUah
        } else 0
        val designCap = readDesignCapacityMah()

        val now = System.currentTimeMillis()
        if (batterySessionStartPct < 0) {
            batterySessionStartPct = pct
            batterySessionStartMs = now
        }
        val elapsedHrs = ((now - batterySessionStartMs).coerceAtLeast(60_000L)) / 3_600_000f
        val ratePctHr = if (now - batterySessionStartMs > 30_000L) {
            (pct - batterySessionStartPct) / elapsedHrs
        } else {
            val estCapacity = if (designCap > 0) designCap.toFloat() else 4500f
            val signedMa = if (isCharging) abs(currentMa) else -abs(currentMa)
            (signedMa / estCapacity) * 100f
        }

        val statusLabel = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Зарядка"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Разряд"
            BatteryManager.BATTERY_STATUS_FULL -> "Заряжена"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Не заряжается"
            else -> "Неизвестно"
        }

        return BatteryTelemetry(
            percent = pct.coerceIn(0, 100),
            currentMa = currentMa,
            voltageMv = voltageMv,
            powerWatts = powerWatts,
            ratePctPerHour = ratePctHr,
            chargeCounterMah = chargeCounterMah,
            designCapacityMah = designCap,
            isCharging = isCharging,
            statusLabel = statusLabel,
            tempCelsius = tempC
        )
    }

    @Suppress("PrivateApi")
    private fun readDesignCapacityMah(): Int {
        return runCatching {
            val profileCls = Class.forName("com.android.internal.os.PowerProfile")
            val instance = profileCls.getConstructor(Context::class.java).newInstance(context)
            val method = profileCls.getMethod("getBatteryCapacity")
            (method.invoke(instance) as Double).toInt()
        }.getOrDefault(0)
    }

    fun readThermalTelemetry(batteryTempC: Float, overrideZones: List<ThermalZoneInfo>? = null): ThermalTelemetry {
        val statusInt = powerManager.currentThermalStatus
        val status = when (statusInt) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalLevel.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.SHUTDOWN
            else -> ThermalLevel.NONE
        }
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { powerManager.getThermalHeadroom(10).takeIf { !it.isNaN() && it >= 0f } }.getOrNull()
        } else null

        val zones = overrideZones ?: readThermalZonesFromSysfs(batteryTempC)
        return ThermalTelemetry(status = status, headroom = headroom, zones = zones)
    }

    private fun readThermalZonesFromSysfs(batteryTempC: Float): List<ThermalZoneInfo> {
        val result = mutableListOf<ThermalZoneInfo>()
        for (i in 0 until 40) {
            val base = "/sys/class/thermal/thermal_zone$i"
            if (!File(base).exists()) continue
            val rawType = NativeProcReader.readTextFile("$base/type") ?: continue
            val rawTemp = NativeProcReader.readLongFile("$base/temp") ?: continue
            val tempC = if (abs(rawTemp) > 200) rawTemp / 1000f else rawTemp.toFloat()
            if (tempC < -20f || tempC > 130f) continue
            val friendly = mapThermalType(rawType)
            if (friendly != null) {
                result.add(ThermalZoneInfo(i, rawType, friendly, tempC))
            }
        }
        if (result.none { it.friendlyCategory == "Battery" } && batteryTempC > 0f) {
            result.add(ThermalZoneInfo(99, "battery_bm", "Battery", batteryTempC))
        }
        return result.distinctBy { it.friendlyCategory + "_" + it.rawType }.take(12)
    }

    fun mapThermalType(rawType: String): String? {
        val lower = rawType.lowercase()
        return when {
            lower.contains("cpu") || lower.contains("ap") || lower.contains("cluster") || lower.contains("big") || lower.contains("little") -> "CPU"
            lower.contains("gpu") || lower.contains("gpuss") -> "GPU"
            lower.contains("batt") || lower.contains("bms") -> "Battery"
            lower.contains("skin") || lower.contains("quiet") || lower.contains("xo-therm") || lower.contains("back") -> "Skin / Корпус"
            lower.contains("soc") || lower.contains("pmic") || lower.contains("ddr") -> "SoC / PMIC"
            else -> null
        }
    }

    fun readNetworkTelemetry(activeSocketCount: Int?): NetworkTelemetry {
        val now = System.currentTimeMillis()
        val totalRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        val totalTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        val mobileRx = TrafficStats.getMobileRxBytes().coerceAtLeast(0L)
        val mobileTx = TrafficStats.getMobileTxBytes().coerceAtLeast(0L)

        val wifiRxTotal = (totalRx - mobileRx).coerceAtLeast(0L)
        val wifiTxTotal = (totalTx - mobileTx).coerceAtLeast(0L)
        val wifiTotal = wifiRxTotal + wifiTxTotal
        val cellTotal = mobileRx + mobileTx

        if (sessionWifiStartTotal < 0L) sessionWifiStartTotal = wifiTotal
        if (sessionCellStartTotal < 0L) sessionCellStartTotal = cellTotal

        var wifiRxRate = 0L
        var wifiTxRate = 0L
        var cellRxRate = 0L
        var cellTxRate = 0L

        if (prevNetTimeMs > 0L) {
            val dtSec = ((now - prevNetTimeMs).coerceAtLeast(100L)) / 1000.0
            val dCellRx = (mobileRx - prevMobileRx).coerceAtLeast(0L)
            val dCellTx = (mobileTx - prevMobileTx).coerceAtLeast(0L)
            val dTotalRx = (totalRx - prevTotalRx).coerceAtLeast(0L)
            val dTotalTx = (totalTx - prevTotalTx).coerceAtLeast(0L)
            val dWifiRx = (dTotalRx - dCellRx).coerceAtLeast(0L)
            val dWifiTx = (dTotalTx - dCellTx).coerceAtLeast(0L)

            wifiRxRate = (dWifiRx / dtSec).toLong()
            wifiTxRate = (dWifiTx / dtSec).toLong()
            cellRxRate = (dCellRx / dtSec).toLong()
            cellTxRate = (dCellTx / dtSec).toLong()
        }

        prevNetTimeMs = now
        prevMobileRx = mobileRx
        prevMobileTx = mobileTx
        prevTotalRx = totalRx
        prevTotalTx = totalTx

        val transportLabel = runCatching {
            val net = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(net)
            when {
                caps == null -> "Нет сети"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Подключено"
            }
        }.getOrDefault("Неизвестно")

        return NetworkTelemetry(
            wifiRxBytesPerSec = wifiRxRate,
            wifiTxBytesPerSec = wifiTxRate,
            cellRxBytesPerSec = cellRxRate,
            cellTxBytesPerSec = cellTxRate,
            sessionWifiTotalBytes = (wifiTotal - sessionWifiStartTotal).coerceAtLeast(0L),
            sessionCellTotalBytes = (cellTotal - sessionCellStartTotal).coerceAtLeast(0L),
            activeSocketCount = activeSocketCount,
            activeTransportLabel = transportLabel
        )
    }

    fun collectTier0Processes(settings: AppSettings): List<ProcessEntry> {
        packageCache.refreshIfNeeded()
        val result = mutableListOf<ProcessEntry>()
        val seenPackages = HashSet<String>()

        // 1. Running App Processes (includes self & visible processes)
        val running = activityManager.runningAppProcesses ?: emptyList()
        val pids = running.map { it.pid }.toIntArray()
        val memInfos = if (pids.isNotEmpty()) {
            runCatching { activityManager.getProcessMemoryInfo(pids) }.getOrNull()
        } else null

        running.forEachIndexed { idx, proc ->
            val pkg = proc.pkgList?.firstOrNull() ?: proc.processName.substringBefore(':')
            seenPackages.add(pkg)
            val meta = packageCache.getMeta(pkg)
            val pssMb = memInfos?.getOrNull(idx)?.totalPss?.let { it / 1024f }
            val stateTag = when {
                proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> ProcessStateTag.FG
                proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> ProcessStateTag.SVC
                proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> ProcessStateTag.BGD
                else -> ProcessStateTag.CCH
            }
            val category = when {
                meta?.isSystem == true -> ProcessCategory.SYSTEM
                stateTag == ProcessStateTag.BGD || stateTag == ProcessStateTag.CCH -> ProcessCategory.BACKGROUND
                else -> ProcessCategory.APPS
            }
            val netBytes = (TrafficStats.getUidRxBytes(proc.uid).coerceAtLeast(0L) +
                TrafficStats.getUidTxBytes(proc.uid).coerceAtLeast(0L))
            result.add(
                ProcessEntry(
                    pid = proc.pid,
                    ppid = 0,
                    uid = proc.uid,
                    packageName = pkg,
                    displayName = meta?.displayName ?: proc.processName,
                    category = category,
                    stateTag = stateTag,
                    cpuPercent = null,
                    ramPssMb = pssMb,
                    ramUssMb = null,
                    netBytesTotal = netBytes,
                    oomScoreAdj = null,
                    uptimeSeconds = 0L,
                    isProtected = packageCache.isProtectedPackage(pkg, settings.whitelistPackages),
                    isFrozenOrStopped = false
                )
            )
        }

        // 2. Active recent packages via UsageStatsManager
        if (hasUsageStatsPermission()) {
            val end = System.currentTimeMillis()
            val start = end - 15 * 60 * 1000L
            val events = usageStatsManager.queryEvents(start, end)
            val ev = UsageEvents.Event()
            val recentActive = LinkedHashMap<String, Long>()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    ev.eventType == UsageEvents.Event.FOREGROUND_SERVICE_START
                ) {
                    recentActive[ev.packageName] = ev.timeStamp
                }
            }
            for ((pkg, ts) in recentActive) {
                if (!seenPackages.add(pkg)) continue
                val meta = packageCache.getMeta(pkg) ?: continue
                val netBytes = (TrafficStats.getUidRxBytes(meta.uid).coerceAtLeast(0L) +
                    TrafficStats.getUidTxBytes(meta.uid).coerceAtLeast(0L))
                val isRecent = (end - ts) < 60_000L
                result.add(
                    ProcessEntry(
                        pid = meta.uid, // Virtual PID surrogate in Tier 0 when OS hides PID
                        ppid = 0,
                        uid = meta.uid,
                        packageName = pkg,
                        displayName = meta.displayName,
                        category = if (meta.isSystem) ProcessCategory.SYSTEM else if (isRecent) ProcessCategory.APPS else ProcessCategory.BACKGROUND,
                        stateTag = if (isRecent) ProcessStateTag.BGD else ProcessStateTag.CCH,
                        cpuPercent = null,
                        ramPssMb = null,
                        ramUssMb = null,
                        netBytesTotal = netBytes,
                        oomScoreAdj = null,
                        uptimeSeconds = ((end - ts) / 1000L).coerceAtLeast(0L),
                        isProtected = packageCache.isProtectedPackage(pkg, settings.whitelistPackages),
                        isFrozenOrStopped = false
                    )
                )
            }
        }

        return result
    }

    fun getLastProcStatTotalJiffies(): Long = prevCoreTotal[0]

    private fun parseStatTextIntoBuffer(text: String, out: LongArray): Int {
        out.fill(0L)
        var cores = 0
        for (line in text.lineSequence()) {
            if (line.startsWith("cpu")) {
                val tokens = line.trim().split(Regex("\\s+"))
                if (tokens.size < 5) continue
                val tag = tokens[0]
                val slot = if (tag == "cpu") 0 else {
                    val idx = tag.removePrefix("cpu").toIntOrNull() ?: continue
                    if (idx + 1 > cores) cores = idx + 1
                    idx + 1
                }
                val base = 3 + slot * 8
                if (base + 7 >= out.size) continue
                for (k in 0 until 8) {
                    out[base + k] = tokens.getOrNull(k + 1)?.toLongOrNull() ?: 0L
                }
            } else if (line.startsWith("ctxt ") && out.size > 1) {
                out[1] = line.substringAfter("ctxt ").trim().toLongOrNull() ?: 0L
            } else if (line.startsWith("intr ") && out.size > 2) {
                out[2] = line.substringAfter("intr ").trim().substringBefore(' ').toLongOrNull() ?: 0L
            }
        }
        out[0] = cores.toLong()
        return cores
    }

    private fun parseMemInfoTextIntoBuffer(text: String, out: LongArray): Boolean {
        out.fill(0L)
        var any = false
        for (line in text.lineSequence()) {
            val idx = when {
                line.startsWith("MemTotal:") -> 0
                line.startsWith("MemFree:") -> 1
                line.startsWith("MemAvailable:") -> 2
                line.startsWith("Buffers:") -> 3
                line.startsWith("Cached:") -> 4
                line.startsWith("SReclaimable:") -> 5
                line.startsWith("SwapTotal:") -> 6
                line.startsWith("SwapFree:") -> 7
                line.startsWith("Active(anon):") -> 8
                line.startsWith("Inactive(anon):") -> 9
                line.startsWith("Slab:") -> 10
                else -> -1
            }
            if (idx >= 0) {
                out[idx] = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
                any = true
            }
        }
        return any
    }
}
