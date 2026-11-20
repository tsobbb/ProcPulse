package com.example.procpulse.core.bridge

import com.example.procpulse.core.model.AppSettings
import com.example.procpulse.core.model.CapabilityTier
import com.example.procpulse.core.model.LogcatEntry
import com.example.procpulse.core.model.ProcessCategory
import com.example.procpulse.core.model.ProcessDetail
import com.example.procpulse.core.model.ProcessEntry
import com.example.procpulse.core.model.SocketConnection
import com.example.procpulse.core.model.SystemSnapshot

data class ProcessActionOutcome(
    val success: Boolean,
    val message: String,
    val fallbackToTier: CapabilityTier? = null
)

interface SystemTelemetryBridge {
    val activeTier: CapabilityTier

    suspend fun refreshCapabilities(settings: AppSettings): CapabilityTier

    suspend fun collectSnapshot(settings: AppSettings): SystemSnapshot

    suspend fun collectProcesses(
        settings: AppSettings,
        category: ProcessCategory? = null
    ): List<ProcessEntry>

    suspend fun collectProcessDetail(
        entry: ProcessEntry,
        settings: AppSettings
    ): ProcessDetail

    suspend fun collectSockets(filterUid: Int? = null): List<SocketConnection>

    suspend fun collectLogcat(pid: Int, maxLines: Int = 200): List<LogcatEntry>

    suspend fun requestShizukuPermission(): Boolean

    suspend fun checkRootAccess(): Boolean

    // Task Control Actions
    suspend fun killBackground(packageName: String): ProcessActionOutcome
    suspend fun forceStop(packageName: String): ProcessActionOutcome
    suspend fun setFreeze(packageName: String, freeze: Boolean): ProcessActionOutcome
    suspend fun setBackgroundRestricted(packageName: String, restrict: Boolean): ProcessActionOutcome
    suspend fun sendSignal(pid: Int, signal: Int): ProcessActionOutcome // e.g., 9=SIGKILL, 19=SIGSTOP, 18=SIGCONT
    suspend fun reniceProcess(pid: Int, nice: Int): ProcessActionOutcome
    suspend fun setCpuAffinity(pid: Int, hexMask: String): ProcessActionOutcome
    suspend fun setComponentEnabled(packageName: String, componentClass: String, enabled: Boolean): ProcessActionOutcome
}
