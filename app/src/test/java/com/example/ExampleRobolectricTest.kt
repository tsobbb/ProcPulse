package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.procpulse.core.database.LagReportAnalyzer
import com.example.procpulse.core.database.ProfilerRepository
import com.example.procpulse.core.database.ProfilerSampleEntity
import com.example.procpulse.core.database.ProfilerSessionEntity
import com.example.procpulse.core.model.FloatRingBuffer
import com.example.procpulse.core.model.LagSeverity
import com.example.procpulse.core.model.LmkKillEvent
import com.example.procpulse.core.model.ProcessCategory
import com.example.procpulse.core.model.ProcessEntry
import com.example.procpulse.core.model.ProcessStateTag
import com.example.procpulse.core.model.ProfilerTopProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @Test
    fun `verify ProcPulse app name and strict offline manifest without INTERNET`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ProcPulse", appName)

        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val permissions = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertFalse("INTERNET permission must not exist in ProcPulse", Manifest.permission.INTERNET in permissions)
    }

    @Test
    fun `verify FloatRingBuffer circular overwrite and min max`() {
        val ring = FloatRingBuffer(capacity = 4)
        ring.push(10f, 100L)
        ring.push(25f, 200L)
        ring.push(5f, 300L)
        ring.push(40f, 400L)
        ring.push(20f, 500L) // overwrites 10f

        assertEquals(4, ring.count)
        assertEquals(25f, ring.valueAt(0), 0.01f)
        assertEquals(5f, ring.minOrZero(), 0.01f)
        assertEquals(40f, ring.maxOrZero(), 0.01f)
        assertEquals(20f, ring.latest(), 0.01f)
    }

    @Test
    fun `verify ProfilerRepository top process serialization roundtrip`() {
        val input = listOf(
            ProfilerTopProcess(pid = 1234, packageName = "com.test.app", displayName = "Test App", cpuPercent = 42.5f, ramMb = 256f)
        )
        val json = ProfilerRepository.encodeTopProcesses(input)
        val decoded = ProfilerRepository.decodeTopProcesses(json)
        assertEquals(1, decoded.size)
        assertEquals(1234, decoded[0].pid)
        assertEquals("com.test.app", decoded[0].packageName)
        assertEquals(42.5f, decoded[0].cpuPercent, 0.01f)
    }

    @Test
    fun `verify Tier 0 ProcessEntry uses distinct processKey when pid is null`() {
        val first = ProcessEntry(
            pid = null,
            ppid = 0,
            uid = 10155,
            packageName = "com.example.alpha",
            displayName = "Alpha",
            category = ProcessCategory.APPS,
            stateTag = ProcessStateTag.BGD,
            cpuPercent = null,
            ramPssMb = null,
            ramUssMb = null,
            netBytesTotal = 0L,
            oomScoreAdj = null,
            uptimeSeconds = 0L,
            isProtected = false,
            isFrozenOrStopped = false
        )
        val second = first.copy(uid = 10156, packageName = "com.example.beta", displayName = "Beta")
        assertNotEquals(first.processKey(), second.processKey())
        assertEquals("-1_10155_com.example.alpha", first.processKey())
    }

    @Test
    fun `verify LagReportAnalyzer detects CPU Cap, LMKD, Disk IO and Frame Jank into Markdown`() {
        val session = ProfilerSessionEntity(
            id = 42L,
            title = "Диагностика лагов (3 мин)",
            startTimeMs = 1_000_000L,
            endTimeMs = 1_120_000L,
            intervalMs = 2000L,
            targetDurationSec = 180,
            tierUsed = "TIER 1",
            isLagDiagnostic = true
        )
        val killsJson = ProfilerRepository.encodeLmkEvents(
            listOf(
                LmkKillEvent("12:00:01", 1_010_000L, "com.heavy.game", "lmkd"),
                LmkKillEvent("12:00:05", 1_015_000L, "com.heavy.app", "am_kill"),
                LmkKillEvent("12:00:09", 1_020_000L, "com.social.feed", "am_kill")
            )
        )
        val samples = listOf(
            ProfilerSampleEntity(
                sessionId = 42L,
                timestampMs = 1_002_000L,
                cpuTotalPercent = 88f,
                clusterFreqsSummary = "Prime:1800MHz",
                tempCelsius = 80f,
                batteryTempCelsius = 43f,
                currentMa = -1400f,
                freeRamMb = 280L,
                zramUsedMb = 2100L,
                fps = 32f,
                topProcessesJson = "[]",
                pswpinPerSec = 150f,
                pswpoutPerSec = 180f,
                pgmajfaultPerSec = 95f,
                allocstallPerSec = 6.2f,
                memAvailableMb = 280L,
                ioWaitPercent = 24f,
                ioBusyPercent = 82f,
                procsRunning = 19,
                procsBlocked = 3,
                isCpuCapped = true,
                scalingMaxSummary = "Prime:1800/2800MHz",
                amKillEventsJson = killsJson,
                foregroundPackage = "com.heavy.game",
                jankPercent = 28.5f,
                jankStage = "GPU/RenderThread",
                rendererType = "Skia (Vulkan)"
            )
        )

        val report = LagReportAnalyzer.analyze(session, samples)
        assertEquals(5, report.categories.size)
        assertTrue(report.categories.all { it.severity == LagSeverity.CRITICAL })
        assertEquals(1, report.appJankSummaries.size)
        assertTrue(report.markdownReport.contains("com.heavy.game"))
        assertTrue(report.markdownReport.contains("[КРИТИЧНО]"))
    }
}
