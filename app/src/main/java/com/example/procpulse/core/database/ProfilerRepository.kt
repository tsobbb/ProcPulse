package com.example.procpulse.core.database

import com.example.procpulse.core.model.LmkKillEvent
import com.example.procpulse.core.model.ProfilerTopProcess
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfilerRepository(
    private val profilerDao: ProfilerDao,
    private val watchdogDao: WatchdogDao
) {
    val sessions: Flow<List<ProfilerSessionEntity>> = profilerDao.observeAllSessions()
    val watchdogEvents: Flow<List<WatchdogEventEntity>> = watchdogDao.observeEvents()

    fun observeSamples(sessionId: Long): Flow<List<ProfilerSampleEntity>> =
        profilerDao.observeSamplesForSession(sessionId)

    suspend fun getSession(sessionId: Long): ProfilerSessionEntity? =
        profilerDao.getSessionById(sessionId)

    suspend fun getSamples(sessionId: Long): List<ProfilerSampleEntity> =
        profilerDao.getSamplesForSession(sessionId)

    suspend fun startSession(
        title: String,
        intervalMs: Long,
        targetDurationSec: Int,
        tierUsed: String,
        isLagDiagnostic: Boolean = false
    ): Long {
        val entity = ProfilerSessionEntity(
            title = title,
            startTimeMs = System.currentTimeMillis(),
            endTimeMs = null,
            intervalMs = intervalMs,
            targetDurationSec = targetDurationSec,
            tierUsed = tierUsed,
            isLagDiagnostic = isLagDiagnostic
        )
        return profilerDao.insertSession(entity)
    }

    suspend fun recordSample(sample: ProfilerSampleEntity) {
        profilerDao.insertSample(sample)
    }

    suspend fun finishSession(
        sessionId: Long,
        peakCpu: Float,
        peakTemp: Float,
        minFreeRamMb: Long,
        avgCurrentMa: Float,
        sampleCount: Int
    ) {
        val existing = profilerDao.getSessionById(sessionId) ?: return
        profilerDao.updateSession(
            existing.copy(
                endTimeMs = System.currentTimeMillis(),
                peakCpuPercent = peakCpu,
                peakTempCelsius = peakTemp,
                minFreeRamMb = minFreeRamMb,
                avgCurrentMa = avgCurrentMa,
                sampleCount = sampleCount
            )
        )
    }

    suspend fun deleteSession(sessionId: Long) {
        profilerDao.deleteSession(sessionId)
    }

    suspend fun logWatchdogEvent(
        ruleType: String,
        triggerPackage: String?,
        description: String,
        actionTaken: String
    ) {
        watchdogDao.insertEvent(
            WatchdogEventEntity(
                timestampMs = System.currentTimeMillis(),
                ruleType = ruleType,
                triggerPackage = triggerPackage,
                description = description,
                actionTaken = actionTaken
            )
        )
    }

    suspend fun clearWatchdogEvents() {
        watchdogDao.clearEvents()
    }

    suspend fun exportSessionAsCsv(sessionId: Long): String {
        val session = profilerDao.getSessionById(sessionId)
        val samples = profilerDao.getSamplesForSession(sessionId)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("# ProcPulse Blackbox Session: ${session?.title ?: sessionId} (LagDiag=${session?.isLagDiagnostic == true})")
        sb.appendLine(
            "timestamp,cpu_percent,cluster_freqs,soc_temp_c,battery_temp_c,current_ma,free_ram_mb,mem_avail_mb," +
                "zram_used_mb,fps,jank_pct,jank_stage,renderer,fg_pkg,cpu_capped,procs_run,procs_blk," +
                "pswpin_s,pswpout_s,pgmajfault_s,allocstall_s,iowait_pct,iobusy_pct,top_processes"
        )
        for (s in samples) {
            val ts = fmt.format(Date(s.timestampMs))
            val escapedTop = s.topProcessesJson.replace("\"", "\"\"")
            val escapedFreqs = s.clusterFreqsSummary.replace("\"", "\"\"")
            val escapedStage = (s.jankStage ?: "").replace("\"", "\"\"")
            sb.appendLine(
                "$ts,${"%.1f".format(Locale.US, s.cpuTotalPercent)},\"$escapedFreqs\"," +
                    "${"%.1f".format(Locale.US, s.tempCelsius)},${"%.1f".format(Locale.US, s.batteryTempCelsius)}," +
                    "${"%.1f".format(Locale.US, s.currentMa)},${s.freeRamMb},${s.memAvailableMb},${s.zramUsedMb}," +
                    "${s.fps?.let { "%.1f".format(Locale.US, it) } ?: ""}," +
                    "${s.jankPercent?.let { "%.1f".format(Locale.US, it) } ?: ""}," +
                    "\"$escapedStage\",\"${s.rendererType ?: ""}\",\"${s.foregroundPackage ?: ""}\"," +
                    "${s.isCpuCapped},${s.procsRunning},${s.procsBlocked}," +
                    "${"%.1f".format(Locale.US, s.pswpinPerSec)},${"%.1f".format(Locale.US, s.pswpoutPerSec)}," +
                    "${"%.1f".format(Locale.US, s.pgmajfaultPerSec)},${"%.1f".format(Locale.US, s.allocstallPerSec)}," +
                    "${"%.1f".format(Locale.US, s.ioWaitPercent)},${"%.1f".format(Locale.US, s.ioBusyPercent)}," +
                    "\"$escapedTop\""
            )
        }
        return sb.toString()
    }

    suspend fun exportSessionAsJson(sessionId: Long): String {
        val session = profilerDao.getSessionById(sessionId)
        val samples = profilerDao.getSamplesForSession(sessionId)
        val root = JSONObject()
        if (session != null) {
            root.put("id", session.id)
            root.put("title", session.title)
            root.put("isLagDiagnostic", session.isLagDiagnostic)
            root.put("startTimeMs", session.startTimeMs)
            root.put("endTimeMs", session.endTimeMs ?: JSONObject.NULL)
            root.put("intervalMs", session.intervalMs)
            root.put("tierUsed", session.tierUsed)
            root.put("peakCpuPercent", session.peakCpuPercent)
            root.put("peakTempCelsius", session.peakTempCelsius)
            root.put("minFreeRamMb", session.minFreeRamMb)
            root.put("avgCurrentMa", session.avgCurrentMa)
        }
        val arr = JSONArray()
        for (s in samples) {
            val obj = JSONObject()
            obj.put("timestampMs", s.timestampMs)
            obj.put("cpuTotalPercent", s.cpuTotalPercent)
            obj.put("clusterFreqs", s.clusterFreqsSummary)
            obj.put("scalingMaxSummary", s.scalingMaxSummary)
            obj.put("isCpuCapped", s.isCpuCapped)
            obj.put("procsRunning", s.procsRunning)
            obj.put("procsBlocked", s.procsBlocked)
            obj.put("tempCelsius", s.tempCelsius)
            obj.put("batteryTempCelsius", s.batteryTempCelsius)
            obj.put("currentMa", s.currentMa)
            obj.put("freeRamMb", s.freeRamMb)
            obj.put("memAvailableMb", s.memAvailableMb)
            obj.put("zramUsedMb", s.zramUsedMb)
            obj.put("pswpinPerSec", s.pswpinPerSec)
            obj.put("pswpoutPerSec", s.pswpoutPerSec)
            obj.put("pgmajfaultPerSec", s.pgmajfaultPerSec)
            obj.put("allocstallPerSec", s.allocstallPerSec)
            obj.put("ioWaitPercent", s.ioWaitPercent)
            obj.put("ioBusyPercent", s.ioBusyPercent)
            obj.put("fps", s.fps ?: JSONObject.NULL)
            obj.put("jankPercent", s.jankPercent ?: JSONObject.NULL)
            obj.put("jankStage", s.jankStage ?: JSONObject.NULL)
            obj.put("rendererType", s.rendererType ?: JSONObject.NULL)
            obj.put("foregroundPackage", s.foregroundPackage ?: JSONObject.NULL)
            obj.put("amKillEvents", runCatching { JSONArray(s.amKillEventsJson) }.getOrElse { JSONArray() })
            obj.put("topProcesses", runCatching { JSONArray(s.topProcessesJson) }.getOrElse { JSONArray() })
            arr.put(obj)
        }
        root.put("samples", arr)
        return root.toString(2)
    }

    companion object {
        fun encodeTopProcesses(list: List<ProfilerTopProcess>): String {
            val arr = JSONArray()
            for (item in list.take(5)) {
                arr.put(
                    JSONObject().apply {
                        put("pid", item.pid)
                        put("pkg", item.packageName)
                        put("name", item.displayName)
                        put("cpu", item.cpuPercent)
                        put("ram", item.ramMb)
                        put("wl", item.wakeLockCount)
                    }
                )
            }
            return arr.toString()
        }

        fun decodeTopProcesses(json: String): List<ProfilerTopProcess> {
            return try {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(
                            ProfilerTopProcess(
                                pid = obj.optInt("pid"),
                                packageName = obj.optString("pkg"),
                                displayName = obj.optString("name"),
                                cpuPercent = obj.optDouble("cpu", 0.0).toFloat(),
                                ramMb = obj.optDouble("ram", 0.0).toFloat(),
                                wakeLockCount = obj.optInt("wl", 0)
                            )
                        )
                    }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }

        fun encodeLmkEvents(events: List<LmkKillEvent>): String {
            if (events.isEmpty()) return "[]"
            val arr = JSONArray()
            for (ev in events.take(10)) {
                arr.put(
                    JSONObject().apply {
                        put("ts", ev.timestampLabel)
                        put("ms", ev.timestampMs)
                        put("pkg", ev.packageName)
                        put("reason", ev.reason)
                    }
                )
            }
            return arr.toString()
        }

        fun decodeLmkEvents(json: String): List<LmkKillEvent> {
            if (json.isBlank() || json == "[]") return emptyList()
            return try {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(
                            LmkKillEvent(
                                timestampLabel = obj.optString("ts"),
                                timestampMs = obj.optLong("ms"),
                                packageName = obj.optString("pkg"),
                                reason = obj.optString("reason")
                            )
                        )
                    }
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}
