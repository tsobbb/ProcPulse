package com.example.procpulse.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profiler_sessions")
data class ProfilerSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long?,
    val intervalMs: Long,
    val targetDurationSec: Int,
    val tierUsed: String,
    val peakCpuPercent: Float = 0f,
    val peakTempCelsius: Float = 0f,
    val minFreeRamMb: Long = 0L,
    val avgCurrentMa: Float = 0f,
    val sampleCount: Int = 0,
    val isLagDiagnostic: Boolean = false
)

@Entity(
    tableName = "profiler_samples",
    foreignKeys = [
        ForeignKey(
            entity = ProfilerSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("timestampMs")]
)
data class ProfilerSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestampMs: Long,
    val cpuTotalPercent: Float,
    val clusterFreqsSummary: String,
    val tempCelsius: Float,
    val batteryTempCelsius: Float,
    val currentMa: Float,
    val freeRamMb: Long,
    val zramUsedMb: Long,
    val fps: Float?,
    val topProcessesJson: String,
    val pswpinPerSec: Float = 0f,
    val pswpoutPerSec: Float = 0f,
    val pgmajfaultPerSec: Float = 0f,
    val allocstallPerSec: Float = 0f,
    val memAvailableMb: Long = 0L,
    val ioWaitPercent: Float = 0f,
    val ioBusyPercent: Float = 0f,
    val procsRunning: Int = 0,
    val procsBlocked: Int = 0,
    val isCpuCapped: Boolean = false,
    val scalingMaxSummary: String = "",
    val amKillEventsJson: String = "[]",
    val foregroundPackage: String? = null,
    val jankPercent: Float? = null,
    val jankStage: String? = null,
    val rendererType: String? = null
)

@Entity(tableName = "watchdog_events")
data class WatchdogEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val ruleType: String,
    val triggerPackage: String?,
    val description: String,
    val actionTaken: String
)
