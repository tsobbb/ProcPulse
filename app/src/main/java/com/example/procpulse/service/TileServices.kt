package com.example.procpulse.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.procpulse.ProcPulseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        val app = application as ProcPulseApp
        val enabled = app.coordinator.settingsFlow.value.overlayConfig.enabled
        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (enabled) "Активен" else "Выкл"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val app = application as ProcPulseApp
        val current = app.coordinator.settingsFlow.value.overlayConfig
        if (!current.enabled && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(intent)
            return
        }
        val next = !current.enabled
        scope.launch {
            app.coordinator.settingsRepo.updateOverlayConfig(current.copy(enabled = next))
            if (next) {
                ProcPulseMonitorService.startOrUpdate(this@OverlayTileService)
            }
            qsTile?.apply {
                state = if (next) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                subtitle = if (next) "Активен" else "Выкл"
                updateTile()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class ProfilerTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        val app = application as ProcPulseApp
        val recording = app.coordinator.profilerState.value.isRecording
        qsTile?.apply {
            state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (recording) "Идёт запись" else "Готов"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val app = application as ProcPulseApp
        val recording = app.coordinator.profilerState.value.isRecording
        scope.launch {
            if (recording) {
                app.coordinator.stopProfilerSession()
            } else {
                app.coordinator.startProfilerSession("", app.coordinator.settingsFlow.value.samplingIntervalMs, 0)
            }
            val nowRec = !recording
            qsTile?.apply {
                state = if (nowRec) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                subtitle = if (nowRec) "Идёт запись" else "Готов"
                updateTile()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? ProcPulseApp ?: return
        val settings = app.coordinator.settingsFlow.value
        if (settings.startWatchdogOnBoot && settings.watchdogConfig.enabled) {
            app.coordinator.evaluateCollectorState()
            ProcPulseMonitorService.startOrUpdate(context)
        }
    }
}
