package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.procpulse.core.database.ProfilerRepository
import com.example.procpulse.core.model.FloatRingBuffer
import com.example.procpulse.core.model.ProfilerTopProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
}
