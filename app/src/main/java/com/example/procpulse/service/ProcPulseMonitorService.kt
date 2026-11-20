package com.example.procpulse.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.res.ResourcesCompat
import com.example.MainActivity
import com.example.R
import com.example.procpulse.ProcPulseApp
import com.example.procpulse.core.model.AppSettings
import com.example.procpulse.core.model.OverlayConfig
import com.example.procpulse.core.model.OverlayPreset
import com.example.procpulse.core.model.OverlayWidgetMetric
import com.example.procpulse.core.model.SystemSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class ProcPulseMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var coordinator: ProcPulseCoordinator
    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager

    private var overlayView: LinearLayout? = null
    private var primaryTextView: TextView? = null
    private var secondaryTextView: TextView? = null
    private var sparklineView: HudSparklineView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lastAppliedConfig: OverlayConfig? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as ProcPulseApp
        coordinator = app.coordinator
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        startAsForegroundSafe()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> coordinator.togglePause()
            ACTION_TOGGLE_PASSTHROUGH -> {
                scope.launch {
                    val cur = coordinator.settingsFlow.value.overlayConfig
                    coordinator.settingsRepo.updateOverlayConfig(cur.copy(passThrough = !cur.passThrough))
                }
            }
            ACTION_STOP_ALL -> {
                scope.launch {
                    val cur = coordinator.settingsFlow.value.overlayConfig
                    coordinator.settingsRepo.updateOverlayConfig(cur.copy(enabled = false))
                    if (coordinator.profilerState.value.isRecording) {
                        coordinator.stopProfilerSession()
                    }
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startAsForegroundSafe() {
        val notification = buildStatusNotification(
            snapshot = coordinator.snapshot.value,
            paused = coordinator.isPaused.value,
            overlayCfg = coordinator.settingsFlow.value.overlayConfig,
            profiler = coordinator.profilerState.value
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeState() {
        scope.launch {
            combine(
                coordinator.settingsFlow,
                coordinator.snapshot,
                coordinator.isPaused,
                coordinator.profilerState
            ) { settings, snap, paused, profiler ->
                QuadState(settings, snap, paused, profiler)
            }.collect { (settings, snap, paused, profiler) ->
                coordinator.evaluateCollectorState()

                val needOverlay = settings.overlayConfig.enabled && Settings.canDrawOverlays(this@ProcPulseMonitorService)
                if (needOverlay) {
                    ensureOrUpdateOverlay(settings.overlayConfig, snap)
                } else {
                    removeOverlay()
                }

                if (!settings.overlayConfig.enabled && !profiler.isRecording && !settings.watchdogConfig.enabled) {
                    stopSelf()
                    return@collect
                }

                val notif = buildStatusNotification(snap, paused, settings.overlayConfig, profiler)
                notificationManager.notify(NOTIFICATION_ID, notif)
            }
        }
    }

    private fun ensureOrUpdateOverlay(config: OverlayConfig, snap: SystemSnapshot?) {
        if (overlayView == null) {
            createOverlayView(config)
        } else if (lastAppliedConfig != config) {
            updateOverlayWindowFlags(config)
            applyOverlayStyling(config)
        }
        lastAppliedConfig = config

        val pText = primaryTextView ?: return
        val sText = secondaryTextView ?: return
        val spark = sparklineView

        if (snap == null) {
            pText.text = "ProcPulse: Инициализация..."
            sText.visibility = View.GONE
            return
        }

        val parts = buildMetricTokens(config, snap)
        when (config.preset) {
            OverlayPreset.SINGLE_LINE -> {
                pText.text = parts.joinToString(" | ")
                sText.visibility = View.GONE
                spark?.visibility = View.GONE
            }
            OverlayPreset.CORNER_SPARKLINE -> {
                pText.text = parts.take(3).joinToString(" · ")
                val rest = parts.drop(3)
                if (rest.isNotEmpty()) {
                    sText.visibility = View.VISIBLE
                    sText.text = rest.joinToString("\n")
                } else {
                    sText.visibility = View.GONE
                }
                spark?.visibility = View.VISIBLE
                spark?.pushValue(snap.cpu.totalUsagePercent ?: 0f)
            }
            OverlayPreset.GAMING_HUD -> {
                val fpsStr = if (snap.fps.available && snap.fps.fps != null) {
                    "${snap.fps.fps.toInt()} FPS (${"%.1f".format(Locale.US, snap.fps.frameTimeMs ?: 0f)}ms)"
                } else {
                    "FPS: Tier 1/2"
                }
                pText.text = fpsStr
                sText.visibility = View.VISIBLE
                sText.text = parts.filterNot { it.startsWith("FPS") }.joinToString(" · ")
                spark?.visibility = View.VISIBLE
                spark?.pushValue(snap.fps.fps ?: (snap.cpu.totalUsagePercent ?: 0f))
            }
        }
    }

    private fun buildMetricTokens(config: OverlayConfig, snap: SystemSnapshot): List<String> {
        val list = mutableListOf<String>()
        if (OverlayWidgetMetric.FPS in config.metrics) {
            list.add(if (snap.fps.available && snap.fps.fps != null) "FPS ${snap.fps.fps.toInt()}" else "FPS --")
        }
        if (OverlayWidgetMetric.FRAME_TIME in config.metrics) {
            list.add(if (snap.fps.frameTimeMs != null) "${"%.1f".format(Locale.US, snap.fps.frameTimeMs)}ms" else "--ms")
        }
        if (OverlayWidgetMetric.CPU in config.metrics) {
            val cpuStr = snap.cpu.totalUsagePercent?.let { "${it.toInt()}%" } ?: "${snap.cpu.cores.maxOfOrNull { c -> c.currentFreqMhz } ?: 0}MHz"
            list.add("CPU $cpuStr")
        }
        if (OverlayWidgetMetric.TEMP in config.metrics) {
            val t = snap.thermal.zones.firstOrNull { it.friendlyCategory == "CPU" }?.tempCelsius ?: snap.battery.tempCelsius
            list.add("${"%.1f".format(Locale.US, t)}°C")
        }
        if (OverlayWidgetMetric.POWER in config.metrics) {
            list.add("${"%.0f".format(Locale.US, snap.battery.currentMa)}mA ${"%.1f".format(Locale.US, snap.battery.powerWatts)}W")
        }
        if (OverlayWidgetMetric.RAM_ZRAM in config.metrics) {
            val used = (snap.memory.totalMb - snap.memory.availableMb).coerceAtLeast(0L)
            list.add("RAM ${used}/${snap.memory.totalMb}M")
        }
        if (OverlayWidgetMetric.NET in config.metrics) {
            val rxKb = (snap.network.wifiRxBytesPerSec + snap.network.cellRxBytesPerSec) / 1024L
            val txKb = (snap.network.wifiTxBytesPerSec + snap.network.cellTxBytesPerSec) / 1024L
            list.add("↓${rxKb}K ↑${txKb}K")
        }
        if (OverlayWidgetMetric.TOP_PROCESS in config.metrics) {
            val top = snap.topCpuConsumers.firstOrNull()
            if (top != null) {
                list.add("TOP ${top.displayName.take(10)} ${top.cpuPercent?.toInt() ?: 0}%")
            }
        }
        return list
    }

    private fun createOverlayView(config: OverlayConfig) {
        val monoFont = runCatching { ResourcesCompat.getFont(this, R.font.jetbrains_mono) }.getOrNull() ?: Typeface.MONOSPACE

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(10)
            val padV = dp(6)
            setPadding(padH, padV, padH, padV)
        }

        val pText = TextView(this).apply {
            typeface = monoFont
            fontFeatureSettings = "tnum"
            setTextColor(Color.parseColor("#F59E0B"))
        }
        val sText = TextView(this).apply {
            typeface = monoFont
            fontFeatureSettings = "tnum"
            setTextColor(Color.parseColor("#FAFAFA"))
        }
        val spark = HudSparklineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(22)).apply {
                topMargin = dp(4)
            }
        }

        container.addView(pText)
        container.addView(sText)
        container.addView(spark)

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (config.passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(48)
        }

        attachDragListener(container, params, config)

        overlayView = container
        primaryTextView = pText
        secondaryTextView = sText
        sparklineView = spark
        layoutParams = params
        applyOverlayStyling(config)

        runCatching { windowManager.addView(container, params) }
    }

    private fun applyOverlayStyling(config: OverlayConfig) {
        val alphaInt = (config.backgroundOpacity.coerceIn(0.2f, 1.0f) * 255).toInt()
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.argb(alphaInt, 9, 9, 11))
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), Color.parseColor("#27272A"))
        }
        overlayView?.background = bgDrawable

        val sizeSp = config.fontSizeSp.toFloat()
        primaryTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (config.preset == OverlayPreset.GAMING_HUD) sizeSp + 3f else sizeSp)
        secondaryTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, (sizeSp - 1f).coerceAtLeast(9f))
    }

    private fun updateOverlayWindowFlags(config: OverlayConfig) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (config.passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        attachDragListener(view, params, config)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun attachDragListener(view: View, params: WindowManager.LayoutParams, config: OverlayConfig) {
        if (config.passThrough) {
            view.setOnTouchListener(null)
            return
        }
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        runCatching { windowManager.updateViewLayout(v, params) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (config.snapToEdges) {
                            val displayW = resources.displayMetrics.widthPixels
                            val viewW = v.width
                            params.x = if (params.x + viewW / 2 < displayW / 2) dp(12) else (displayW - viewW - dp(12)).coerceAtLeast(dp(12))
                            runCatching { windowManager.updateViewLayout(v, params) }
                        }
                        if (abs(event.rawX - touchX) < 10f && abs(event.rawY - touchY) < 10f) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun removeOverlay() {
        overlayView?.let { v ->
            runCatching { windowManager.removeView(v) }
        }
        overlayView = null
        primaryTextView = null
        secondaryTextView = null
        sparklineView = null
        layoutParams = null
    }

    private fun buildStatusNotification(
        snapshot: SystemSnapshot?,
        paused: Boolean,
        overlayCfg: OverlayConfig,
        profiler: ActiveProfilerState
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pausePi = PendingIntent.getService(
            this,
            1,
            Intent(this, ProcPulseMonitorService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val passPi = PendingIntent.getService(
            this,
            2,
            Intent(this, ProcPulseMonitorService::class.java).setAction(ACTION_TOGGLE_PASSTHROUGH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this,
            3,
            Intent(this, ProcPulseMonitorService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val summary = if (snapshot != null) {
            val cpu = snapshot.cpu.totalUsagePercent?.let { "${it.toInt()}%" } ?: "--%"
            val ramUsed = (snapshot.memory.totalMb - snapshot.memory.availableMb).coerceAtLeast(0L)
            val temp = snapshot.thermal.zones.firstOrNull { it.friendlyCategory == "CPU" }?.tempCelsius
                ?: snapshot.battery.tempCelsius
            val ma = snapshot.battery.currentMa.toInt()
            "CPU $cpu | RAM ${ramUsed}MB | ${"%.1f".format(Locale.US, temp)}°C | ${ma}mA"
        } else {
            "Сбор системной телеметрии..."
        }

        // Android 16 Live Updates (Notification.ProgressStyle) when timed Blackbox Profiler is active
        if (profiler.isRecording && Build.VERSION.SDK_INT >= 36) {
            val totalSec = if (profiler.targetDurationSec > 0) profiler.targetDurationSec else 3600
            val elapsedSec = ((System.currentTimeMillis() - profiler.startTimeMs) / 1000L).toInt().coerceIn(0, totalSec)
            val builder = Notification.Builder(this, ProcPulseCoordinator.CHANNEL_PROFILER)
                .setSmallIcon(R.drawable.ic_notification_pulse)
                .setContentTitle("Blackbox: ${profiler.title}")
                .setContentText("$summary • Срезов: ${profiler.samplesRecorded}")
                .setSubText(if (profiler.targetDurationSec > 0) "${elapsedSec}/${totalSec}с" else "LIVE")
                .setOngoing(true)
                .setContentIntent(openIntent)
                .addAction(Notification.Action.Builder(null, "Стоп", stopPi).build())

            runCatching {
                val progressStyle = Notification.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgress(elapsedSec)
                    .setProgressSegments(
                        listOf(
                            Notification.ProgressStyle.Segment(totalSec)
                                .setColor(Color.parseColor("#F59E0B"))
                        )
                    )
                    .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_notification_pulse))
                builder.setStyle(progressStyle)
            }
            return builder.build()
        }

        val title = when {
            profiler.isRecording -> "Blackbox Запись (${profiler.samplesRecorded} ср.)"
            overlayCfg.enabled -> "ProcPulse HUD (${if (overlayCfg.passThrough) "Pass-through" else "Интерактивный"})"
            else -> "ProcPulse Монитор"
        }

        return NotificationCompat.Builder(this, ProcPulseCoordinator.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification_pulse)
            .setContentTitle(title)
            .setContentText(summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, if (paused) "Продолжить" else "Пауза", pausePi)
            .addAction(0, if (overlayCfg.passThrough) "Сдвиг ВКЛ" else "Pass-through", passPi)
            .addAction(0, "Стоп", stopPi)
            .build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class QuadState(
        val settings: AppSettings,
        val snapshot: SystemSnapshot?,
        val paused: Boolean,
        val profiler: ActiveProfilerState
    )

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_PAUSE = "com.example.procpulse.ACTION_TOGGLE_PAUSE"
        const val ACTION_TOGGLE_PASSTHROUGH = "com.example.procpulse.ACTION_TOGGLE_PASSTHROUGH"
        const val ACTION_STOP_ALL = "com.example.procpulse.ACTION_STOP_ALL"

        fun startOrUpdate(context: Context): Boolean {
            val intent = Intent(context, ProcPulseMonitorService::class.java)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    false
                } else {
                    false
                }
            }
        }
    }
}

private class HudSparklineView(context: Context) : View(context) {
    private val buf = FloatArray(30)
    private var count = 0
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val path = Path()

    fun pushValue(v: Float) {
        if (count < buf.size) {
            buf[count++] = v
        } else {
            System.arraycopy(buf, 1, buf, 0, buf.size - 1)
            buf[buf.size - 1] = v
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (count < 2) return
        var maxV = 1f
        for (i in 0 until count) if (buf[i] > maxV) maxV = buf[i]
        val w = width.toFloat()
        val h = height.toFloat()
        val step = w / (count - 1).toFloat()
        path.reset()
        for (i in 0 until count) {
            val x = i * step
            val y = h - ((buf[i] / maxV).coerceIn(0f, 1f) * (h - 4f)) - 2f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
