package com.example.procpulse

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.procpulse.core.bridge.PackageCacheManager
import com.example.procpulse.core.bridge.StandardHardwareHelper
import com.example.procpulse.core.bridge.UnifiedTelemetryBridge
import com.example.procpulse.core.database.ProcPulseDatabase
import com.example.procpulse.core.database.ProfilerRepository
import com.example.procpulse.core.database.SettingsRepository
import com.example.procpulse.service.ProcPulseCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ProcPulseApp : Application() {

    lateinit var database: ProcPulseDatabase
        private set
    lateinit var settingsRepo: SettingsRepository
        private set
    lateinit var profilerRepo: ProfilerRepository
        private set
    lateinit var bridge: UnifiedTelemetryBridge
        private set
    lateinit var coordinator: ProcPulseCoordinator
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var unbindJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Never initialize UI/Room/DataStore/Bridge inside secondary helper processes
        if (getProcessName() != packageName) return

        database = ProcPulseDatabase.getInstance(this)
        settingsRepo = SettingsRepository(this)
        profilerRepo = ProfilerRepository(database.profilerDao(), database.watchdogDao())
        val packageCache = PackageCacheManager(this)
        val hardwareHelper = StandardHardwareHelper(this, packageCache)
        bridge = UnifiedTelemetryBridge(this, packageCache, hardwareHelper)
        coordinator = ProcPulseCoordinator(this, bridge, settingsRepo, profilerRepo)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                unbindJob?.cancel()
                unbindJob = null
                appScope.launch {
                    bridge.reconnectShizukuIfAlive()
                    coordinator.evaluateCollectorState()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                unbindJob?.cancel()
                unbindJob = appScope.launch {
                    delay(5_000L)
                    if (!coordinator.hasActiveBackgroundConsumers()) {
                        bridge.unbindShizukuService()
                    }
                }
            }
        })
    }
}
