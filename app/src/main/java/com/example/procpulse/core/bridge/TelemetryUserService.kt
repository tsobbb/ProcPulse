package com.example.procpulse.core.bridge

import android.annotation.SuppressLint
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * Isolated UID 2000 (Shizuku) telemetry service.
 * Zero references to Context, PackageManager, Room, Compose, Coil, or ProcPulseApp
 * so this background Binder process stays tiny (< 15 MB RAM).
 */
class TelemetryUserService : ITelemetryUserService.Stub() {

    private val coreCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var prevSysTotalJiffies: Long = 0L
    private val prevProcTicks = HashMap<Int, Long>(512)
    private val curProcTicks = HashMap<Int, Long>(512)

    override fun destroy() {
        exitProcess(0)
    }

    override fun readSystemMetricsSnapshot(): String {
        val json = JSONObject()
        json.put("stat", readTextSafe("/proc/stat"))
        json.put("meminfo", readTextSafe("/proc/meminfo"))
        json.put("vmstat", readFilteredVmStat())
        json.put("diskstats", readFilteredDiskStats())

        val psiRes = probeNode("/proc/pressure/memory")
        json.put("psi", psiRes.first)
        json.put("psiStatus", psiRes.second)

        val zramRes = probeNode("/sys/block/zram0/mm_stat")
        json.put("zram", zramRes.first)
        json.put("zramStatus", zramRes.second)

        val gpuBusyRes = probeNode("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
        val gpuClockRes = probeNode("/sys/class/kgsl/kgsl-3d0/clock_mhz")
        val gpuMaxRes = probeNode("/sys/class/kgsl/kgsl-3d0/max_clock_mhz")
        json.put("gpuBusy", gpuBusyRes.first)
        json.put("gpuClock", gpuClockRes.first)
        json.put("gpuMax", gpuMaxRes.first)
        json.put(
            "gpuStatus",
            when {
                gpuBusyRes.second == "OK" || gpuClockRes.second == "OK" -> "OK"
                gpuBusyRes.second == "EACCES" || gpuClockRes.second == "EACCES" -> "EACCES"
                else -> "ENOENT"
            }
        )

        val (ddrFreq, ddrStatus) = readFirstDevfreqMhz()
        json.put("ddrFreq", ddrFreq)
        json.put("ddrStatus", ddrStatus)

        json.put("cpuCaps", readCpuCapsJson())
        json.put("thermals", readThermalZonesJson())
        return json.toString()
    }

    override fun listProcessesSnapshot(topLimit: Int): String {
        val uptimeSec = readUptimeSeconds()
        val statText = readTextSafe("/proc/stat")
        val sysTotal = parseTotalCpuJiffies(statText)
        val dSysTotal = if (prevSysTotalJiffies > 0L && sysTotal > prevSysTotalJiffies) {
            (sysTotal - prevSysTotalJiffies).toFloat()
        } else 0f

        val fgPkg = extractForegroundPackageQuick()

        curProcTicks.clear()
        val entries = ArrayList<ProcRawItem>(280)
        val procNames = File("/proc").list()

        if (procNames != null) {
            for (name in procNames) {
                if (name.isEmpty() || !name[0].isDigit()) continue
                val pid = name.toIntOrNull() ?: continue
                try {
                    val statLine = readTextSafe("/proc/$pid/stat")
                    if (statLine.isEmpty()) continue
                    val parsed = parsePidStat(statLine) ?: continue
                    val ticks = parsed.utime + parsed.stime
                    curProcTicks[pid] = ticks

                    var cpuPct: Float? = null
                    if (dSysTotal > 0f) {
                        val prev = prevProcTicks[pid]
                        if (prev != null && ticks >= prev) {
                            cpuPct = (((ticks - prev).toFloat() / dSysTotal) * 100f).coerceIn(0f, 100f)
                        }
                    }

                    val uid = readUidForPid(pid)
                    val cmdline = readCmdlineRaw(pid)
                    val statmRssKb = readStatmRssKb(pid)
                    val oomAdj = readTextSafe("/proc/$pid/oom_score_adj").toIntOrNull()
                    val uptime = if (uptimeSec > 0.0) {
                        (uptimeSec - (parsed.startTimeTicks / 100.0)).toLong().coerceAtLeast(0L)
                    } else 0L

                    entries.add(
                        ProcRawItem(
                            pid = pid,
                            ppid = parsed.ppid,
                            uid = uid,
                            comm = parsed.comm,
                            cmdline = cmdline,
                            state = parsed.state,
                            rssKb = statmRssKb,
                            oomAdj = oomAdj,
                            cpuPct = cpuPct,
                            uptimeSec = uptime
                        )
                    )
                } catch (_: Throwable) {
                    // Per-PID fault isolation: never abort batch if one PID exits or hits EACCES
                }
            }
        }

        if (sysTotal > 0L) {
            prevSysTotalJiffies = sysTotal
        }
        prevProcTicks.clear()
        prevProcTicks.putAll(curProcTicks)

        // Fallback to single-shot top/ps if /proc traversal yielded no entries or zero CPU deltas on first call
        var psFallback = ""
        var topFallback = ""
        if (entries.isEmpty()) {
            psFallback = runExec("ps -A -o PID,PPID,UID,NI,RSS,S,ELAPSED,ARGS", maxLines = 600)
        } else if (dSysTotal <= 0f || entries.none { (it.cpuPct ?: 0f) > 0f }) {
            // Optional fallback when proc jiffies haven't accumulated a delta yet or are restricted
            topFallback = runExec("top -b -n 1 -q -o PID,UID,%CPU,RES,S,ARGS", maxLines = 120)
        }

        val root = JSONObject()
        root.put("fgPkg", fgPkg)
        root.put("coreCount", coreCount)
        root.put("psFallback", psFallback)
        root.put("topFallback", topFallback)

        if (topLimit > 0) {
            val topCpu = entries
                .filter { (it.cpuPct ?: 0f) > 0f || it.rssKb > 0 }
                .sortedWith(compareByDescending<ProcRawItem> { it.cpuPct ?: -1f }.thenByDescending { it.rssKb })
                .take(topLimit)
            val topRam = entries
                .filter { it.rssKb > 0 }
                .sortedByDescending { it.rssKb }
                .take(topLimit)
            root.put("topCpu", encodeProcArray(topCpu))
            root.put("topRam", encodeProcArray(topRam))
        } else {
            root.put("procs", encodeProcArray(entries))
        }
        return root.toString()
    }

    override fun readProcessDetail(pid: Int, packageName: String): String {
        val obj = JSONObject()
        obj.put("smaps", readTextSafe("/proc/$pid/smaps_rollup"))
        obj.put("oomAdj", readTextSafe("/proc/$pid/oom_score_adj"))
        obj.put("stat", readTextSafe("/proc/$pid/stat"))
        obj.put("fdCount", runCatching { File("/proc/$pid/fd").list()?.size ?: -1 }.getOrDefault(-1))
        obj.put("meminfo", runExec("dumpsys meminfo $pid", maxLines = 80))
        if (packageName.isNotBlank() && packageName.contains('.')) {
            obj.put("activityServices", runExec("dumpsys activity services $packageName", maxLines = 60))
            obj.put("wakeLocks", runExec("dumpsys power | grep -i '$packageName' | head -n 20", maxLines = 25))
            obj.put("appops", runExec("cmd appops get $packageName RUN_ANY_IN_BACKGROUND", maxLines = 10))
        }
        return obj.toString()
    }

    override fun readSocketsSnapshot(filterUid: Int): String {
        val ssOut = runExec("ss -tunapn", maxLines = 250)
        if (ssOut.isNotBlank() && ssOut.contains("Netid")) {
            return JSONObject().apply {
                put("mode", "ss")
                put("data", ssOut)
            }.toString()
        }
        return JSONObject().apply {
            put("mode", "proc")
            put("tcp", readTextSafe("/proc/net/tcp"))
            put("tcp6", readTextSafe("/proc/net/tcp6"))
            put("udp", readTextSafe("/proc/net/udp"))
            put("udp6", readTextSafe("/proc/net/udp6"))
        }.toString()
    }

    override fun readLogcatSnapshot(pid: Int, maxLines: Int): String {
        val cmd = if (pid > 0) {
            "logcat -d -v threadtime --pid=$pid -t ${maxLines.coerceIn(10, 400)}"
        } else {
            "logcat -d -v threadtime -t ${maxLines.coerceIn(10, 200)}"
        }
        return runExec(cmd, maxLines = 450)
    }

    override fun readSurfaceFlingerFps(): String {
        return runExec("dumpsys SurfaceFlinger --latency", maxLines = 150)
    }

    override fun readLagDiagnosticsSnapshot(
        includeSlowDumpsys: Boolean,
        activePackage: String,
        timestatsAction: Int
    ): String {
        val root = JSONObject()

        // Timestats control: 1 = enable + clear, 2 = dump + clear, 3 = disable
        when (timestatsAction) {
            1 -> runExec("dumpsys SurfaceFlinger --timestats -enable -clear", maxLines = 20)
            2 -> root.put("timestats", runExec("dumpsys SurfaceFlinger --timestats -dump -clear", maxLines = 180))
            3 -> runExec("dumpsys SurfaceFlinger --timestats -disable", maxLines = 20)
        }

        // Foreground package detection via dumpsys activity top / window
        val topOut = runExec("dumpsys activity top | grep -E 'ACTIVITY ' | head -n 5", maxLines = 10)
        val fgPkg = parseActivityTopPackage(topOut).ifBlank {
            val winOut = runExec("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | head -n 5", maxLines = 10)
            parseWindowFocusPackage(winOut)
        }.ifBlank { activePackage }
        root.put("fgPackage", fgPkg)

        // Graphics frame stats / jank breakdown for foreground app
        if (fgPkg.isNotBlank() && fgPkg.contains('.')) {
            root.put("gfxinfo", runExec("dumpsys gfxinfo $fgPkg | head -n 55", maxLines = 60))
        }

        // Thermal service throttling state
        root.put("thermalservice", runExec("dumpsys thermalservice | head -n 45", maxLines = 50))

        // VMStat & Diskstats deltas
        root.put("vmstat", readFilteredVmStat())
        root.put("diskstats", readFilteredDiskStats())

        if (includeSlowDumpsys) {
            root.put("cpuinfo", runExec("dumpsys cpuinfo | head -n 35", maxLines = 40))
            root.put("alarms", runExec("dumpsys alarm | grep -iE 'Wakeups:|u0a' | head -n 35", maxLines = 40))
            root.put("jobs", runExec("dumpsys jobscheduler | grep -E 'JOB #|u0a' | head -n 30", maxLines = 35))
            root.put("diskFree", runExec("dumpsys diskstats | head -n 25", maxLines = 30))
            root.put(
                "lmkLogcat",
                runExec("logcat -b events -d -v epoch -t 200 | grep -E 'am_kill|lmkd|lowmemorykiller'", maxLines = 60)
            )
        }

        return root.toString()
    }

    override fun executeCommand(command: String): Int {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            proc.waitFor()
        } catch (_: Throwable) {
            -1
        }
    }

    private fun probeNode(path: String): Pair<String, String> {
        return try {
            val f = File(path)
            if (!f.exists()) {
                "" to "ENOENT"
            } else {
                val text = f.readText().trim()
                text to "OK"
            }
        } catch (t: Throwable) {
            val msg = t.message?.lowercase().orEmpty()
            val status = when {
                t is FileNotFoundException && msg.contains("enoent") -> "ENOENT"
                msg.contains("permission denied") || msg.contains("eacces") || msg.contains("eperm") -> "EACCES"
                else -> "EACCES"
            }
            "" to status
        }
    }

    private fun readTextSafe(path: String): String {
        return try {
            File(path).readText().trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun readFilteredVmStat(): String {
        val raw = readTextSafe("/proc/vmstat")
        if (raw.isEmpty()) return ""
        val sb = StringBuilder()
        for (line in raw.lineSequence()) {
            if (line.startsWith("pswpin ") ||
                line.startsWith("pswpout ") ||
                line.startsWith("pgmajfault ") ||
                line.startsWith("allocstall") ||
                line.startsWith("compact_stall ")
            ) {
                sb.appendLine(line)
            }
        }
        return sb.toString().trim()
    }

    private fun readFilteredDiskStats(): String {
        val raw = readTextSafe("/proc/diskstats")
        if (raw.isEmpty()) return ""
        val sb = StringBuilder()
        for (line in raw.lineSequence()) {
            val tokens = line.trim().split(Regex("\\s+"))
            val dev = tokens.getOrNull(2) ?: continue
            if (dev == "mmcblk0" || dev == "sda" || dev == "sdb" || dev == "nvme0n1") {
                sb.appendLine(line.trim())
            }
        }
        return sb.toString().trim()
    }

    private fun readCpuCapsJson(): JSONArray {
        val arr = JSONArray()
        for (i in 0 until coreCount) {
            try {
                val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
                val online = if (i == 0) 1 else (readTextSafe("/sys/devices/system/cpu/cpu$i/online").toIntOrNull() ?: 1)
                val sMin = readTextSafe("$base/scaling_min_freq").toLongOrNull()?.div(1000L)?.toInt() ?: 0
                val sMax = readTextSafe("$base/scaling_max_freq").toLongOrNull()?.div(1000L)?.toInt() ?: 0
                val cMax = readTextSafe("$base/cpuinfo_max_freq").toLongOrNull()?.div(1000L)?.toInt() ?: sMax
                val gov = readTextSafe("$base/scaling_governor")
                arr.put(
                    JSONObject().apply {
                        put("core", i)
                        put("online", online == 1)
                        put("sMin", sMin)
                        put("sMax", sMax)
                        put("cMax", cMax)
                        put("gov", gov)
                    }
                )
            } catch (_: Throwable) {
            }
        }
        return arr
    }

    private fun readFirstDevfreqMhz(): Pair<Int, String> {
        return try {
            val dir = File("/sys/class/devfreq")
            if (!dir.exists()) return -1 to "ENOENT"
            val candidates = dir.listFiles() ?: return -1 to "EACCES"
            val candidate = candidates.firstOrNull {
                val n = it.name.lowercase()
                n.contains("ddrss") || n.contains("ddr") || n.contains("memlat") || n.contains("bus")
            } ?: return -1 to "ENOENT"
            val probe = probeNode("${candidate.absolutePath}/cur_freq")
            if (probe.second != "OK") return -1 to probe.second
            val hz = probe.first.toLongOrNull() ?: return -1 to "ENOENT"
            val mhz = if (hz > 10_000_000L) (hz / 1_000_000L).toInt() else (hz / 1000L).toInt()
            mhz to "OK"
        } catch (_: Throwable) {
            -1 to "EACCES"
        }
    }

    private fun readThermalZonesJson(): JSONArray {
        val arr = JSONArray()
        for (i in 0 until 45) {
            try {
                val base = "/sys/class/thermal/thermal_zone$i"
                val type = readTextSafe("$base/type")
                if (type.isEmpty()) continue
                val tempVal = readTextSafe("$base/temp").toLongOrNull() ?: continue
                arr.put(
                    JSONObject().apply {
                        put("id", i)
                        put("type", type)
                        put("temp", tempVal)
                    }
                )
            } catch (_: Throwable) {
            }
        }
        return arr
    }

    private fun readUidForPid(pid: Int): Int {
        return try {
            Os.stat("/proc/$pid").st_uid
        } catch (_: ErrnoException) {
            -1
        } catch (_: Throwable) {
            -1
        }.let { uid ->
            if (uid >= 0) uid else parseUidFromStatus(readTextSafe("/proc/$pid/status"))
        }
    }

    private fun parseUidFromStatus(statusText: String): Int {
        for (line in statusText.lineSequence()) {
            if (line.startsWith("Uid:")) {
                return line.substringAfter("Uid:").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: 0
            }
        }
        return 0
    }

    private fun readCmdlineRaw(pid: Int): String {
        return try {
            val bytes = File("/proc/$pid/cmdline").readBytes()
            if (bytes.isEmpty()) return ""
            val chars = CharArray(bytes.size)
            for (i in bytes.indices) {
                val b = bytes[i]
                chars[i] = if (b.toInt() == 0) ' ' else (b.toInt() and 0xFF).toChar()
            }
            String(chars).trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun readStatmRssKb(pid: Int): Long {
        val statm = readTextSafe("/proc/$pid/statm")
        if (statm.isEmpty()) return 0L
        val parts = statm.split(Regex("\\s+"))
        val residentPages = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return residentPages * 4L
    }

    private fun readUptimeSeconds(): Double {
        val txt = readTextSafe("/proc/uptime")
        return txt.substringBefore(' ').toDoubleOrNull() ?: 0.0
    }

    private fun parseTotalCpuJiffies(statText: String): Long {
        val firstLine = statText.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return 0L
        val tokens = firstLine.trim().split(Regex("\\s+"))
        var sum = 0L
        for (i in 1 until tokens.size) {
            sum += tokens[i].toLongOrNull() ?: 0L
        }
        return sum
    }

    private data class PidStatParsed(
        val comm: String,
        val state: String,
        val ppid: Int,
        val utime: Long,
        val stime: Long,
        val startTimeTicks: Long
    )

    private fun parsePidStat(statLine: String): PidStatParsed? {
        val lparen = statLine.indexOf('(')
        val rparen = statLine.lastIndexOf(')')
        if (lparen < 0 || rparen <= lparen || rparen + 2 >= statLine.length) return null
        val comm = statLine.substring(lparen + 1, rparen)
        val parts = statLine.substring(rparen + 2).trim().split(Regex("\\s+"))
        if (parts.size < 20) return null
        val state = parts[0]
        val ppid = parts[1].toIntOrNull() ?: 0
        val utime = parts[11].toLongOrNull() ?: 0L
        val stime = parts[12].toLongOrNull() ?: 0L
        val startTime = parts[19].toLongOrNull() ?: 0L
        return PidStatParsed(comm, state, ppid, utime, stime, startTime)
    }

    private data class ProcRawItem(
        val pid: Int,
        val ppid: Int,
        val uid: Int,
        val comm: String,
        val cmdline: String,
        val state: String,
        val rssKb: Long,
        val oomAdj: Int?,
        val cpuPct: Float?,
        val uptimeSec: Long
    )

    private fun encodeProcArray(list: List<ProcRawItem>): JSONArray {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject().apply {
                    put("pid", item.pid)
                    put("ppid", item.ppid)
                    put("uid", item.uid)
                    put("comm", item.comm)
                    put("cmd", item.cmdline)
                    put("st", item.state)
                    put("rss", item.rssKb)
                    if (item.oomAdj != null) put("oom", item.oomAdj)
                    if (item.cpuPct != null) put("cpu", item.cpuPct.toDouble())
                    put("up", item.uptimeSec)
                }
            )
        }
        return arr
    }

    private fun extractForegroundPackageQuick(): String {
        val out = runExec("dumpsys activity top | grep -E 'ACTIVITY ' | head -n 3", maxLines = 5)
        return parseActivityTopPackage(out)
    }

    private fun parseActivityTopPackage(out: String): String {
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("ACTIVITY ")) {
                val comp = t.removePrefix("ACTIVITY ").trim().substringBefore(' ')
                val pkg = comp.substringBefore('/')
                if (pkg.contains('.')) return pkg
            }
        }
        return ""
    }

    private fun parseWindowFocusPackage(out: String): String {
        val match = Regex("""([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)/""").find(out)
        return match?.groupValues?.getOrNull(1).orEmpty()
    }

    @SuppressLint("NewApi")
    private fun runExec(cmd: String, maxLines: Int = 800): String {
        return try {
            val proc = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val sb = StringBuilder()
            var line = reader.readLine()
            var lines = 0
            while (line != null && lines < maxLines) {
                sb.appendLine(line)
                line = reader.readLine()
                lines++
            }
            proc.waitFor()
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }
}
