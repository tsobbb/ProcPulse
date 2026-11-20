package com.example.procpulse.core.bridge

import android.annotation.SuppressLint
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's privileged shell process (UID 2000).
 * Reads /proc, /sys, and dumpsys directly via Binder IPC without spawning
 * new sh processes on every UI tick for procfs reads.
 */
class TelemetryUserService : ITelemetryUserService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun readSystemMetricsSnapshot(): String {
        val json = JSONObject()
        json.put("stat", readTextSafe("/proc/stat"))
        json.put("meminfo", readTextSafe("/proc/meminfo"))
        json.put("psi", readTextSafe("/proc/pressure/memory"))
        json.put("zram", readTextSafe("/sys/block/zram0/mm_stat"))
        json.put("gpuBusy", readTextSafe("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"))
        json.put("gpuClock", readTextSafe("/sys/class/kgsl/kgsl-3d0/clock_mhz"))
        json.put("gpuMax", readTextSafe("/sys/class/kgsl/kgsl-3d0/max_clock_mhz"))
        json.put("ddrFreq", readFirstDevfreqMhz())
        json.put("thermals", readThermalZonesJson())
        return json.toString()
    }

    override fun listProcessesSnapshot(): String {
        // Use single-shot ps -A which executes fast in shell UID 2000
        val out = runExec("ps -A -o PID,PPID,UID,NI,RSS,S,ELAPSED,ARGS")
        val root = JSONObject()
        root.put("ps", out)
        // Also collect per-pid /proc/<pid>/stat jiffies directly if readable by shell
        val jiffies = JSONObject()
        val procDir = File("/proc")
        procDir.listFiles()?.forEach { f ->
            val pid = f.name.toIntOrNull() ?: return@forEach
            val stat = readTextSafe("/proc/$pid/stat")
            if (stat.isNotEmpty()) {
                jiffies.put(pid.toString(), stat)
            }
        }
        root.put("procStats", jiffies)
        return root.toString()
    }

    override fun readProcessDetail(pid: Int, packageName: String): String {
        val obj = JSONObject()
        obj.put("smaps", readTextSafe("/proc/$pid/smaps_rollup"))
        obj.put("oomAdj", readTextSafe("/proc/$pid/oom_score_adj"))
        obj.put("stat", readTextSafe("/proc/$pid/stat"))
        obj.put("fdCount", File("/proc/$pid/fd").list()?.size ?: -1)
        obj.put("meminfo", runExec("dumpsys meminfo $pid"))
        if (packageName.isNotBlank()) {
            obj.put("activityServices", runExec("dumpsys activity services $packageName"))
            obj.put("wakeLocks", runExec("dumpsys power | grep -i '$packageName'"))
            obj.put("appops", runExec("cmd appops get $packageName RUN_ANY_IN_BACKGROUND"))
        }
        return obj.toString()
    }

    override fun readSocketsSnapshot(filterUid: Int): String {
        val ssOut = runExec("ss -tunapn")
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
        return runExec(cmd)
    }

    override fun readSurfaceFlingerFps(): String {
        val out = runExec("dumpsys SurfaceFlinger --latency")
        return out
    }

    override fun executeCommand(command: String): Int {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            proc.waitFor()
        } catch (_: Throwable) {
            -1
        }
    }

    private fun readTextSafe(path: String): String {
        return try {
            val f = File(path)
            if (f.canRead()) f.readText().trim() else ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun readFirstDevfreqMhz(): Int {
        return try {
            val dir = File("/sys/class/devfreq")
            val candidate = dir.listFiles()?.firstOrNull {
                val n = it.name.lowercase()
                n.contains("ddrss") || n.contains("ddr") || n.contains("memlat") || n.contains("bus")
            } ?: return -1
            val hz = readTextSafe("${candidate.absolutePath}/cur_freq").toLongOrNull() ?: return -1
            if (hz > 10_000_000L) (hz / 1_000_000L).toInt() else (hz / 1000L).toInt()
        } catch (_: Throwable) {
            -1
        }
    }

    private fun readThermalZonesJson(): JSONArray {
        val arr = JSONArray()
        for (i in 0 until 45) {
            val base = "/sys/class/thermal/thermal_zone$i"
            val type = readTextSafe("$base/type")
            val tempStr = readTextSafe("$base/temp")
            val tempVal = tempStr.toLongOrNull() ?: continue
            if (type.isNotEmpty()) {
                arr.put(JSONObject().apply {
                    put("id", i)
                    put("type", type)
                    put("temp", tempVal)
                })
            }
        }
        return arr
    }

    @SuppressLint("NewApi")
    private fun runExec(cmd: String): String {
        return try {
            val proc = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val sb = StringBuilder()
            var line = reader.readLine()
            var lines = 0
            while (line != null && lines < 1200) {
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
