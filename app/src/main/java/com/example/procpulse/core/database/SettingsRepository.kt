package com.example.procpulse.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.procpulse.core.model.AccentPreset
import com.example.procpulse.core.model.AppSettings
import com.example.procpulse.core.model.CurrentCalibrationMode
import com.example.procpulse.core.model.OverlayConfig
import com.example.procpulse.core.model.OverlayPreset
import com.example.procpulse.core.model.OverlayWidgetMetric
import com.example.procpulse.core.model.PreferredTierMode
import com.example.procpulse.core.model.ThemeMode
import com.example.procpulse.core.model.WatchdogConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.procPulseDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "procpulse_settings"
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_PRESET = stringPreferencesKey("accent_preset")
        val PREFERRED_TIER = stringPreferencesKey("preferred_tier")
        val SAMPLING_INTERVAL = longPreferencesKey("sampling_interval_ms")
        val INVERT_CURRENT = booleanPreferencesKey("invert_current_sign")
        val CALIBRATION_MODE = stringPreferencesKey("current_calibration_mode")
        val BOOT_WATCHDOG = booleanPreferencesKey("boot_watchdog")
        val WHITELIST = stringSetPreferencesKey("whitelist_packages")
        val BLACKLIST = stringSetPreferencesKey("blacklist_packages")

        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val OVERLAY_PRESET = stringPreferencesKey("overlay_preset")
        val OVERLAY_METRICS = stringSetPreferencesKey("overlay_metrics")
        val OVERLAY_PASS_THROUGH = booleanPreferencesKey("overlay_pass_through")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val OVERLAY_FONT_SIZE = intPreferencesKey("overlay_font_size")
        val OVERLAY_SNAP = booleanPreferencesKey("overlay_snap")

        val WD_ENABLED = booleanPreferencesKey("wd_enabled")
        val WD_CPU_THRESHOLD = intPreferencesKey("wd_cpu_threshold")
        val WD_CPU_DURATION = intPreferencesKey("wd_cpu_duration")
        val WD_TEMP_THRESHOLD = intPreferencesKey("wd_temp_threshold")
        val WD_RAM_THRESHOLD = intPreferencesKey("wd_ram_threshold")
        val WD_AUTO_KILL = booleanPreferencesKey("wd_auto_kill")
        val WD_SCREEN_OFF_FREEZE = intPreferencesKey("wd_screen_off_freeze")
    }

    val settingsFlow: Flow<AppSettings> = context.procPulseDataStore.data.map { prefs ->
        val defaultOverlay = OverlayConfig()
        val defaultWatchdog = WatchdogConfig()
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.AMOLED_DARK,
            accentPreset = prefs[Keys.ACCENT_PRESET]?.let { runCatching { AccentPreset.valueOf(it) }.getOrNull() }
                ?: AccentPreset.SIGNAL_AMBER,
            preferredTierMode = prefs[Keys.PREFERRED_TIER]?.let { runCatching { PreferredTierMode.valueOf(it) }.getOrNull() }
                ?: PreferredTierMode.AUTO,
            samplingIntervalMs = prefs[Keys.SAMPLING_INTERVAL] ?: 1000L,
            invertCurrentSign = prefs[Keys.INVERT_CURRENT] ?: false,
            currentCalibrationMode = prefs[Keys.CALIBRATION_MODE]?.let {
                runCatching { CurrentCalibrationMode.valueOf(it) }.getOrNull()
            } ?: CurrentCalibrationMode.AUTO,
            startWatchdogOnBoot = prefs[Keys.BOOT_WATCHDOG] ?: false,
            whitelistPackages = prefs[Keys.WHITELIST] ?: emptySet(),
            blacklistPackages = prefs[Keys.BLACKLIST] ?: emptySet(),
            overlayConfig = OverlayConfig(
                enabled = prefs[Keys.OVERLAY_ENABLED] ?: defaultOverlay.enabled,
                preset = prefs[Keys.OVERLAY_PRESET]?.let { runCatching { OverlayPreset.valueOf(it) }.getOrNull() }
                    ?: defaultOverlay.preset,
                metrics = prefs[Keys.OVERLAY_METRICS]?.mapNotNull {
                    runCatching { OverlayWidgetMetric.valueOf(it) }.getOrNull()
                }?.toSet() ?: defaultOverlay.metrics,
                passThrough = prefs[Keys.OVERLAY_PASS_THROUGH] ?: defaultOverlay.passThrough,
                backgroundOpacity = prefs[Keys.OVERLAY_OPACITY] ?: defaultOverlay.backgroundOpacity,
                fontSizeSp = prefs[Keys.OVERLAY_FONT_SIZE] ?: defaultOverlay.fontSizeSp,
                snapToEdges = prefs[Keys.OVERLAY_SNAP] ?: defaultOverlay.snapToEdges
            ),
            watchdogConfig = WatchdogConfig(
                enabled = prefs[Keys.WD_ENABLED] ?: defaultWatchdog.enabled,
                cpuThresholdPercent = prefs[Keys.WD_CPU_THRESHOLD] ?: defaultWatchdog.cpuThresholdPercent,
                cpuDurationMinutes = prefs[Keys.WD_CPU_DURATION] ?: defaultWatchdog.cpuDurationMinutes,
                tempThresholdCelsius = prefs[Keys.WD_TEMP_THRESHOLD] ?: defaultWatchdog.tempThresholdCelsius,
                freeRamThresholdMb = prefs[Keys.WD_RAM_THRESHOLD] ?: defaultWatchdog.freeRamThresholdMb,
                autoKillBlacklisted = prefs[Keys.WD_AUTO_KILL] ?: defaultWatchdog.autoKillBlacklisted,
                screenOffFreezeDelayMinutes = prefs[Keys.WD_SCREEN_OFF_FREEZE] ?: defaultWatchdog.screenOffFreezeDelayMinutes
            )
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.procPulseDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setAccentPreset(preset: AccentPreset) {
        context.procPulseDataStore.edit { it[Keys.ACCENT_PRESET] = preset.name }
    }

    suspend fun setPreferredTierMode(mode: PreferredTierMode) {
        context.procPulseDataStore.edit { it[Keys.PREFERRED_TIER] = mode.name }
    }

    suspend fun setSamplingIntervalMs(intervalMs: Long) {
        context.procPulseDataStore.edit { it[Keys.SAMPLING_INTERVAL] = intervalMs }
    }

    suspend fun setInvertCurrentSign(invert: Boolean) {
        context.procPulseDataStore.edit { it[Keys.INVERT_CURRENT] = invert }
    }

    suspend fun setCurrentCalibrationMode(mode: CurrentCalibrationMode) {
        context.procPulseDataStore.edit { it[Keys.CALIBRATION_MODE] = mode.name }
    }

    suspend fun setStartWatchdogOnBoot(enabled: Boolean) {
        context.procPulseDataStore.edit { it[Keys.BOOT_WATCHDOG] = enabled }
    }

    suspend fun addToWhitelist(pkg: String) {
        context.procPulseDataStore.edit { prefs ->
            val current = prefs[Keys.WHITELIST] ?: emptySet()
            prefs[Keys.WHITELIST] = current + pkg
            val black = prefs[Keys.BLACKLIST] ?: emptySet()
            if (pkg in black) prefs[Keys.BLACKLIST] = black - pkg
        }
    }

    suspend fun removeFromWhitelist(pkg: String) {
        context.procPulseDataStore.edit { prefs ->
            val current = prefs[Keys.WHITELIST] ?: emptySet()
            prefs[Keys.WHITELIST] = current - pkg
        }
    }

    suspend fun toggleBlacklist(pkg: String) {
        context.procPulseDataStore.edit { prefs ->
            val current = prefs[Keys.BLACKLIST] ?: emptySet()
            prefs[Keys.BLACKLIST] = if (pkg in current) current - pkg else current + pkg
        }
    }

    suspend fun updateOverlayConfig(config: OverlayConfig) {
        context.procPulseDataStore.edit { prefs ->
            prefs[Keys.OVERLAY_ENABLED] = config.enabled
            prefs[Keys.OVERLAY_PRESET] = config.preset.name
            prefs[Keys.OVERLAY_METRICS] = config.metrics.map { it.name }.toSet()
            prefs[Keys.OVERLAY_PASS_THROUGH] = config.passThrough
            prefs[Keys.OVERLAY_OPACITY] = config.backgroundOpacity
            prefs[Keys.OVERLAY_FONT_SIZE] = config.fontSizeSp
            prefs[Keys.OVERLAY_SNAP] = config.snapToEdges
        }
    }

    suspend fun updateWatchdogConfig(config: WatchdogConfig) {
        context.procPulseDataStore.edit { prefs ->
            prefs[Keys.WD_ENABLED] = config.enabled
            prefs[Keys.WD_CPU_THRESHOLD] = config.cpuThresholdPercent
            prefs[Keys.WD_CPU_DURATION] = config.cpuDurationMinutes
            prefs[Keys.WD_TEMP_THRESHOLD] = config.tempThresholdCelsius
            prefs[Keys.WD_RAM_THRESHOLD] = config.freeRamThresholdMb
            prefs[Keys.WD_AUTO_KILL] = config.autoKillBlacklisted
            prefs[Keys.WD_SCREEN_OFF_FREEZE] = config.screenOffFreezeDelayMinutes
        }
    }
}
