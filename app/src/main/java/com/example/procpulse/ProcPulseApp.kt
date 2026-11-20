package com.example.procpulse

import android.app.Application
import com.example.procpulse.core.bridge.PackageCacheManager
import com.example.procpulse.core.bridge.StandardHardwareHelper
import com.example.procpulse.core.bridge.UnifiedTelemetryBridge
import com.example.procpulse.core.database.ProcPulseDatabase
import com.example.procpulse.core.database.ProfilerRepository
import com.example.procpulse.core.database.SettingsRepository
import com.example.procpulse.service.ProcPulseCoordinator

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

    override fun onCreate() {
        super.onCreate()
        database = ProcPulseDatabase.getInstance(this)
        settingsRepo = SettingsRepository(this)
        profilerRepo = ProfilerRepository(database.profilerDao(), database.watchdogDao())
        val packageCache = PackageCacheManager(this)
        val hardwareHelper = StandardHardwareHelper(this, packageCache)
        bridge = UnifiedTelemetryBridge(this, packageCache, hardwareHelper)
        coordinator = ProcPulseCoordinator(this, bridge, settingsRepo, profilerRepo)
    }
}
