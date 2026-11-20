package com.example.procpulse.core.nativebridge

import com.example.procpulse.core.model.SysfsAvailability
import java.io.File

/**
 * High-speed procfs/sysfs reader backed by C++20 JNI (libprocpulse_native.so),
 * with an automatic pure-Kotlin/Java fallback for JVM unit tests or environments
 * where the native shared object isn't loaded.
 */
object NativeProcReader {

    val isNativeLoaded: Boolean = try {
        System.loadLibrary("procpulse_native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    private external fun nativeReadIntFile(path: String): Long
    private external fun nativeReadTextFile(path: String): String?
    private external fun nativeReadNodeWithStatus(path: String, outText: Array<String?>): Int
    private external fun nativeReadCpuCurFreqs(numCores: Int, outKhz: LongArray): Int
    private external fun nativeReadThermalZones(maxZones: Int, outIds: IntArray, outTemps: LongArray, outTypes: Array<String?>): Int
    private external fun nativeReadProcStat(outArr: LongArray): Int
    private external fun nativeReadProcStatQueue(outArr: LongArray): Boolean
    private external fun nativeReadMemInfo(outArr: LongArray): Boolean
    private external fun nativeReadPsiMemory(outArr: DoubleArray): Boolean
    private external fun nativeReadZramMmStat(outArr: LongArray): Boolean
    private external fun nativeReadVmStat(outArr: LongArray): Boolean
    private external fun nativeReadDiskStats(outArr: LongArray): Boolean
    private external fun nativeListPids(outPids: IntArray): Int
    private external fun nativeReadProcessDetail(pid: Int, outArr: LongArray): Boolean

    data class NodeReadOutcome(
        val text: String?,
        val availability: SysfsAvailability
    )

    fun readNodeWithStatus(path: String): NodeReadOutcome {
        if (isNativeLoaded) {
            val out = arrayOfNulls<String>(1)
            val rc = nativeReadNodeWithStatus(path, out)
            return when {
                rc == 0 && out[0] != null -> NodeReadOutcome(out[0], SysfsAvailability.AVAILABLE)
                rc == -2 -> NodeReadOutcome(null, SysfsAvailability.NOT_IN_KERNEL) // -ENOENT
                else -> NodeReadOutcome(null, SysfsAvailability.SELINUX_DENIED) // -EACCES / -EPERM
            }
        }
        return try {
            val f = File(path)
            if (!f.exists()) {
                NodeReadOutcome(null, SysfsAvailability.NOT_IN_KERNEL)
            } else if (!f.canRead()) {
                NodeReadOutcome(null, SysfsAvailability.SELINUX_DENIED)
            } else {
                NodeReadOutcome(f.readText().trim(), SysfsAvailability.AVAILABLE)
            }
        } catch (_: SecurityException) {
            NodeReadOutcome(null, SysfsAvailability.SELINUX_DENIED)
        } catch (_: Throwable) {
            NodeReadOutcome(null, SysfsAvailability.SELINUX_DENIED)
        }
    }

    fun readProcStatQueue(outBuffer: LongArray): Boolean {
        if (isNativeLoaded && nativeReadProcStatQueue(outBuffer)) return true
        return try {
            val lines = File("/proc/stat").takeIf { it.canRead() }?.readLines() ?: return false
            outBuffer[0] = 0L
            outBuffer[1] = 0L
            for (line in lines) {
                if (line.startsWith("procs_running ")) {
                    outBuffer[0] = line.substringAfter("procs_running ").trim().toLongOrNull() ?: 0L
                } else if (line.startsWith("procs_blocked ")) {
                    outBuffer[1] = line.substringAfter("procs_blocked ").trim().toLongOrNull() ?: 0L
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun readVmStat(outBuffer: LongArray): Boolean {
        if (isNativeLoaded && nativeReadVmStat(outBuffer)) return true
        return try {
            val lines = File("/proc/vmstat").takeIf { it.canRead() }?.readLines() ?: return false
            outBuffer.fill(0L)
            for (line in lines) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) continue
                val v = parts[1].toLongOrNull() ?: 0L
                when {
                    parts[0] == "pswpin" -> outBuffer[0] = v
                    parts[0] == "pswpout" -> outBuffer[1] = v
                    parts[0] == "pgmajfault" -> outBuffer[2] = v
                    parts[0].startsWith("allocstall") -> outBuffer[3] += v
                    parts[0] == "compact_stall" -> outBuffer[4] = v
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun readDiskStats(outBuffer: LongArray): Boolean {
        if (isNativeLoaded && nativeReadDiskStats(outBuffer)) return true
        return try {
            val lines = File("/proc/diskstats").takeIf { it.canRead() }?.readLines() ?: return false
            for (line in lines) {
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size >= 13 && (cols[2] == "mmcblk0" || cols[2] == "sda" || cols[2] == "nvme0n1")) {
                    outBuffer[0] = cols[5].toLongOrNull() ?: 0L
                    outBuffer[1] = cols[9].toLongOrNull() ?: 0L
                    outBuffer[2] = cols[12].toLongOrNull() ?: 0L
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun readCpuCurFreqsBatch(numCores: Int, outKhz: LongArray): Int {
        if (isNativeLoaded) {
            return nativeReadCpuCurFreqs(numCores, outKhz)
        }
        var ok = 0
        for (i in 0 until minOf(numCores, outKhz.size)) {
            val v = readLongFile("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") ?: 0L
            outKhz[i] = v
            if (v > 0L) ok++
        }
        return ok
    }

    fun readThermalZonesBatch(maxZones: Int, outIds: IntArray, outTemps: LongArray, outTypes: Array<String?>): Int {
        if (isNativeLoaded) {
            return nativeReadThermalZones(maxZones, outIds, outTemps, outTypes)
        }
        var count = 0
        for (i in 0 until maxZones) {
            if (count >= outIds.size) break
            val temp = readLongFile("/sys/class/thermal/thermal_zone$i/temp") ?: continue
            val type = readTextFile("/sys/class/thermal/thermal_zone$i/type") ?: continue
            outIds[count] = i
            outTemps[count] = temp
            outTypes[count] = type
            count++
        }
        return count
    }

    fun readLongFile(path: String): Long? {
        if (isNativeLoaded) {
            val v = nativeReadIntFile(path)
            if (v != Long.MIN_VALUE) return v
        }
        return try {
            File(path).takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    fun readTextFile(path: String): String? {
        if (isNativeLoaded) {
            val s = nativeReadTextFile(path)
            if (s != null) return s
        }
        return try {
            File(path).takeIf { it.canRead() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    fun readProcStat(outBuffer: LongArray): Int {
        if (isNativeLoaded) {
            val cores = nativeReadProcStat(outBuffer)
            if (cores >= 0) return cores
        }
        return readProcStatFallback(outBuffer)
    }

    fun readMemInfo(outBuffer: LongArray): Boolean {
        if (isNativeLoaded && nativeReadMemInfo(outBuffer)) return true
        return readMemInfoFallback(outBuffer)
    }

    fun readPsiMemory(outBuffer: DoubleArray): Boolean {
        if (isNativeLoaded && nativeReadPsiMemory(outBuffer)) return true
        return try {
            val text = File("/proc/pressure/memory").takeIf { it.canRead() }?.readText() ?: return false
            val someMatch = Regex("""some avg10=([0-9.]+)""").find(text)
            val fullMatch = Regex("""full avg10=([0-9.]+)""").find(text)
            outBuffer[0] = someMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            outBuffer[1] = fullMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun readZramMmStat(outBuffer: LongArray): Boolean {
        if (isNativeLoaded && nativeReadZramMmStat(outBuffer)) return true
        return try {
            val text = File("/sys/block/zram0/mm_stat").takeIf { it.canRead() }?.readText()?.trim() ?: return false
            val parts = text.split(Regex("\\s+"))
            if (parts.size < 3) return false
            outBuffer[0] = parts[0].toLongOrNull() ?: 0L
            outBuffer[1] = parts[1].toLongOrNull() ?: 0L
            outBuffer[2] = parts[2].toLongOrNull() ?: 0L
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun listPids(outPids: IntArray): Int {
        if (isNativeLoaded) {
            val count = nativeListPids(outPids)
            if (count > 0) return count
        }
        return try {
            val files = File("/proc").listFiles() ?: return 0
            var idx = 0
            for (f in files) {
                if (idx >= outPids.size) break
                val pid = f.name.toIntOrNull()
                if (pid != null && pid > 0) {
                    outPids[idx++] = pid
                }
            }
            idx
        } catch (_: Throwable) {
            0
        }
    }

    fun readProcessDetail(pid: Int, outBuffer: LongArray): Boolean {
        if (isNativeLoaded && nativeReadProcessDetail(pid, outBuffer)) return true
        return false
    }

    private fun readProcStatFallback(out: LongArray): Int {
        return try {
            val lines = File("/proc/stat").takeIf { it.canRead() }?.readLines() ?: return -1
            out.fill(0L)
            var cores = 0
            for (line in lines) {
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
            cores
        } catch (_: Throwable) {
            -1
        }
    }

    private fun readMemInfoFallback(out: LongArray): Boolean {
        return try {
            val lines = File("/proc/meminfo").takeIf { it.canRead() }?.readLines() ?: return false
            out.fill(0L)
            for (line in lines) {
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
                    val num = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
                    out[idx] = num
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
